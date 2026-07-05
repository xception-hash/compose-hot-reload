# T22: Multi-activity overlay re-attach + activity-vs-application Resources isolation
Status: DONE
Assignee: agy (device needed — run interactively)
Priority: 3 of T21–T27 (after T21 — reuses its persistent loader)
Depends on: T21 (the session loader object is what gets re-attached)

## Goal
Two gaps recorded in `docs/resource-edits-v1.md` §Open questions:
(2c) overlays attach at `LoadResources` time to the *then-resumed* activity + application, so an
activity opened AFTER an edit may render pre-edit resources; (2d) we attach to BOTH the
activity's and application's `Resources` without knowing which is strictly required. Fix (2c),
answer (2d) with an isolation experiment, document both.

## Spec
1. `runtime-client/.../ActivityTracker.kt`: in `onActivityResumed`, if a session loader exists
   (T21 singleton) and this activity's `resources` is not yet attached (T21's WeakHashMap),
   `addLoaders(loader)` before the frame renders. Keep the tracker free of any Compose calls —
   attach only; recomposition is unnecessary (a newly-started activity composes fresh).
2. Testbed: `samples/single-module` gains `SecondActivity` (registered in the manifest,
   exported=false) showing `ResourceLabel()` + a `BackHandler`-free plain layout; MainActivity
   gains a `Button("Open second")` with `contentDescription = "OPEN_SECOND"` launching it.
   Keep MainActivity's existing composables and test tags untouched (e2e cases 1–12 must not
   notice).
3. e2e case `multi-activity-resource` in `e2e/run.sh` (number = next free): baseline label →
   perl-edit `strings.xml` → wait `resource-swapped:` → tap OPEN_SECOND (uiautomator bounds,
   like run-multi does) → assert SecondActivity shows the NEW value (this fails without step 1)
   → press back → PID unchanged → restore.
4. Isolation experiment (2d), manual, results into `docs/resource-edits-v1.md` §Open questions
   (replace the open item with findings): temporarily attach the loader to (a) only the
   activity Resources, (b) only the application Resources; for each, test a string edit on
   MainActivity + on SecondActivity + after `adb shell am kill`-free rotation
   (`adb shell settings put system accelerometer_rotation 0; adb shell wm size` not needed —
   just `adb shell am start` the activity again). Record which attachments are required for:
   same-activity update, later-activity correctness, recreate survival. Ship the minimal
   correct set as the final code (if both are required, say so and keep both).

## Out of scope
- engine/, protocol/, cli/ — no changes.
- Multi-module sample (single-activity there stays).
- Configuration-change stress (T14 already covered recreate-with-loader-on-application).

## Acceptance
From repo root, emulator booted:
1. `./e2e/run.sh` → all cases PASS (incl. the new one) twice back-to-back; pgrep clean;
   samples restored byte-identical apart from the committed SecondActivity testbed.
2. Manual proof of (2c) fix: watch session → edit `hot_label` → open SecondActivity → new
   value visible without any further edit; PID unchanged.
3. `docs/resource-edits-v1.md`: 2c/2d open-question entries replaced by findings + what shipped.
