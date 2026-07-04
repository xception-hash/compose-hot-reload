# T14: On-device experiment — does Compose UI pick up a ResourcesLoader overlay without recreate?
Status: TODO
Assignee: agy + the maintainer on device (failures/surprises are FINDINGS — write them down, change nothing)

## Goal
Resolve the single riskiest unknown from docs/resource-edits-notes.md §5 BEFORE anyone builds
resource-edit support: after adding a `ResourcesLoader` overlay to the live `Resources`, does a
Compose `stringResource(...)` show the new value on (a) plain recomposition, (b) tier-2 reset,
or only on (c) activity recreate? Deliverable: `docs/resourcesloader-compose-experiment.md`
with the observed answer per tier. All app-code edits are TEMPORARY and reverted at the end.

## Spec
Work in `samples/single-module` (revert everything after; emulator via `scripts/emulator-up.sh`).
1. Add a string resource `exp_label` = "ORIGINAL RES" to the sample and render it once via
   `stringResource(R.string.exp_label)` next to the existing text, plus a state-driven counter
   button that forces recomposition of that composable. Build + install + launch.
2. Build the overlay: change `exp_label` to "OVERLAY RES", `./gradlew :app:assembleDebug`,
   unzip the APK, keep ONLY `resources.arsc` + `res/` in a dir. Push that dir to the device
   (app-accessible location, e.g. `/data/local/tmp/exp-overlay` then `run-as` copy into the
   app's `codeCacheDir/exp-overlay` — files must be readable by the app). Then restore the
   string to "ORIGINAL RES" in source (device app still has the original installed).
3. Add a TEMPORARY debug hook in the sample (e.g. a long-press handler or a
   `registerReceiver` broadcast like the Phase-0 spike used) that runs, in-process:
   `ResourcesProvider.loadFromDirectory(overlayDir, null)` → `ResourcesLoader().apply { addProvider(p) }`
   → `activity.resources.addLoaders(loader)` (and also `application.resources.addLoaders(loader)`).
   Log success/exception.
4. Record, in order, what the on-screen text shows after each step (use `scripts/ui-state.sh`):
   a. loader added, NO other action (does existing UI change by itself?)
   b. tap the counter (plain recomposition of the composable that reads the string)
   c. tier-2: HotReloader reset (only if easy via existing runtime-client; else skip, note skipped)
   d. `adb shell am start` with recreate / rotate / `activity.recreate()` — full recreate.
   Also record `new Resources` lookups: log `getString(R.string.exp_label)` at each step —
   distinguishes "Resources returns new value but Compose cached it" from "loader not applied".
5. Write `docs/resourcesloader-compose-experiment.md`: setup commands, the step→observation
   table, and a one-paragraph conclusion ("resource edits need tier X"). Revert ALL sample edits.

## Out of scope
NO changes to engine/, runtime-client/, protocol/, gradle-plugin/. No new opcodes. The temporary
sample hook must not survive the task (`git status` clean at the end).

## Acceptance
1. `test -f docs/resourcesloader-compose-experiment.md` — contains the step→observation table
   with a `getString` column AND an on-screen (`ui-state.sh`) column for steps a/b/d (c optional).
2. Doc states an explicit conclusion: the minimum tier at which "OVERLAY RES" appeared on screen.
3. `git status` clean except the doc + this spec's status line.
