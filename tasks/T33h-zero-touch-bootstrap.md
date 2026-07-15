# T33h: Zero-touch bootstrap / init-script mode
Status: DONE (2026-07-15)
Assignee: coordinator

## Goal

Add explicit `--zero-touch` support so Compose Hot Reload can discover, instrument,
build, install, and watch an Android/Compose project without changing its settings or
module build files. Configured mode remains the default and retains its existing output
and behavior.

## Public contract

- `IntegrationMode` is `CONFIGURED` (default) or `ZERO_TOUCH` on `ProjectConfig`.
- `--zero-touch` is an explicit boolean flag accepted by `inspect`, `configure`,
  `doctor`, `prepare`, `watch`, and `start`.
- Profiles persist zero-touch mode as `zero-touch = true`; absence/false means
  configured. Profile schema remains 1 (the key is additive).
- `config show` includes `--zero-touch` in its expanded command.
- Fingerprints hard-compare the integration mode and the SHA-256 of the bundled
  runtime AAR in zero-touch mode. Configured fingerprints use no bundled-runtime hash.
  Legacy fingerprint JSON with no integration mode is interpreted as configured.
- Changing integration mode requires a matching `prepare`; the existing positive-match
  fingerprint gate refuses a stale installed APK.

## Bundled artifacts

Add a Java-only `:bootstrap` subproject:

- Java source only, compiled with `--release 17` (class major 61).
- Depends only on the Gradle API at compile time; no AGP or Kotlin dependency/import.
- Uses reflection and Gradle plugin callbacks for AGP/Kotlin integration.

The engine JAR embeds, as generated resources:

```text
dev/hotreload/bootstrap/bootstrap.jar
dev/hotreload/bootstrap/runtime-client.aar
dev/hotreload/bootstrap/zero-touch.init.gradle
```

The resource task depends on the bootstrap JAR and a fresh
`:runtime-client:assembleRelease` from the included runtime-client build. The existing
CLI install distribution and IntelliJ plugin copy the engine JAR, so they inherit the
payloads. Distribution verification opens the nested engine JAR and asserts all three
entries.

The runtime AAR declares `minSdk 24` and `minCompileSdk 30`, contains the initializer,
classes, and both supported JNI ABIs. `androidx.startup:startup-runtime:1.2.0` is added
separately through the target's normal dependency repositories because a local file AAR
has no POM/transitive dependencies.

## Generated init script and internal properties

Artifacts are extracted to a fresh private host temp directory, never under the target
project. Paths are normalized, contained by that directory, regular files, and validated
as ZIPs before use. The temp directory lives for the complete Tooling API connection and
is removed afterward.

The fixed bundled Groovy init script loads the bootstrap implementation class and applies
it only to the exact watched Gradle-path allowlist. It reads these tool-owned properties:

```text
dev.hotreload.bootstrap.jar
dev.hotreload.bootstrap.runtimeAar
dev.hotreload.bootstrap.modules
dev.hotreload.bootstrap.appModule
dev.hotreload.bootstrap.variant
```

The entire `dev.hotreload.bootstrap.` prefix is reserved. User/profile Gradle arguments
must reject every Gradle spelling before any Gradle call: joined/separate `-P`, joined/
separate `--project-prop`, `-Dorg.gradle.project.*`, and joined/separate `--system-prop`
forms. Near-miss names outside the prefix remain valid. Internal arguments are built
separately after validation; user input is never interpolated into Groovy source.

## Bootstrap behavior

The bootstrap mirrors configured `HotReloadPlugin` behavior:

- App module: FunctionKeyMeta flag, the three deterministic class-shape flags, optional
  live-literals v2 flag, and legacy JNI packaging.
- Android library and Kotlin/JVM watched modules: the three deterministic class-shape
  flags only.
- The local runtime AAR and Startup dependency are added only to debuggable build-type
  implementation configurations on the selected app module.
- The selected application variant is checked through reflected AGP variant metadata and
  rejected if non-debuggable. Do not infer debuggability from its name.
- Bootstrap application occurs before target plugins. Register callbacks for AGP 8
  standalone `org.jetbrains.kotlin.android`, AGP 9 built-in Kotlin, Android library, and
  Kotlin/JVM ordering; compiler flags apply exactly once and missing/unsupported APIs fail
  loudly with the module and operation.
- No runtime dependency is added to non-debuggable classpaths or non-app modules.

## Shared Gradle invocation

One engine-owned invocation builder supplies user Gradle args, live-literals mode, and
zero-touch init-script/internal args to every target build:

- discovery;
- prepare/assemble;
- watch initial and incremental compilation;
- resource assembly (through the same warm `GradleCompiler`).

Discovery is deliberately two-stage in zero-touch mode: the first inspection is
uninstrumented and resolves the exact watched-module allowlist; a second inspection uses
the bootstrap init script and refreshes metadata. This avoids applying the bootstrap to
unselected projects. Explicit configured-mode discovery remains one-stage and byte-
identical.

## Doctor and immutability

