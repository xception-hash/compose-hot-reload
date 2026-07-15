# T33g: prepare/start orchestration + build fingerprints (T33 phase 6)
Status: DONE
Assignee: agy

## Device gate (coordinator, 2026-07-15, emulator-5554) — ALL PASS
Host acceptance re-run independently first: all 8 markers green (no-device paths via a
bogus `--device zzz-no-such-device` serial, emulator attached). Then the full gate:
- `./e2e/run.sh` all cases PASS (271s, literals SKIP as designed locally) +
  `./e2e/run-multi.sh` 4/4 PASS (50s), both UNMODIFIED — no-fingerprint freeze proven on
  a real device.
- **Prepare smoke:** `build: :app:assembleDebug... ok` → `apk: … (output-metadata.json)`
  → `installed:` → `launched:` → `fingerprint: written` → `prepared: … (debug,
  literals=false)`; JSON on disk with all fields (protocolVersion 8, device sha256).
- **Match smoke:** same-config watch → `fingerprint: OK (prepared …)` → watching → leaf
  edit → `hot-swapped: 2 redefined, 4 groups invalidated in 1519ms`.
- **Mismatch smoke (THE incident, closed):** `watch --literals` → `fingerprint: MISMATCH`
  + `literals: recorded 'false' != resolved 'true'`, exit 1, no watch;
  `--ignore-fingerprint` → skip line → watching.
- **Outside-install smoke:** edited `:app:installDebug` over the prepared app → watch →
  `fingerprint: installed APK changed outside 'hotreload prepare' — cannot verify build
  mode…` → still proceeds to watching.
- **Start smoke:** uninstalled multisample → `start` → doctor `[FAIL] App installed…` →
  `start: preparing (app not installed, no build fingerprint)` → full prepare lines →
  `fingerprint: OK` → watching → cross-module core edit → `hot-swapped: 1 redefined,
  whole tree invalidated (no compose keys) in 905ms`.
Cleanup: smoke fingerprints removed, pristine sample reinstalled, apps force-stopped,
watchers pkilled.

## Outcome (host-only; device gate pending coordinator review)
Implemented by the coordinator directly (not delegated to agy, at the maintainer's
request). All host-only work done + verified; the device gate below is left for the
reviewer.

**New files:** `engine/Fingerprint.kt` (Fingerprint + FingerprintStore),
`engine/AppLauncher.kt` (extracted `ensureRunning`), `engine/Prepare.kt`,
`engine/src/test/.../FingerprintTest.kt` (12 tests), `.../FingerprintStoreTest.kt` (4 tests).
**Changed:** `engine/Adb.kt` (+`install`, +`installedApkSha256`), `engine/Doctor.kt`
(`run()` = `runChecked().ok`, structured `Result`, output frozen), `engine/WatchSession.kt`
(one-line delegation to `AppLauncher`), `cli/Main.kt` (prepare/start commands, watch
fingerprint gate, `--ignore-fingerprint`), `README.md` (prepare/start subsection +
`fingerprint: MISMATCH` troubleshooting).

**Host acceptance — all 8 markers printed:** TESTS-OK, USAGE-OK, PREPARE-FAILFAST-OK,
START-DOCTOR-GATE-OK, FREEZE-DOCTOR-OK, FREEZE-WATCH-OK, FLAG-GUARD-OK, E2E-FROZEN-OK.
`:engine:test :protocol:test :cli:compileKotlin :cli:installDist` BUILD SUCCESSFUL.
NOTE: an emulator was attached during verification, so the no-device markers were exercised
via a bogus `--device zzz-no-such-device` serial (same fail-fast/freeze code paths, no live
hang). The canonical no-device run + the full device gate below are for the reviewer.

