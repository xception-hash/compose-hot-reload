# T39: Diagnose and fix configured-library Compose capture corruption

Status: IN PROGRESS — deterministic configured regression and batch-D8 fix landed locally 2026-07-18; fresh Mode B retry remains
Assignee: maintainer / coordinator (engine and Compose-patch semantics stay with the coordinator)
Recommended model: Gemini 3.1 Pro (Low), only for a decision-complete fixture or evidence-collection subtask
Fallback model: GPT-OSS 120B (Medium), only for the same bounded non-core subtask

## Dispatch

Do not dispatch the core diagnosis or patch design. If the coordinator first extracts a mechanical,
decision-complete fixture-only subtask, it may be delegated with:

```bash
scripts/delegate.sh tasks/T39-configured-library-compose-capture-crash.md "Gemini 3.1 Pro (Low)"
```

Do not use Claude Opus 4.6; its quota remains unavailable. Re-run `agy models` immediately before
any future dispatch.

## Goal

Make a configured-mode body edit in a watched Compose Android library hot-swap without corrupting
captured Compose values. The failed bounded Android Studio attempt recompiled the library and sent
a patch, then Compose caught a `ClassCastException` that treated a state object as a function value
inside a lazy-grid item lambda. A watcher-stopped clean uninstall/reinstall did not inject any
patch and was healthy, so this is a patch-path regression rather than a baseline APK failure.

## Known evidence

- The prior app-only compile omission is fixed locally: a Kotlin batch now routes to each owning
  watched module's selected-variant Kotlin task in one Gradle invocation.
- The bounded configured target prepared, installed, and reached Ready with the rebuilt local
  candidate. Its installed APK was known to match the configured output and is JaCoCo-free.
- The single permitted Mode B retry reached the library patch path: three desugaring support dexes
  were injected and twelve classes redefined before Compose reported the capture cast failure.
- The plugin was stopped. A subsequent clean uninstall/install/launch with no watcher produced a
  healthy process and no redefine/injection activity.
- The temporary target Gradle wiring and the two local runtime-client composite compatibility edits
  remain required test scaffolding. Do not remove or broaden them while diagnosing this task.

## Progress 2026-07-18

- Added `e2e/fixtures/configured-capture` and `e2e/run-configured-capture.sh`: an AGP-9.0
  configured app/library fixture compatible with the retained local runtime-client scaffold. Its
  library composable uses a lazy item lambda that captures both state and a separately-created
  event callback.
- The device regression passes: it verifies the watched library's built-in Kotlin class output
  changes, waits for a successful hot-swap, observes the edited UI, invokes the callback after
  the swap to prove its captured state remains type-correct, and requires the original PID.
- Patch D8 now receives all redefine/inject primary classes in one invocation. This preserves one
  primary definition per redefined class while generating each desugaring support DEX from the
  complete coupled patch set; focused host coverage asserts that ownership contract.
- Required engine/protocol/CLI distribution and plugin verifier gates pass. This is not T39
  acceptance yet: rebuild/install the local candidate and perform exactly one fresh Android Studio
  Mode B edit/revert/PID/Stop retry before restoring T38 scaffolding.

## Spec

1. Preserve the failed attempt as evidence. Do not perform another ad-hoc Android Studio marker
   retry before a deterministic host/device regression exists.
2. Establish a minimal configured multi-module reproducer using the existing sample or a new
   bounded fixture. It must contain a Compose library composable whose lazy/list item lambda
   captures both state and an event callback, and it must reproduce a body-only library edit after
   a matching configured install.
3. Instrument only the engine test/reproducer as needed to capture, for every changed class:
   baseline and post-compile class facts, selected module/task, patch class list, classifier plan,
   and generated primary/support dex ownership. Do not log application source, package identity,
   or arbitrary source text in tracked output.
4. Compare the baseline installed class set with the direct library compile output and the patch
   class set. Determine whether the failure arises from incomplete task routing/order, an
   inconsistent compiler output set, lambda/support-class omission, D8 desugaring ownership, or
   Compose invalidation/interpreter routing.
5. Implement the smallest fix that preserves one debounced Gradle invocation per Kotlin batch,
   selected-variant module tasks, Gradle-managed dependency ordering, existing app-module behavior,
   one-definition-per-redefined-class DEX, desugaring injection, debuggable-only guards, and the
   existing rebuild-needed handling.
6. Add focused coverage proving the library Kotlin output changes, the patch hot-swaps, the
   captured state/callback remains type-correct, the UI visibly changes, and the PID remains stable.
   Do not treat an app compile as evidence that the library compiled.
7. Run the relevant engine/CLI/plugin gates and Plugin Verifier. The sample device gate may run
   only after its AGP line is compatible with the retained smoke scaffold; do not alter the
   scaffold merely to make that gate pass.
8. Rebuild and reinstall the local candidate. Then perform exactly one fresh matching configured
   Mode B Android Studio retry: Ready -> library marker edit -> Ready with visible change -> revert
   -> Ready -> stable PID -> Stop/Off.
9. Only if that retry passes, perform T38's surgical target/scaffold restoration, matching
   zero-touch preparation, and sanitized documentation close-out. Publishing and pushing remain
   separately unauthorized.

## Out of scope

- Publishing the candidate, pushing any branch/tag, or opening a PR.
- Removing the temporary T38 wiring or runtime-client compatibility scaffold before the successful
  retry.
- Retrying the full T37 matrix, changing the device floor, bypassing fingerprints, or using a
  temporary target clone.
- Suppressing the exception, automatically falling back to a full reinstall, or weakening class
  shape/desugaring/security validation instead of fixing the patch path.

## Acceptance

From the repository root, after the mandated preflight:

```bash
unset JAVA_HOME; source scripts/env.sh
"$JAVA_HOME/bin/java" -version
ls "$ANDROID_HOME/build-tools/36.0.0/d8" "$ANDROID_HOME/build-tools/36.0.0/dexdump"
adb devices
adb shell getprop ro.build.version.sdk
./gradlew :engine:test :protocol:test :cli:compileKotlin :cli:installDist :cli:verifyZeroTouchDistribution
(cd intellij-plugin && unset JAVA_HOME && source ../scripts/env.sh && ./gradlew test buildPlugin verifyPlugin)
```

The focused configured multi-module regression must prove a library output change and a successful
hot swap of the capture-heavy composable. Then execute the exact T38 Mode B manual gate once, with
the candidate rebuilt from this checkout. Require visible edit and revert, stable PID, Stop/Off,
no capture/type exception, and no leaked watcher. Only then use T38's recorded restoration and
baseline-comparison commands.