Configured Doctor output and text checks remain unchanged. Zero-touch Doctor validates
the target Gradle root plus bundled artifacts, skips `dev.hotreload` build-file scans, and
reports the bootstrap mode. Reinstall guidance in zero-touch mode says to rerun
`hotreload prepare --zero-touch`; a raw target `install<Variant>` omits the init script.

Fixture gates snapshot tracked target files before and after inspect/prepare/watch. Normal
Gradle `.gradle`, `.kotlin`, and `build` outputs are excluded; settings, build scripts,
source, `local.properties`, init scripts, and metadata inside the target must not change.

## Tests and fixtures

- Unit tests: config/profile/fingerprint migration and mismatches; reserved-property
  parsing; artifact extraction/validation/hash; bootstrap argument allowlist; Doctor
  configured freeze and zero-touch branch.
- Bootstrap integration tests: AGP 9.2.1 built-in Kotlin and AGP 8.12.3 standalone
  Kotlin 2.3.20; single/multi-module, nonstandard physical dirs, Kotlin/JVM dependency,
  custom debuggable variant, debug-only runtime and Startup resolution, compiler flags,
  and target-tree immutability.
- Packaging tests: bootstrap class major 61 and no AGP/Kotlin linkage; engine/CLI nested
  payload assertions; IntelliJ bundled-CLI assertion.
- Device: existing configured `e2e/run.sh` and `run-multi.sh` unchanged, then zero-touch
  prepare/watch/hot-swap with a stable PID and preserved state. Live-literals gets a
  matching zero-touch prepare before its watcher.

AGP 8/JDK 17 fixture execution requires an explicit `JAVA17_HOME`; it must not silently
fall back to JDK 21. A missing device blocks only the device gates.

## Documentation

- README Quickstart leads with `hotreload start --zero-touch --project <dir>` as the
  no-build-file-edit path and keeps configured mode as a separate path.
- Rename the existing "zero-config" wording to "automatic discovery"; it is independent
  of zero-touch integration.
- Document profiles, Startup repository resolution, `--literals`, fingerprints, ordinary
  Gradle outputs, and the configured-only AGP 8 mavenLocal workaround.
- Update `AGENTS.md` rebuild guidance, the T33 phase table, and `docs/PLAN.md` only after
  acceptance passes.

## Security/privacy gates

- Validate all property names, module paths, selected variant, extracted paths, ZIP
  entries, and init-script arguments at both the engine and bootstrap boundary.
- Preserve all existing runtime debuggable guards and prove release classpaths clean.
- Never write generated bootstrap inputs into the target tree.
- Before every commit, run the maintainer confidentiality greps over staged files and the
  staged diff; both must be empty.

## Acceptance

Host core:

```bash
./gradlew :bootstrap:test :engine:test :protocol:test :cli:installDist :cli:verifyZeroTouchDistribution
```

Configured regressions:

```bash
(cd samples/single-module && ./gradlew --offline :app:assembleDebug :app:assembleQa)
(cd samples/single-module && ./gradlew --offline -q :app:dependencies --configuration qaRuntimeClasspath | grep -q runtime-client)
(cd samples/multi-module && ./gradlew --offline :app:assembleDebug)
```

Fixture/device gates are implemented as `e2e/run-zero-touch.sh` and the unchanged:

```bash
./e2e/run.sh
./e2e/run-multi.sh
./e2e/run-zero-touch.sh
```

Only the coordinator flips this task and T33 phase 8 to DONE, commits the reviewed
integration, and opens the PR after all available acceptance gates pass. If JDK 17 or a
device is unavailable, record those gates explicitly as pending rather than weakening
them.

## Outcome (2026-07-15)

Host implementation and review are complete. The core suite, CLI and IntelliJ nested-
payload checks, IntelliJ Plugin Verifier against all three pinned IDE lines, configured-
mode sample regressions, and the AGP 9/JDK 21 zero-touch host fixture all pass. The host
fixture covers two-stage discovery, a custom debuggable variant, compiler/live-literal
flags, Startup/runtime packaging, a clean release build, rejection of a selected
non-debuggable variant, and target-tree immutability.

The following acceptance gates remain pending, so this task and T33 phase 8 are not DONE:

- AGP 8.12.3 standalone Kotlin compilation on JDK 17: no JDK 17 is installed. Its
  Gradle 8.13 configuration/discovery path was exercised successfully on the available
  JBR, but the fixture deliberately refuses to substitute JDK 21 for this gate.
- Device acceptance: no device/emulator is attached, so the unchanged configured-mode
  `e2e/run.sh` / `e2e/run-multi.sh` and zero-touch prepare/watch/hot-swap gates have not
  run in this review cycle.

### Session update (2026-07-15)

JDK 17 and a headless API 36 emulator are now available. The offline zero-touch host
fixture and both configured-mode device regressions passed. The zero-touch device gate
reached its `watching`, `hot-swapped`, and `literal-pushed` contracts, but state reset
from `Count: 2` to `Count: 0` after a keyless whole-tree swap. A focused engine correction
now skips the redundant compile/swap after a successful literal push and advances the
literal baseline; `:engine:test --tests dev.hotreload.engine.LiteralFastPathTest` passes.

