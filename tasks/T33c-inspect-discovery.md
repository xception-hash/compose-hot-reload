# T33c: `hotreload inspect` + Gradle metadata discovery (T33 phase 2)
Status: TODO
Assignee: agy

## Goal
Add the Gradle-side discovery component and a `hotreload inspect` command that reports
machine-readable project metadata (modules, types, variants, application IDs, tasks,
source/res/class-output dirs, project dependencies). Phase 2 is REPORT-ONLY: nothing in
the watch/doctor path changes, `ModuleSpec` probing stays untouched, and the default
`--variant debug` behavior stays string-identical (e2e suites are the regression
contract). Later phases (3/5/6) consume this report.

## Design decisions (fixed — do not improvise alternatives)
> Feasibility PROVEN 2026-07-14 (coordinator smoke, samples/single-module): a plain
> Groovy init script duck-typing `androidComponents.onVariants(selector().all())`
> reports `debug debuggable=true / release debuggable=false / qa debuggable=true`
> with `applicationId` Providers resolved at task execution — no AGP compile
> dependency needed. The mechanism below is validated; only the schema plumbing and
> per-field capture remain.

1. **Mechanism: init-script-injected task, launched over the Gradle Tooling API** (same
   API `GradleCompiler` already uses — but a fresh short-lived `GradleConnector`
   connection is fine here; do NOT touch GradleCompiler). Rationale: no new published
   artifact, works without the target project applying our plugin (feeds phase 8
   zero-touch), and the JSON file is the contract phase 9's IDE plugin will consume.
   - Groovy init script bundled as an engine resource:
     `engine/src/main/resources/dev/hotreload/inspect.init.gradle`. Extracted to a temp
     file at runtime (same pattern as the interp.dex resource extraction).
   - The init script registers a `hotreloadInspect` task on the ROOT project. Launch it
     with `--init-script <tmp>`, `-Pdev.hotreload.inspect.out=<tmp json path>`, and
     `--no-configuration-cache` (the task walks projects at execution time, which CC
     forbids; the flag is valid on all Gradle versions we target and harmless when CC
     is off). Also pass the configured `projectJavaHome` + `gradleArgs` exactly like
     GradleCompiler does.
   - JSON is written with `groovy.json.JsonOutput` (bundled in Gradle — zero deps).
2. **Engine-side parsing: gson 2.11.0** (`com.google.code.gson:gson:2.11.0`, present in
   the offline Gradle cache; kotlinx-serialization would drag in a compiler plugin).
   Engine `implementation` dependency — it rides into the CLI installDist and the
   IDE-plugin-bundled CLI (283KB, acceptable).
