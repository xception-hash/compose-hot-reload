# T33d: --device, discovery-defaulted modules, include/exclude, --launch-activity (T33 phase 3)
Status: TODO
Assignee: agy

## Goal
Finish T33 phase 3: stop assuming the app module is `:app` under `app/`, that only one
device is attached, and that the app is already running. Four additions to the CLI/engine:

1. `--device <serial>` — target a specific device when several are online.
2. Zero-config module/appId resolution: when `--module`/`--app-id` are omitted,
   `watch`/`doctor` resolve the app module, variant, applicationId, and watched-module
   closure through the T33c Gradle discovery (`GradleDiscovery`/`inspect.init.gradle`).
3. `--include-module` / `--exclude-module` — filter the discovered watched set.
4. `--launch-activity <name>` + auto-launch — if the app process is not running when
   `watch` starts, launch it instead of dying on the handshake.

All design decisions below are FIXED — do not improvise alternatives; a genuinely needed
new decision = stop and surface it.

## Spec

### 1. `ProjectConfig` — two new fields + input validation
```kotlin
val deviceSerial: String? = null,     // --device; null = adb's default-device behavior
val launchActivity: String? = null,   // --launch-activity; null = monkey LAUNCHER fallback
```
`init` additions (both strings reach the DEVICE shell via `adb shell` — adb concatenates
its args into one shell command on-device, so these are injection guards, not cosmetics):
- `require(applicationId.matches(Regex("^[A-Za-z0-9._]+$")))` — message
  `"applicationId contains characters outside [A-Za-z0-9._]: <value>"`. (Valid Android
  application IDs can't contain anything else, so this breaks no legitimate caller;
  applicationId already flows into `pidof`/`pm path`/`run-as` today.)
- `launchActivity?.let { require(it.matches(Regex("^[A-Za-z0-9._$]+$"))) ... }` — same
  message shape. Accepts `.MainActivity`, `com.example.MainActivity`, `Outer$Inner`.
- `deviceSerial` is deliberately NOT validated: it is only ever passed as a discrete
  `ProcessBuilder` argument to `adb -s` on the host (no shell), and real-world serials
  contain vendor-specific characters.

### 2. `Adb` — serial-aware
Constructor becomes `class Adb(private val adb: Path, serial: String? = null)`. Add
`internal val commandPrefix: List<String> = listOf(adb.toString()) + (serial?.let { listOf("-s", it) } ?: emptyList())`
and build EVERY ProcessBuilder in the class from `commandPrefix + args` — EXCEPT
`devices()`, which is a global adb command and must stay serial-less
(`listOf(adb.toString(), "devices")`). No other behavior change.

### 3. `WatchSession` — serial + auto-launch
- `Adb(config.adb)` → `Adb(config.adb, config.project.deviceSerial)`.
- New private `ensureAppRunning(adb: Adb)`, called in `run()` AFTER constructing `adb`
  and BEFORE `adb.forward(...)`:
  1. `adb.safeShell("pidof", appId)` — non-empty numeric pid → return silently
     (app already running: print NOTHING, keep e2e logs identical).
  2. Not running → launch via `safeShell` (NOT `shell` — `am start` exit codes and
     output are unreliable; success is judged only by the pidof poll below):
     - `launchActivity != null` → `am start -n <appId>/<launchActivity>`
     - else → `monkey -p <appId> -c android.intent.category.LAUNCHER 1`
  3. Poll `pidof` every 250 ms, up to 40 attempts (10 s). On success print exactly
     `launched: <appId> (pid <pid>)`. On timeout throw
     `IllegalStateException("app <appId> is not running and could not be launched" +
     " — start it on the device, or pass --launch-activity <activity>")`.

### 4. `Doctor` — serial-aware device check
- Construct `Adb(adbPath, config.deviceSerial)` (the `-s` then applies to every
  per-device check: getprop, pm path, run-as, pidof, forward).
- Device-selection logic in check (c):
  - `deviceSerial == null`: unchanged (exactly one online device required), but extend
    the multi-device FAIL fix text with `or pass --device <serial>`.
  - `deviceSerial != null`: it must appear in `devices()`; otherwise
    `fail("Device connected: '<serial>' not found among online devices (<list>)")`.
    Multiple devices online is then OK.
- Reword the not-running warn (check f) to:
  `"Runtime handshake: app not running — hotreload watch will auto-launch it; start it manually and re-run doctor for the handshake check"`.
Doctor stays read-only: it never launches anything.

### 5. `GradleDiscovery.kt` — reusable watch-plan resolution
Refactor the BFS inside `suggestedWatchCommand()` into shared logic and add:
```kotlin
data class WatchPlan(
    val variantName: String,
    val applicationId: String?,               // null when the report lacks it
    val modules: List<ModuleSpec.Request>,    // app module FIRST, then closure order (variant = null)
)

fun DiscoveryReport.resolveWatchPlan(
    appModulePath: String?,        // --app-module (normalized via ModuleSpec.Request.parse)
    variantName: String?,          // --variant, null = pick automatically
    includeModules: List<String>,  // normalized gradle paths
    excludeModules: List<String>,
): WatchPlan
```
Rules (every failure is `IllegalArgumentException` with the quoted message shape —
the CLI converts to `fail(...)`):
- **App project:** `appModulePath` given → the discovered project with that gradlePath;
  must exist (`"--app-module '<p>' not found in the project"`) and have
  `type == "androidApp"` (`"--app-module '<p>' is <type>, not an Android application module"`).
  Not given → exactly one `androidApp` in the report; zero →
  `"no Android application module found — pass --module and --app-id explicitly"`;
  more than one → `"multiple application modules found (<paths>) — pass --app-module"`.
