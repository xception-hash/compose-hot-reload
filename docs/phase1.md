# Phase 1 — MVP vertical slice: status & operational notes

> [!NOTE]
> This is a historical implementation record from July 2026, not a setup guide. The commands and
> manual runtime wiring used during Phase 1 predate profiles, CLI-owned preparation, and the
> released Gradle/IDE plugins. Use the root
> [Stable Quickstart](../README.md#4-stable-quickstart-configured-gradle-plugin) or the
> [AI setup guide](ai-project-setup.md) for current onboarding.

**Exit gate MET 2026-07-04** on `samples/single-module` (API 36 arm64 emulator):
composable body edit → visible on device in **~1.1s** (1160ms / 1117ms over two runs),
same PID, `remember` + `rememberSaveable` preserved, via `hotreload watch`.

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

## Items that remained at the time

- `gradle-plugin` (T05, not yet specced): inject runtime-client into debug builds, set
  compiler flags, so users don't hand-edit their app like the sample does.
- README quickstart; e2e harness (`e2e/`) automating the gate test above.
