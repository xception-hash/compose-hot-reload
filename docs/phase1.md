# Phase 1 — MVP vertical slice: status & operational notes

**Exit gate MET 2026-07-04** on `samples/single-module` (API 36 arm64 emulator):
composable body edit → visible on device in **~1.1s** (1160ms / 1117ms over two runs),
same PID, `remember` + `rememberSaveable` preserved, via `hotreload watch`.

## Running it
```bash
source scripts/env.sh   # JAVA_HOME etc.
./gradlew -q :cli:run --args="watch --project $PWD/samples/single-module --app-id dev.hotreload.sample"
# then edit a composable body in the sample and save
```
Prereqs: emulator up (`scripts/emulator-up.sh`), sample installed & running
(`(cd samples/single-module && ./gradlew :app:installDebug)`, launch it). Device state
checks: `PKG=dev.hotreload.sample scripts/ui-state.sh`, `scripts/taps.sh`.

## Architecture as built
- `protocol/` — dependency-free framed binary protocol; sources compiled into BOTH the
  JVM engine and the AAR (srcDir sharing, no publishing).
- `runtime-client/` — standalone Android build (composite-included by samples) producing
  the AAR: JVMTI agent .so (grown from the spike agent — **AOSP port not needed, decision
  final**), ComposeBridge reflection, PatchServer, androidx.startup init. Host app needs
  only `debugImplementation` + `jniLibs.useLegacyPackaging = true`.
- `engine/` — WatchSession: save → Tooling-API incremental compile → ClassSnapshot diff →
  FactsExtractor/Classifier → d8 per class → inject/redefine (atomic batch) → targeted
  `invalidateGroupsWithKey`. `cli/` — arg parsing only.

## Post-Phase-0 findings (gotchas discovered in Phase 1)
- **TCP bind = EPERM without the INTERNET permission**, even loopback. Transport is an
  abstract-namespace Unix socket `hotreload-<applicationId>` + `adb forward tcp:0
  localabstract:<name>` (AOSP deployer approach). Never force a manifest permission.
- **`Method.invoke` on a void method returns null** — never use `?.invoke() ?: fallback`
  to detect a missing method (ComposeBridge bug, fixed).
- **`DirectoryWatcher.watchAsync()` swallows failures** in its unobserved future; a
  nonexistent watch root kills all events silently. Roots are existence-filtered and the
  future is observed now.
- Classifier includes structural/inject verdicts (proven paths), so Phase 2's breadth is
  mostly orchestration + e2e coverage, not new mechanisms.

## Remaining Phase 1 items (Opus/agy-friendly)
- `gradle-plugin` (T05, not yet specced): inject runtime-client into debug builds, set
  compiler flags, so users don't hand-edit their app like the sample does.
- README quickstart; e2e harness (`e2e/`) automating the gate test above.
