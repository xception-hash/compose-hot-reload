# T33a: Typed ProjectConfig + explicit --app-module / --app-module-dir (T33 phase 1 remainder)
Status: DONE (2026-07-14, Sonnet background agent + coordinator review; branch t33/phase1-wiring)
Assignee: agy

## Outcome (2026-07-14)
Implemented exactly per spec by a Sonnet background agent in an isolated worktree; coordinator
re-ran all acceptance on the merged `t33/phase1-wiring` branch. Notes: Doctor keeps its old
field names as private `get()` delegates onto the config (body untouched); `selectAppModule`
returns the input list reference when both args are null (assertSame-verified). New
ProjectConfigTest 7/7. Host + device gates green (see T33b Outcome for the shared device run).

## Goal
Finish T33 phase 1: consolidate the portability options (landed in PR #9) into one typed
config object shared by the CLI, Doctor, and WatchSession, and make the app module an
explicit choice instead of "first `--module` entry". Pure refactor + two new flags — zero
behavior change for existing invocations.

## Spec

### 1. `engine/src/main/kotlin/dev/hotreload/engine/ProjectConfig.kt` (new)
```kotlin
data class ProjectConfig(
    val projectDir: Path,
    val modules: List<ModuleSpec.Request>,   // FIRST entry is the app module (invariant kept)
    val applicationId: String,
    val variant: String = "debug",
    val projectJavaHome: Path? = null,
    val gradleArgs: List<String> = emptyList(),
    val literals: Boolean = false,
) {
    init {
        require(modules.isNotEmpty()) { "at least one module (the app module) is required" }
        require(variant.isNotBlank()) { "variant must not be blank" }
    }
    val appModule: ModuleSpec.Request get() = modules.first()

    companion object {
        /**
         * Reorder/override for explicit app-module selection.
         * - [appModulePath] (from --app-module): must equal the gradlePath of one entry in
         *   [modules] (normalize through ModuleSpec.Request.parse so `app` == `:app`);
         *   move that entry to index 0. IllegalArgumentException naming the path if absent.
         * - [appModuleDir] (from --app-module-dir): replace the app entry's relativeDir
         *   (after the reorder). Must be relative — reuse the same require as Request.parse.
         * - Both null → modules returned untouched (current behavior).
         */
        fun selectAppModule(
            modules: List<ModuleSpec.Request>,
            appModulePath: String?,
            appModuleDir: String?,
        ): List<ModuleSpec.Request>
    }
}
```

### 2. `WatchSession.Config` → thin wrapper
Replace the 9-field `WatchSession.Config` with:
```kotlin
class Config(
    val project: ProjectConfig,
    val d8: Path,
    val adb: Path,
)
```
Update every `config.<x>` read inside WatchSession to `config.project.<x>` (d8/adb stay on
Config). Do NOT change any logic, log line, or task-name derivation — this is mechanical.

### 3. `Doctor` takes ProjectConfig
`Doctor(config: ProjectConfig, sdkDir: Path, buildTools: String = "36.0.0")` — replace the
current 7-parameter constructor; read modules/appId/variant/projectJavaHome from the config.
Update `DoctorTest` accordingly (assertions unchanged).

### 4. `cli/Main.kt`
- Build one `ProjectConfig` from the parsed options and pass it to both Doctor and
  WatchSession (today the values are threaded twice).
- New single-value options, documented in USAGE:
  `--app-module <gradlePath>` — which watched module is the app (default: first --module).
  `--app-module-dir <dir>`   — physical directory override for the app module.
- Wire them through `ProjectConfig.selectAppModule` AFTER `applyVariantOverrides`; convert
  IllegalArgumentException to the existing `fail(...)` pattern.

### 5. Tests — `engine/src/test/kotlin/dev/hotreload/engine/ProjectConfigTest.kt` (new)
Cover at minimum:
- selectAppModule reorders (`[:a, :b]` + appModulePath ":b" → `[:b, :a]`).
- `app` and `:app` both match a `:app` entry.
- unknown path → IllegalArgumentException naming the path.
- appModuleDir overrides relativeDir of the selected app module only.
- both null → identical list (same order, same instances).
- blank variant / empty modules rejected by init.

## Out of scope
Discovery, profiles, precedence beyond CLI-flag-vs-default (that is T33 phases 2/4), any
change to ModuleSpec probing, any protocol/device code, README (flags get documented when
phase 3 rounds out the option set).

## Acceptance
Run from repo root; all must pass:
```bash
export REPO_ROOT="$PWD" && source scripts/env.sh
./gradlew :engine:test :protocol:test :cli:compileKotlin
# behavior-identity: old invocation shape still parses (exit 0 within 5s is NOT expected —
# just assert USAGE/arg errors don't fire; kill after ready-check is coordinator-run):
cli/build/install/cli/bin/cli watch 2>&1 | grep -q "required" && echo ARGS-OK
git grep -n "moduleNames" -- engine/ cli/  # must output nothing (field fully replaced)
```
Device gate (coordinator runs, emulator required): `./e2e/run.sh` and `./e2e/run-multi.sh`
green — proves the refactor changed nothing observable.