T33h remains **IN-REVIEW**. Rebuild `:cli:installDist`, then rerun the offline host and
zero-touch device gates before any closeout work. No roadmap status, commit, push, or PR
was changed in this session.

### Continuation update (2026-07-15)

Preflight passed with JBR 21, JDK 17, Build Tools 36.0.0, and the single API 36 emulator.
After rebuilding `:cli:installDist`, the offline zero-touch host fixture passed again.
The device fixture then reached `watching`, the compiled `hot-swapped` contract, and
`literal-pushed` without the former redundant keyless whole-tree swap. It nevertheless
failed the state assertion: the literal showed `v2`, but `Count: 2` became `Count: 0`.

The remaining defect is now narrowly located in
`runtime-client/lib/src/main/kotlin/dev/hotreload/client/ComposeBridge.kt`.
`updateLiteral()` uses `invalidateAllCompositions()` (state-preserving) followed by
`invalidateGroupsWithKey(enclosingKey)` to wake an idle Recomposer. The keyed invalidation
resets the enclosing composable's `remember` state. Replacing it experimentally with
`pumpFrame()` preserved `Count: 2`, but the literal remained `v1`; the idle Recomposer was
not woken. That experiment was reverted, and the CLI distribution rebuilt to match the
preserved source tree.

The next implementation must make the first live-literal update both visible and
state-preserving. Relevant logs are `/tmp/t33h-zero-touch-device-retry.log` (v2 / Count 0)
and `/tmp/t33h-zero-touch-device-final.log` (v1 / Count 2 experiment); each failed via
`assert_ui` with exit 1. The existing `WatchSession.kt`, `e2e/run-zero-touch.sh`, and AGP 8
fixture changes were reviewed and preserved. T33h remains **IN-REVIEW**; no roadmap status,
commit, push, or PR was changed.

### GPT-5.6 Tera handoff update (2026-07-15)

The live-literal/state defect is fixed in the current dirty worktree and the complete
zero-touch gate passes. The final mechanism is intentionally split by change type:

- `WatchSession` treats a successful literal push as the completed save, advances the
  text baseline, and skips the redundant compile/swap that previously reset state.
- `ComposeBridge.updateLiteral()` writes the Compose compiler v2 helper's mutable static
  backing field named by the literal key while keeping the helper's `enabled` branch
  false. This avoids switching an already-rendered composition to a `State` it never
  observed.
- Keyless code swaps call `forceRecomposeAllCompositions()`: reflect
  `CompositionImpl.composer`, invoke mangled `forceRecomposeScopes`, then synchronously
  call `setContent` with the existing content lambda. This disables skipping but retains
  the slot table and therefore `remember` state.
- Resource overlays deliberately keep the older `invalidateAllCompositions()` path.
  Using forced `setContent` for resources made the overlay attach successfully while the
  old string remained visible. `PatchServer` now routes keyless compiled/interpreted code
  to the forced path and resources to ordinary invalidation.

The investigation rejected three partial fixes, all live-reproduced: keyed invalidation
showed `v2` but reset `Count: 2` to `Count: 0`; a frame pump and Recomposer enqueue kept
`Count: 2` but left `v1`; forced recomposition for every whole-tree request fixed code but
broke resource refresh. Do not restore any of these combined paths.

Passing evidence from the final source shape:

- `(cd runtime-client && ./gradlew :runtime-client:testDebugUnitTest)` — PASS. The new
  `ComposeBridgeTest` pins backing-field mutation, the disabled helper branch, and final-
  field rejection.
- `./gradlew :cli:installDist` — PASS with a freshly rebuilt runtime AAR.
- `ZERO_TOUCH_OFFLINE=1 JAVA17_HOME=<JDK17> ./e2e/run-zero-touch.sh` — PASS, including
  AGP 9 compiled/literal/resource edits with stable PID and `Count: 2`, plus the AGP 8
  JDK 17 multi-module keyless JVM hot swap.
- Earlier in the same continuation,
  `./gradlew :bootstrap:test :engine:test :protocol:test :cli:installDist
  :cli:verifyZeroTouchDistribution` — PASS.

Still pending because the owner stopped the session:

- The configured sample regressions were started in parallel and the polling turn was
  interrupted; no build process remains, but their results were not accepted. Rerun all
  three commands from this task's Acceptance section.
- Rerun the unchanged configured device gates `./e2e/run.sh` and
  `./e2e/run-multi.sh` after the ComposeBridge/PatchServer changes.
- Review the full diff and run the prescribed local confidentiality greps. Only then may
  the coordinator set this task and T33 phase 8 to DONE. No status, commit, push, or PR
  was changed.

### Closeout (2026-07-15)

The pending configured gates were rerun and passed: both single-module debug/qa assembly
and runtime-client resolution, plus multi-module debug assembly. `./e2e/run.sh` completed
all exercised cases successfully (the opt-in live-literals case correctly skipped without
`HOTRELOAD_LITERALS=1`), and `./e2e/run-multi.sh` passed 4/4. The full dirty diff was
reviewed; `git diff --check` and the maintainer confidentiality greps over the worktree and
staged diff were empty. This task and T33 phase 8 are DONE; no commit, push, or PR was made.