3. **AGP data capture rules** (all bit-tested elsewhere in this repo — follow exactly):
   - Register variant collection inside `plugins.withId('com.android.application')` /
     `'com.android.library'` via `androidComponents.onVariants(selector().all())` at
     CONFIGURATION time (after `afterEvaluate` it's too late); stash collected entries in
     the project's `extraProperties`; the task reads them at execution.
   - `applicationId` is a `Provider<String>` — capture the Provider in onVariants,
     resolve `.get()` at TASK EXECUTION (config-time get() can be too early).
   - `debuggable` exists on application variants (same variant-API boolean T33b's
     tripwire uses); library variants don't have it — omit the field there.
   - Source/res dirs: from the `android` extension's sourceSets, using the SAME name
     tiers as `ModuleSpec.sourceSetNames`: `[main, <flavor>, <buildType>, <variant>]`,
     existing dirs only. (Do NOT use `variant.sources` — AGP-version-sensitive.)
   - Kotlin class-output dirs: at task execution, look up `compile<Variant>Kotlin` in
     the subproject and report its `outputs.files` dirs (tolerate a missing task →
     empty list; dirs may not exist pre-build — report them anyway, this is metadata).
   - `apkOutputDir`: try `variant.artifacts.get(SingleArtifact.APK)` resolved at task
     execution; on ANY exception fall back to the convention path
     `build/outputs/apk/<flavor…>/<buildType>`. Field is documented best-effort.
   - Project dependencies: DECLARED direct deps only —
     `variant.runtimeConfiguration.allDependencies.withType(ProjectDependency)` for
     Android variants, `configurations.runtimeClasspath.allDependencies` for JVM
     modules. No resolution at configuration time (T32 lesson). Transitive closure is
     the consumer's job (all projects are in the report).
4. **Schema v1** — top-level object:
   ```json
   {
     "schemaVersion": 1,
     "gradleVersion": "9.6.1",
     "rootProjectName": "single-module",
     "rootDir": "/abs/path",
     "javaHome": "/abs/path/of/build JVM",
     "projects": [
       {
         "gradlePath": ":app",
         "projectDir": "app",                    // RELATIVE to rootDir ("" for root)
         "type": "androidApp",                   // androidApp | androidLib | kotlinJvm | other
         "pluginIds": ["com.android.application", "dev.hotreload"],
         "variants": [                            // android types only
           {
             "name": "debug",
             "buildType": "debug",
             "flavors": [],
             "debuggable": true,                  // app variants only
             "applicationId": "dev.hotreload.sample",  // app variants only
             "tasks": {
               "compileKotlin": ":app:compileDebugKotlin",
               "assemble": ":app:assembleDebug",
               "install": ":app:installDebug"
             },
             "classOutputDirs": ["app/build/…"],  // relative to rootDir, may be []
             "sourceDirs": ["app/src/main/java", "app/src/main/kotlin"],
             "resDirs": ["app/src/main/res"],
             "apkOutputDir": "app/build/outputs/apk/debug",
             "projectDependencies": [":feature"]
           }
         ],
         "jvm": {                                 // kotlinJvm type only
           "classOutputDirs": ["core/build/classes/kotlin/main"],
           "sourceDirs": ["core/src/main/kotlin"],
           "projectDependencies": []
         }
       }
     ]
   }
   ```
   All paths inside projects are rootDir-relative. Unknown module shapes (KMP,
   dynamic-feature, …) → `"type": "other"`, pluginIds still listed, no variants/jvm.

## Spec

### 1. `engine/src/main/resources/dev/hotreload/inspect.init.gradle` (new)
Per design decisions above. Keep it plain Groovy, no imports beyond what init scripts
resolve by default (`com.android.build.api.*` types must be accessed dynamically /
duck-typed — the init script cannot compile against AGP).

### 2. `engine/src/main/kotlin/dev/hotreload/engine/GradleDiscovery.kt` (new)
- Data classes mirroring schema v1 exactly: `DiscoveryReport`, `DiscoveredProject`,
  `DiscoveredVariant`, `JvmInfo`, `VariantTasks` (gson field names = schema names).
- `GradleDiscovery.run(projectDir: Path, projectJavaHome: Path?, gradleArgs: List<String>): DiscoveryReport`
  — extract init script → temp, run `hotreloadInspect` via Tooling API, parse the output
  JSON with gson, return the model. On Gradle failure: throw with the tail of the Gradle
  stderr in the message (the CLI prints it and exits 1 — no silent empty report).
- `DiscoveryReport.suggestedWatchCommand(): String?` — first `androidApp` project +
  its first debuggable variant (prefer exact name `debug`), modules = app + transitive
  closure of its `projectDependencies` (walk the report), each rendered as
  `:path=dir`; emits a full `hotreload watch --project … --app-id … --module … --variant …`
  line, omitting `--variant` when it is `debug`. Null when no debuggable app exists.

### 3. `cli/Main.kt` — new `inspect` command
- `hotreload inspect --project <dir> [--project-java-home <dir>] [--gradle-arg <a>]… [--json]`
  — NOTE: `--app-id` is NOT required for inspect (unlike watch/doctor). `--json` is a
  valueless flag (strip before `parseOptions`, like `--literals`).
- Default (human) output, one block per project:
  ```
  project root: /abs/path  (Gradle 9.6.1)
  :app  androidApp  dir=app
    debug: debuggable appId=dev.hotreload.sample  → :app:compileDebugKotlin
    release: not debuggable
    deps: (none)
  suggested: hotreload watch --project … --app-id dev.hotreload.sample --module :app=app
  ```
  (exact spacing free; MUST contain the `suggested:` line when non-null).
- `--json`: print the raw report JSON to stdout, nothing else.
- Update USAGE text.

### 4. Tests — `engine/src/test/kotlin/dev/hotreload/engine/GradleDiscoveryTest.kt` (new)
Pure JVM (no Gradle invocation): commit a fixture
`engine/src/test/resources/dev/hotreload/inspect-fixture.json` modeling a 3-module
project (androidApp with 2 variants incl. a non-debuggable release + flavored
`stageDebug`, androidLib, kotlinJvm). Cover at minimum:
- fixture parses; types/variants/tasks/deps land in the right fields;
- absent optional fields (debuggable/applicationId on lib variants, variants on jvm)
  → null/empty, no crash;
- `suggestedWatchCommand()` picks the debuggable variant, includes the transitive
  module closure with `=dir` mappings, omits `--variant` for plain `debug`;
- report with no debuggable app → suggestion null.

## Out of scope
Any change to WatchSession/ModuleSpec/Doctor behavior; `--device`, include/exclude
(phase 3); profiles/`configure` (phase 4); consuming the report to drive builds
(phases 5/6); IDE plugin (phase 9); AGP 8 fixture matrix (phase 10); README (documented
when phase 3 rounds out the surface).

## Acceptance
Run from repo root; all must pass. (Env rule: source env.sh by ABSOLUTE path and
`export REPO_ROOT="$PWD"` first; verify gradlew actually ran by output, not exit code.)
```bash
export REPO_ROOT="$PWD" && source "$REPO_ROOT/scripts/env.sh"
./gradlew :engine:test :cli:installDist

CLI=cli/build/install/cli/bin/cli
# single-module: valid JSON, right app identity, right task names
$CLI inspect --project samples/single-module --json > /tmp/t33c-single.json
python3 -m json.tool /tmp/t33c-single.json > /dev/null && echo JSON-OK
grep -q '"applicationId": *"dev.hotreload.sample"' /tmp/t33c-single.json && echo APPID-OK
grep -q '":app:compileDebugKotlin"' /tmp/t33c-single.json && echo TASK-OK
grep -q '"debuggable": *true' /tmp/t33c-single.json && echo DEBUGGABLE-OK

# multi-module: all three types present, declared dep edges correct
$CLI inspect --project samples/multi-module --json > /tmp/t33c-multi.json
grep -q '"type": *"androidApp"' /tmp/t33c-multi.json && \
grep -q '"type": *"androidLib"' /tmp/t33c-multi.json && \
grep -q '"type": *"kotlinJvm"' /tmp/t33c-multi.json && echo TYPES-OK
python3 -c "
import json; r=json.load(open('/tmp/t33c-multi.json'))
p={x['gradlePath']:x for x in r['projects']}
assert ':feature' in p[':app']['variants'][0]['projectDependencies']
assert ':core' in p[':feature']['variants'][0]['projectDependencies']
print('DEPS-OK')"

# human mode surfaces a usable suggestion
$CLI inspect --project samples/single-module | grep -q '^suggested: hotreload watch' && echo SUGGEST-OK
```
Behavior-freeze checks (nothing else changed):
```bash
git diff --stat main -- engine/src/main/kotlin/dev/hotreload/engine/{WatchSession,ModuleSpec,Doctor,GradleCompiler}.kt | wc -l  # → 0
$CLI watch 2>&1 | grep -q "required" && echo ARGS-OK
```
Device gate (coordinator runs, emulator required): `./e2e/run.sh` + `./e2e/run-multi.sh`
green unmodified — pure-regression confirmation, phase 2 adds no watch-path code.

Flip Status → IN-REVIEW when acceptance passes; coordinator reviews, runs the device
gate, and commits (per tasks/T33-project-agnostic.md agent rules).