**Precondition:** T33f (PR #16) merged — this spec builds on its `ApkLocator`,
`ModuleMetadata`/`moduleMetadata` flow, the `.discovery.json` sidecar, and T33e's
`ProfileStore`/shared `resolveConfig` in `cli/Main.kt`. Do not start from a tree
without it.

## Goal
Finish T33 phase 6 (per `tasks/T33-project-agnostic.md` §Phase 6 + §Build and APK
handling):
- `hotreload prepare` — build, install, and launch an instrumented APK for the
  resolved configuration, then record a **build fingerprint**.
- `hotreload start` — doctor → prepare when necessary → watch.
- **Refuse to watch** when the installed APK is positively known not to match the
  resolved configuration (closes the known baseline/dex-mismatch hole: a stale
  APK — e.g. built `-Photreload.liveLiterals=true` while watch runs without
  `--literals` — silently corrupts the Compose slot table on first swap instead
  of failing loud).

REUSE, never re-implement: launch = the T33d `ensureAppRunning` logic (extracted,
see §2); APK selection = T33f `ApkLocator`; build/install task names =
metadata-first via `ProjectConfig.moduleMetadata` with the existing convention
expressions as fallback; config resolution = the shared `resolveConfig`
(profiles + discovery cache flow in unchanged).

The sacred `watch` path stays behaviorally frozen: with no fingerprint file on
disk, `watch`/`doctor` output and behavior are byte-identical to today (that is
the e2e regression contract — e2e installs its own APKs and never runs
`prepare`).

All design decisions below are FIXED — do not improvise alternatives; a genuinely
needed new decision = stop and surface it.

## Design: fingerprint semantics (the load-bearing rules)
A fingerprint is written ONLY by `prepare`. It records (a) the resolved config
fields that shape the installed APK, and (b) the sha256 of the base APK **as
installed on the device** (queried from the device after install — never assume
host-file identity). Validation at watch time distinguishes three knowledge
states:

1. **No fingerprint file** → the feature is unused → completely silent, today's
   behavior verbatim (freeze).
2. **Fingerprint present, device base.apk sha256 == recorded** → we positively
   know what is installed → compare config fields; any mismatch ⇒ **REFUSE**
   (exit 1) with a loud `fingerprint: MISMATCH` naming every differing field and
   the fix (`re-run 'hotreload prepare'`, or `--ignore-fingerprint` to override).
   Match ⇒ `fingerprint: OK (prepared <ISO-8601 preparedAt>)` and proceed.
3. **Fingerprint present, device sha256 differs (or unobtainable)** → the APK was
   replaced outside `prepare` (Android Studio, `installDebug`, e2e) → provenance
   unknown, we cannot validate → print
   `fingerprint: installed APK changed outside 'hotreload prepare' — cannot verify build mode; run 'hotreload prepare' to refresh`
   and **PROCEED** (warn-only). Never refuse on unknown provenance — that would
   break every non-prepare workflow including e2e.

`--ignore-fingerprint` (new valueless flag, `watch`/`start` only): skip
validation, print `fingerprint: check skipped (--ignore-fingerprint)`.

Hard-compared fields (exact equality): `variant`, `modules` (sorted
`gradlePath=relativeDir` strings), `moduleVariants` (sorted `gradlePath=variant`),
`literals`, `gradleArgs` (exact list, order-sensitive — conservative on purpose),
`projectJavaHome` (string or null), `protocolVersion` (satisfies the "runtime
version" requirement — it is the runtime compatibility axis and moves with every
runtime change). Informational only (recorded, never compared):
`compilerFlags` (the pinned set the tool requires — record the constant list
`["-Xlambdas=class","-Xsam-conversions=class","-Xstring-concat=inline"]`),
`hostJavaMajor` (`Runtime.version().feature()` at prepare time), `preparedAt`
(epoch millis), `apkPath` (host path of the APK that was installed),
`projectDir`.

Storage: host-side JSON, one file per (device, app):
```
<baseDir>/fingerprints/<sanitized-serial>_<applicationId>.json
```
where `<baseDir>` is the same root `ProfileStore.default()` uses (honors
`HOTRELOAD_CONFIG_DIR`), and `sanitized-serial` replaces every char outside
`[A-Za-z0-9._-]` with `_`. Device-side storage was rejected (app-data files
survive APK replacement, so they can't witness staleness; the device sha256
query is the binding instead). APK-embedded metadata was rejected (can't touch a
signed APK). Fingerprints are per-device state, NOT profile config — `configure`
and the TOML/sidecar are untouched.

## Spec

### 1. `engine/Fingerprint.kt` (new)
```kotlin
/** Build fingerprint written by `prepare`, validated before `watch`. */
data class Fingerprint(
    val schemaVersion: Int = 1,
    val applicationId: String,
    val deviceSerial: String,
    val projectDir: String,
    val variant: String,
    val modules: List<String>,        // sorted "gradlePath=relativeDir"
    val moduleVariants: List<String>, // sorted "gradlePath=variant"
    val literals: Boolean,
    val gradleArgs: List<String>,
    val projectJavaHome: String?,
    val protocolVersion: Int,
    val compilerFlags: List<String>,
    val hostJavaMajor: Int,
    val apkSha256: String?,           // null = device query failed at prepare
    val apkPath: String,
    val preparedAt: Long,
) {
    companion object {
        val REQUIRED_COMPILER_FLAGS = listOf(
            "-Xlambdas=class", "-Xsam-conversions=class", "-Xstring-concat=inline")
        fun of(config: ProjectConfig, deviceSerial: String, apkSha256: String?,
               apkPath: String): Fingerprint // builds from ProjectConfig + Protocol.VERSION
    }
    /** Non-empty = mismatch; each entry "field: recorded 'x' != resolved 'y'". */
    fun mismatches(config: ProjectConfig): List<String>
}

class FingerprintStore(private val baseDir: Path) {
    companion object { fun default(): FingerprintStore } // same baseDir resolution as ProfileStore.default()
    fun path(serial: String, applicationId: String): Path
    fun save(fp: Fingerprint): Path        // createDirectories, gson toJson, overwrite
    fun load(serial: String, applicationId: String): Fingerprint? // null when absent OR unparsable (unparsable also prints "fingerprint: ignoring unreadable fingerprint file (<reason>)")
}
```
- gson for JSON (already an engine dependency via T33c).
- `mismatches()` compares exactly the hard-compared fields listed above; it does
  NOT compare `apkSha256` (the caller does that against the live device value —
  separate knowledge-state rule).
- `applicationId` in `path()` is already guard-validated by `ProjectConfig`;
  serial goes through the sanitizer. No `..` can survive either.

### 2. `engine/AppLauncher.kt` (new) — extraction, not new behavior
Move the body of `WatchSession.ensureAppRunning(adb, appId)` verbatim into
```kotlin
internal object AppLauncher {
    /** If the app is not running, launch it (am start when launchActivity given, else monkey), poll for pid. */
    fun ensureRunning(adb: Adb, appId: String, launchActivity: String?)
}
```
with `launchActivity` as a parameter instead of reading
`config.project.launchActivity`. `WatchSession.ensureAppRunning` becomes a
one-line delegation (`AppLauncher.ensureRunning(adb, appId, config.project.launchActivity)`).
Every printed line (`launched: …`), the poll loop, and the failure message stay
byte-identical — the existing e2e greps prove it.

### 3. `engine/Adb.kt` — two additive helpers
```kotlin
fun install(apk: Path): Pair<Int, String>   // adb [-s serial] install -r <apk>
fun installedApkSha256(applicationId: String): String?
```
`installedApkSha256`: `pm path <appId>` → take the line ending `base.apk`
(strip the `package:` prefix) → `sha256sum <path>` → first whitespace-separated
token, lowercased; any step failing (non-zero exit, no base.apk line, malformed
output) → null. Never throws.

### 4. `engine/Doctor.kt` — structured result, output frozen
`run(): Boolean` is re-implemented as `runChecked().ok` and every printed line
stays byte-identical (doctor output is asserted by users/scripts). New:
```kotlin
data class Result(
    val ok: Boolean,          // == today's return value
    val envOk: Boolean,       // java + sdk + device sections all passed
    val appInstalled: Boolean,
    val appDebuggable: Boolean,
    val appRunning: Boolean,
    val handshakeOk: Boolean?, // null = not attempted (app not running / earlier failure)
)
fun runChecked(): Result
```
The booleans are set at the exact points the existing checks print; no check is
added, removed, or reordered. Doctor does NOT look at fingerprints (out of
scope — keeps the freeze trivial).

### 5. `engine/Prepare.kt` (new) — the prepare pipeline
```kotlin
object Prepare {
    /** Build → locate APK → install → force-stop → launch → fingerprint. Throws IllegalStateException with a user-facing message on any failure. */
    fun run(config: ProjectConfig, sdk: Path, buildTools: String, store: FingerprintStore = FingerprintStore.default()): Fingerprint
}
```
Sequencing (FIXED — each step fails loud before the next):
1. **Device serial first, before any build** (fail fast): `Adb(adbPath,
   config.deviceSerial)`; explicit serial must appear in `devices()`, else with
   no `--device` exactly one online device is required — reuse Doctor's two
   failure message shapes verbatim (`… not found among online devices …` /
   `expected 1 device, found …`). The resolved serial is used everywhere below.
   adb path = `sdk/platform-tools/adb` (existence-checked like Main.kt does).
2. **Build**: `GradleCompiler(projectDir, gradleArgs + (if literals)
   ["-Photreload.liveLiterals=true"], projectJavaHome)` — the exact expression
   `WatchSession.run()` uses, so prepare builds in the SAME mode watch will run
   (that identity is the whole point). Task = app module's assemble task,
   metadata-first pre-probe (mirror the existing pre-probe compileTask pattern
   in WatchSession):
   `config.moduleMetadata[app.gradlePath]?.assembleTask ?: ":<path>:assemble<Variant.taskSegment()>"`.
   Print `build: <task>... ` then `ok (<ms>)`; failure → the Gradle output in
   the error.
3. **Probe + locate**: `ModuleSpec.probe(projectDir, appRequest, variant,
   moduleMetadata[app])` (classes exist — assemble ran compile), then
   `ApkLocator.locate(appModule.apkOutputDirs, appModule.variant ?: variant,
   applicationId)`. Null → error `no APK found under <dirs> after <task>`.
   Print the existing `apk: <path> (<source>)` line shape.
4. **Install**: `adb.install(apk)`; non-zero exit → error with adb's output.
   Print `installed: <applicationId> on <serial>`.
5. **Force-stop, then launch**: `am force-stop <appId>` (belt-and-braces on top
   of install's process kill — injected/primed classes are immutable per
   process; a fresh process is prepare's contract), then
   `AppLauncher.ensureRunning(adb, appId, config.launchActivity)`.
6. **Fingerprint**: `adb.installedApkSha256(appId)` (null tolerated — recorded
   as null, validation then always lands in the warn-only state 3);
   `store.save(Fingerprint.of(config, serial, sha, apkPath))`. Print
   `fingerprint: written (<path>)` then
   `prepared: <applicationId> on <serial> (<variant>, literals=<bool>)`.

No new output line starts with the bare `watching ` prefix (contract check,
unchanged from T33e/T33f). New prefixes introduced: `build: `, `installed: `,
`fingerprint: `, `prepared: ` — none collide.

### 6. `cli/Main.kt` — commands + the watch gate
- Command list gains `prepare` and `start` (USAGE text updated: one line each,
  mirroring the §Phase-6 wording: prepare = "build, install, and launch an
  instrumented APK"; start = "run doctor, prepare when necessary, then watch").
  Both accept exactly the watch option set (incl. `--profile`, `--device`,
  `--literals`, discovery flags) — they run through the SAME option parsing +
  profile loading + `resolveConfig` + discovery-cache block the shared
  watch/doctor path uses today (restructure `main` minimally so `prepare`/
  `start`/`watch`/`doctor` share it; the T33f freeze markers prove the shared
  path's output didn't move).
- New valueless flag `--ignore-fingerprint` (pull out beside `--literals`),
  honored by `watch` and `start`, rejected for other commands (same shape as
  configure's disallowed-option error).
- **`prepare`**: after config resolution → `Prepare.run(config, sdk, buildTools)`
  → exit 0 (failure: existing `fail()` path, exit 1).
- **`watch`** (and start's watch phase): fingerprint gate runs AFTER d8/adb
  existence checks, BEFORE `WatchSession` construction:
  1. `--ignore-fingerprint` → print skip line, go to WatchSession.
  2. Resolve serial: `config.deviceSerial` or the single entry of
     `Adb(adb, null).devices()` — if zero/multiple devices, SKIP the gate
     silently (WatchSession will fail with today's messages; no new output).
  3. `FingerprintStore.default().load(serial, applicationId)` — absent → silent
     skip (freeze).
  4. Present → `adb.installedApkSha256(appId)`; apply the three knowledge-state
     rules from the Design section (refuse ⇒ `fail(...)` exit 1; warn/OK lines
     as specified).
  `WatchSession` itself is untouched except the §2 delegation.
- **`start`**: after config resolution:
  1. `val result = Doctor(config, sdk, buildTools).runChecked()`.
  2. `!result.envOk` → exit 1 (doctor already printed why; print
     `start: environment checks failed — fix the [FAIL] items above`).
  3. `prepareNeeded` = `!result.appInstalled || result.handshakeOk == false ||`
     fingerprint state ∈ {absent, sha-differs/unobtainable, field-mismatch}
     (compute with the same store/serial logic as the watch gate; `--ignore-fingerprint`
     removes only the fingerprint clauses). Note `handshakeOk == null` (app not
     running) is NOT a trigger — watch auto-launches.
  4. If needed → print `start: preparing (<reasons, comma-joined>)` →
     `Prepare.run(...)`. Prepare's own failures end the run (exit 1); no full
     doctor re-run after prepare (its steps fail loud individually).
  5. Fall through to the watch phase (fingerprint gate now passes by
     construction when prepare just ran).
- Doctor command behavior unchanged (`run()` still the entry).

### 7. Tests (host-only; engine test source set)
- New `FingerprintTest`: `of()` field mapping (incl. sorted modules/
  moduleVariants, `Protocol.VERSION` captured, REQUIRED_COMPILER_FLAGS recorded);
  `mismatches()` empty on identical config; one test per hard-compared field
  proving a single-field change is reported and names the field; informational
  fields (hostJavaMajor, preparedAt, apkSha256) do NOT trigger mismatches;
  gson round-trip through `FingerprintStore.save`/`load` (@TempDir).
- New `FingerprintStoreTest` (@TempDir): save/load round-trip; absent → null;
  unparsable JSON → null (and the loud ignore line); serial sanitization
  (`emulator-5554` kept, `weird/serial:1` → `weird_serial_1`, no path escape —
  assert the file stays under `fingerprints/`).
- `DoctorTest` additions only if a host-only seam exists; otherwise NONE —
  Doctor is device-bound and `runChecked()` is proven by the frozen `run()`
  behavior plus the device gate. Do NOT build an adb mock for this.
- Existing suites pass UNMODIFIED (`ModuleSpecTest`, `ApkLocatorTest`,
  `GradleDiscoveryTest`, `ProfileStoreTest`, `TomlTest`, `ProfileTest`,
  `ClassifierTest`, `WireTest`, …).

### 8. Docs
README: new short "prepare / start" subsection after the watch quickstart:
`hotreload start --project <dir>` as the one-command path (doctor → builds/
installs/launches when needed → watches), `hotreload prepare` as the explicit
build+install+launch step, two sentences on fingerprints (what is recorded, the
refuse-vs-warn rule, `--ignore-fingerprint`). Troubleshooting: add the
`fingerprint: MISMATCH` line with the literals-mode example as the canonical
cause. No quickstart restructure.

## Out of scope
Device-side/protocol changes of any kind (no Capabilities baseline hash — that
is the future full closure for non-prepare installs; protocol stays v8);
validating APKs installed outside `prepare` beyond the warn line; `doctor`
fingerprint reporting; source-staleness detection (watch re-baselines by
design); multi-split/AAB fingerprinting beyond base.apk sha256; fingerprint
GC/expiry; `configure`/profile-TOML/sidecar changes; IDE-plugin consumption
(phase 9); uninstall/downgrade handling in `Adb.install` (plain `install -r`
only); parallel-device prepare.

## Acceptance
Run from repo root. Env per the standing gotcha — source by absolute path, then
export:
```bash
source /ABS/PATH/TO/repo/scripts/env.sh && export REPO_ROOT="$PWD"
./gradlew :engine:test :protocol:test :cli:compileKotlin :cli:installDist && echo TESTS-OK
CLI=cli/build/install/cli/bin/cli
export HOTRELOAD_CONFIG_DIR="$(mktemp -d)"

# usage lists the new commands:
$CLI --help | grep -q "prepare" && $CLI --help | grep -q "start" && echo USAGE-OK

# prepare fails fast on device resolution BEFORE any Gradle build (no device attached):
OUT=$($CLI prepare --project samples/single-module --app-id dev.hotreload.sample 2>&1) || true
echo "$OUT" | grep -Eq "no online devices|not found among online devices|expected 1 device" && \
  ! echo "$OUT" | grep -q "build: " && echo PREPARE-FAILFAST-OK

# start gates on doctor (no device → exit non-zero, prints the gate line, never watches):
OUT=$($CLI start --project samples/single-module --app-id dev.hotreload.sample 2>&1) && echo FAIL-START || true
echo "$OUT" | grep -q "start: environment checks failed" && \
  ! echo "$OUT" | grep -q "^watching" && echo START-DOCTOR-GATE-OK

# behavior freeze — no fingerprint file ⇒ watch/doctor emit ZERO fingerprint output
# (this is the e2e contract; watch fails on no-device exactly as today):
OUT=$($CLI doctor --project samples/single-module --app-id dev.hotreload.sample 2>&1) || true
echo "$OUT" | grep -q "fingerprint:" && echo FAIL-FREEZE-DOCTOR || echo FREEZE-DOCTOR-OK
OUT=$($CLI watch --project samples/single-module --app-id dev.hotreload.sample 2>&1) || true
echo "$OUT" | grep -q "fingerprint:" && echo FAIL-FREEZE-WATCH || echo FREEZE-WATCH-OK

# --ignore-fingerprint is watch/start-only:
OUT=$($CLI doctor --project samples/single-module --ignore-fingerprint 2>&1) || true
echo "$OUT" | grep -q "not allowed" && echo FLAG-GUARD-OK

# e2e scripts untouched (they ARE the regression contract):
git diff --exit-code e2e/ >/dev/null && echo E2E-FROZEN-OK
```
All 8 markers (TESTS-OK, USAGE-OK, PREPARE-FAILFAST-OK, START-DOCTOR-GATE-OK,
FREEZE-DOCTOR-OK, FREEZE-WATCH-OK, FLAG-GUARD-OK, E2E-FROZEN-OK) must print;
capture the output. These are HOST-ONLY — do not touch a device, do not run e2e,
do not start an emulator. If any doctor/watch/prepare invocation hangs printing
nothing against a live device, that is the known PatchServer accept-loop wedge —
leave device work to the coordinator; never "fix" it by editing engine code.

Device gate (coordinator runs, emulator required — NOT the agent):
- **Force-stop both sample apps FIRST** (`adb shell am force-stop …`) — the
  PatchServer wedge AND the T33e priming gotcha (`FAILURE TO REDEFINE …
  '$liveEditBytecode' missing`) both present as confusing smoke failures; never
  trust on-screen text that could be the previous session's swap.
- `./e2e/run.sh` (16 cases) + `./e2e/run-multi.sh` (4 cases) green UNMODIFIED
  (e2e never runs prepare, so the gate also proves the no-fingerprint freeze on
  a real device).
- Prepare smoke: `prepare --project samples/single-module --app-id
  dev.hotreload.sample` → `build:` → `apk: … (output-metadata.json)` →
  `installed:` → `launched:` → `prepared:`; fingerprint JSON exists.
- Match smoke: same-config `watch` → `fingerprint: OK (prepared …)` → ready →
  leaf edit → `hot-swapped:`.
- **Mismatch smoke (THE incident, closed):** same fingerprint, `watch --literals`
  → REFUSES, `fingerprint: MISMATCH` naming `literals`, exit 1, no watch;
  `--ignore-fingerprint` then proceeds with the skip line.
- Outside-install smoke: `./gradlew :app:installDebug` over the prepared app →
  `watch` → the warn line (`changed outside 'hotreload prepare'`) → still
  proceeds to ready.
- Start smoke: uninstall the multi-module sample app → `start --project
  samples/multi-module` → doctor output → `start: preparing (app not
  installed…)` → prepare lines → ready → cross-module edit hot-swaps.

Per the T33 rules: when acceptance passes, flip this file's `Status: TODO` →
`IN-REVIEW` and STOP — the coordinator reviews, runs the device gate, and
commits.
