# T39: Diagnose and fix configured-library Compose capture corruption

Status: BLOCKED — the focused batch-D8 regression passes, but the real configured-plugin repeat-edit gate still fails (2026-07-18)
Assignee: GPT-5.6 Sol next session (engine/Compose-patch diagnosis); coordinator reviews all code and runs final device gates
Recommended model: GPT-5.6 Sol
Fallback model: GPT-OSS 120B (Medium), only for mechanical evidence collection or a pre-specified fixture change

## Dispatch

Before dispatching in a future session, run `agy models` and verify GPT-5.6 Sol is available; do
not select an implicit/default model and do not use Claude Opus 4.6. The intended next-session
dispatch is:

```bash
scripts/delegate.sh tasks/T39-configured-library-compose-capture-crash.md "GPT-5.6 Sol"
```

The worker must not publish, push, modify the production target outside the bounded reversible
marker, or accept the result solely from host tests. The coordinator remains responsible for the
diff review and final device/IDE check.

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
  acceptance yet: the local `buildPlugin` package was produced and verified, but has not been
  installed into Android Studio for this milestone. Install that candidate and perform exactly one
  fresh Android Studio Mode B edit/revert/PID/Stop retry before restoring T38 scaffolding.

## Repeat-edit blocker — 2026-07-18

- The plugin ZIP was force-rebuilt after the batch-D8 commit and the engine JAR installed in
  Android Studio was verified byte-for-byte equal to the current rebuilt CLI engine. This is not
  the earlier stale-local-ZIP incident.
- After a fresh matching configured prepare/install, the first visible body-only library edit
  applied. Later body-only saves in the same watcher session did not visibly update, including
  after the user stopped and started the plugin watcher again. Reinstalling the APK/plugin is not
  a valid workaround; it has already been done for this observation.
- During the observed session, device logs recorded successful 12-class redefine batches and
  zero Compose recomposition errors. A captured frame showed the first marker. This establishes
  a distinction the current fixture misses: ART/device acknowledgement is insufficient proof that
  a later source value reaches the rendered composition.
- The emulator was subsequently shut down and all hot-reload/Gradle/Kotlin background daemons were
  removed. A clean API-36 emulator is available for the next controlled reproduction. No watcher
  remains. Do not make more ad-hoc manual edits before instrumentation captures the first and
  second save separately.

## Spec

1. Preserve the failed attempt as evidence. Do not perform another ad-hoc Android Studio marker
   retry before a deterministic host/device regression distinguishes the first and second saves.
2. Establish a minimal configured multi-module reproducer using the existing sample or a new
   bounded fixture. It must contain a Compose library composable whose lazy/list item lambda
   captures both state and an event callback, and it must reproduce a body-only library edit after
   a matching configured install.
3. Extend the deterministic configured reproducer to make two distinct, sequential body edits in
   the same watched library session, then prove that **both** rendered values appear, the callback
   remains type-correct after each, and the PID remains stable. A first-edit-only pass is no
   longer sufficient.
4. Instrument only the engine test/reproducer as needed to capture, for every changed class and
   for each save ordinal: baseline/post-compile class facts, selected module/task, patch class
   list, classifier plan, generated primary/support dex ownership, and the exact device response.
   Do not log application source, package identity, or arbitrary source text in tracked output.
5. Compare the baseline installed class set with the direct library compile output and the patch
   class set. Determine whether the failure arises from incomplete task routing/order, an
   inconsistent compiler output set, lambda/support-class omission, D8 desugaring ownership, or
   Compose invalidation/interpreter routing.
6. Implement the smallest fix that preserves one debounced Gradle invocation per Kotlin batch,
   selected-variant module tasks, Gradle-managed dependency ordering, existing app-module behavior,
   one-definition-per-redefined-class DEX, desugaring injection, debuggable-only guards, and the
   existing rebuild-needed handling.
7. Add focused coverage proving each sequential library Kotlin output change, both patch hot-swaps,
   both visible UI values, type-correct captured state/callback after each, and stable PID. Do not
   treat an app compile as evidence that the library compiled.
8. Run the relevant engine/CLI/plugin gates and Plugin Verifier. The sample device gate may run
   only after its AGP line is compatible with the retained smoke scaffold; do not alter the
   scaffold merely to make that gate pass.
9. Rebuild and reinstall the local candidate. Then perform exactly one fresh matching configured
   Mode B Android Studio retry: Ready -> first library marker edit -> visible value A -> second
   distinct marker edit -> visible value B -> restore the baseline -> Ready -> stable PID ->
   Stop/Off. Capture the device/log signal after each leg.
10. Only if that retry passes, perform T38's surgical target/scaffold restoration, matching
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

The focused configured multi-module regression must prove two sequential library output changes
and successful visible swaps of the capture-heavy composable in one watcher session. Then execute
the exact T38 Mode B manual gate once with the candidate rebuilt from this checkout. Require both
distinct visible edits plus restoration, stable PID, Stop/Off, no capture/type exception, and no
leaked watcher. Only then use T38's recorded restoration and baseline-comparison commands.