- **Variant (within the app project's debuggable variants only):** `variantName` given →
  exact match required, else `"variant '<v>' not found or not debuggable — debuggable variants: <names>"`.
  Not given → variant named `debug` if present; else the SOLE debuggable variant; else
  `"multiple debuggable variants (<names>) — pass --variant"`. No debuggable variants at
  all → `"no debuggable variants in <appPath>"`.
- **Closure:** same BFS as today (declared project dependencies, android types walk their
  first variant's deps, kotlinJvm walks jvm deps), yielding
  `ModuleSpec.Request(gradlePath, relativeDir = discovered projectDir, variant = null)`
  per module, app first. Discovery reports rootDir-relative dirs already.
- **Filters (applied to the closure, app module exempt):**
  - `excludeModules`: each must be in the closure
    (`"--exclude-module '<p>' is not a watched module (watched: <paths>)"`); excluding
    the app path → `"cannot exclude the app module '<p>'"`. Matching entries removed.
  - `includeModules`: when non-empty, keep app + exactly the listed paths; each must be
    in the closure (same not-a-watched-module message shape with `--include-module`).
  - Both given: exclude applies after include (a path in both → error via exclude's
    membership check failing after include kept it — acceptable; do not special-case).
- `suggestedWatchCommand()` is rewritten ON TOP of the shared closure/variant helpers but
  its observable output must not change — the existing GradleDiscoveryTest assertions
  (incl. picking the FIRST androidApp when several exist, returning null instead of
  throwing) pass UNMODIFIED.
- Add `fun DiscoveryReport.watchCommandFor(plan: WatchPlan, projectDir: String): String`
  producing the same format as `suggestedWatchCommand` (`--module` specs as
  `path=dir` comma-joined; `--variant` appended only when != `debug`) — used for the
  `resolved:` line below.

### 6. `cli/Main.kt` — flags, trigger rule, resolution
New options (USAGE updated; keep alphabetical-ish grouping with the existing ones):
```text
  --device <serial>   Target device serial when several are connected (adb -s;
                      overrides $ANDROID_SERIAL)
  --launch-activity <name>
                      Activity to launch when the app is not running (default: the
                      device's LAUNCHER intent for --app-id)
  --include-module <gradlePath>
                      Restrict the DISCOVERED watched modules to these (+ the app
                      module); may be repeated. Only valid without --module
  --exclude-module <gradlePath>
                      Drop a DISCOVERED watched module; may be repeated. Only valid
                      without --module
```
Also update the synopsis: `hotreload watch --project <dir> [options]` with a line noting
app module, variant, and appId are discovered when `--app-id`/`--module` are omitted.

**Trigger rule (exact):** discovery runs iff `--module` is ABSENT and
(`--app-id` is absent OR any `--include-module`/`--exclude-module` is given).
- `--module` present + any include/exclude →
  `fail("--include-module/--exclude-module filter the discovered module set; with --module, edit the list directly")`.
- `--module` present (or discovery not triggered) → the existing path, byte-identical:
  `--app-id` required, default module list `app`, `applyVariantOverrides`,
  `selectAppModule`. This keeps `e2e/run.sh` (passes `--app-id`) and `run-multi.sh`
  (passes `--module`) entirely OFF the discovery path.

**Discovery path:** call `GradleDiscovery.run(project, projectJavaHome, gradleArgs)`
(same args as `inspect`), then `report.resolveWatchPlan(appModuleOpt, variantOpt,
includes, excludes)` with include/exclude values normalized through
`ModuleSpec.Request.parse(...).gradlePath`. Then:
- `applicationId` = `--app-id` if given, else `plan.applicationId`, else
  `fail("discovery found no applicationId for variant '<v>' — pass --app-id")`.
- `variant` = `plan.variantName`; `modules` = `plan.modules` →
  `applyVariantOverrides(--module-variant)` → `ProjectConfig.selectAppModule(modules,
  null, --app-module-dir)` (app is already first; only the dir override applies).
- Print exactly (before Doctor/WatchSession run; `<app>` = plan's first module):
  ```
  discovered: app=<gradlePath> dir=<relativeDir> variant=<variantName> appId=<applicationId>
  discovered: watching <gradlePath, gradlePath, ...>
  resolved: <watchCommandFor(plan, projectDir)>
  ```
  The `resolved:` line is the reproducibility contract: pasting it must yield the same
  session without discovery.
- Discovery/resolution failures → `fail(...)` with the underlying message (never fall
  back silently to the `app` default — that would re-hide the errors this phase exists
  to surface).
`--device` and `--launch-activity` parse as single-value options into the new
ProjectConfig fields in BOTH modes.

### 7. Tests (host-only, all in existing files unless noted)
- `GradleDiscoveryTest` — new cases for `resolveWatchPlan` (reuse
  `engine/src/test/resources/dev/hotreload/inspect-fixture.json`; add sibling fixtures
  like `inspect-fixture-multiapp.json` where needed):
  1. defaults on the fixture → app first, closure order, `debug`, fixture appId;
  2. explicit variant picked; unknown/non-debuggable variant → IAE listing debuggables;
  3. no `debug` + sole debuggable → picked; multiple debuggables + no `--variant` → IAE;
  4. two androidApp projects → IAE listing paths; with `appModulePath` → that app;
     `appModulePath` naming a library → IAE;
  5. exclude removes a module; exclude app → IAE; exclude unknown → IAE;
  6. include whitelists (app kept implicitly); include unknown → IAE;
  7. ALL existing `suggestedWatchCommand` tests pass unmodified.
- `ProjectConfigTest` — `applicationId` with a shell metacharacter (`foo;id`) rejected;
  normal ids accepted; `launchActivity` `.MainActivity` / `Outer$Inner` accepted,
  `foo bar` rejected; defaults (`deviceSerial`/`launchActivity` null) accepted.
- New `AdbTest` — `commandPrefix` is `[adb]` without serial and `[adb, -s, serial]`
  with one. (Process-spawning behavior is covered by the device gate, not unit tests.)

### 8. Docs
- README options table: add the four new flags + one sentence introducing the
  zero-config shape (`hotreload watch --project <dir>` discovers the rest). Do NOT
  restructure the quickstart.

## Out of scope
External profiles + `configure` (phase 4), output-dir/APK metadata replacing ModuleSpec
probing (phase 5), `prepare`/`start` (phase 6), per-dependency selected-variant
resolution (phase 5 — dep modules keep default/`--module-variant` variants), IDE plugin
(phase 9), interactive selection, any protocol/device-side code, `inspect` output.

## Acceptance
Run from repo root. Env per the standing gotcha — source by absolute path, then export:
```bash
source /ABS/PATH/TO/repo/scripts/env.sh && export REPO_ROOT="$PWD"
./gradlew :engine:test :protocol:test :cli:compileKotlin :cli:installDist
CLI=cli/build/install/cli/bin/cli

# Legacy mode is untouched and never discovers:
$CLI watch --project samples/single-module --module app 2>&1 | grep -q -- "--app-id is required" && echo LEGACY-REQ-OK
OUT=$($CLI doctor --project samples/single-module --app-id dev.hotreload.sample 2>&1) || true
echo "$OUT" | grep -q "discovered:" && echo FAIL-LEGACY-DISCOVERED || echo LEGACY-OK

# Discovery mode (doctor may FAIL on device checks without an emulator — grep lines, ignore exit):
OUT=$($CLI doctor --project samples/single-module 2>&1) || true
echo "$OUT" | grep -q "discovered: app=:app dir=app variant=debug appId=dev.hotreload.sample" && echo DISCOVER-OK
echo "$OUT" | grep -Eq "resolved: hotreload watch --project .* --app-id dev.hotreload.sample --module :app=app" && echo RESOLVED-OK
OUT=$($CLI doctor --project samples/multi-module 2>&1) || true
echo "$OUT" | grep -q "discovered: watching :app, :feature, :core" && echo DISCOVER-MULTI-OK
OUT=$($CLI doctor --project samples/multi-module --exclude-module :core 2>&1) || true
echo "$OUT" | grep -q "discovered: watching :app, :feature$" && echo EXCLUDE-OK

# Guards:
$CLI doctor --project samples/multi-module --exclude-module :app 2>&1 | grep -q "cannot exclude the app module" && echo EXCLUDE-GUARD-OK
$CLI watch --project samples/multi-module --module app --include-module :core 2>&1 | grep -q "edit the list directly" && echo FILTER-GUARD-OK
```
All 8 markers (LEGACY-REQ-OK, LEGACY-OK, DISCOVER-OK, RESOLVED-OK, DISCOVER-MULTI-OK,
EXCLUDE-OK, EXCLUDE-GUARD-OK, FILTER-GUARD-OK) must print; capture the output.

Device gate (coordinator runs, emulator required):
- `./e2e/run.sh` (16 cases) + `./e2e/run-multi.sh` (4 cases) green UNMODIFIED — legacy
  path behavior-identical.
- Zero-config live: `cli watch --project samples/multi-module` (no --app-id/--module)
  reaches `watching`, a cross-module edit hot-swaps.
- Auto-launch: force-stop the sample app, start watch → `launched: dev.hotreload.sample
  (pid N)` → ready; repeat with `--launch-activity .MainActivity`.
- `--device emulator-5554` doctor + watch smoke (and doctor FAIL message on a bogus
  serial).

Per the T33 rules: when acceptance passes, flip this file's `Status: TODO` →
`IN-REVIEW` and STOP — the coordinator reviews, runs the device gate, and commits.
