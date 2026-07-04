# T06: e2e harness automating the change-matrix gate tests
Status: TODO
Assignee: agy

## Goal
Automate the manual gate tests we run after every engine change (PLAN.md "Verification").
One command boots everything and asserts the proven change matrix against a live emulator,
so regressions in the watch loop are caught without a human watching the screen.

## Spec
Create `e2e/run.sh` (bash, `set -euo pipefail`, sources `scripts/env.sh`). Reuse existing
scripts (`scripts/emulator-up.sh`, `scripts/ui-state.sh`) — do not duplicate their logic.
Flow:
1. `scripts/emulator-up.sh`; install + launch the sample fresh
   (`cd samples/single-module && ./gradlew :app:installDebug`, `am force-stop` first);
   record PID.
2. Copy `samples/single-module/app/src/main/kotlin/dev/hotreload/sample/MainActivity.kt`
   to a backup; ALWAYS restore it (and delete any added file) in a `trap ... EXIT`.
3. Start `./gradlew -q :cli:run --args="watch --project <repo>/samples/single-module
   --app-id dev.hotreload.sample"` in the background, logging to a temp file; wait for
   the line `watching ` (timeout 120s). Kill it in the same EXIT trap.
4. Run the cases below in order. After each edit: wait for a new `hot-swapped:` line in
   the watch log (timeout 60s), then poll `ui-state.sh`-style
   (`adb exec-out uiautomator dump /dev/tty`) up to 10s for the expected assertion.
   Any failure → print the watch log tail + dump, exit 1.

Cases (each builds on the previous state of the file; these exact edits were validated
live on 2026-07-04 — see docs/phase2-findings.md):
- **body-edit**: change `Text("Hello from the sample app")` to
  `Text("EDITED BODY")`. Assert: text appears, PID unchanged.
- **state-preserved (leaf edit)**: tap the button 2× first (`scripts/taps.sh 2`),
  re-edit the same Greeting string to `"EDITED BODY 2"`. Assert: new text, PID
  unchanged, AND `Count: 2 / Saved: 2` still shown (leaf edits must not reset
  sibling state; only the edited composable's subtree resets).
- **structural-add**: append a new `@Composable fun Badge() { Text("STRUCTURAL ADD OK") }`
  and add `Badge()` inside `MainScreen` — ONE write (the watch log must show
  `(structural)` and `injected`). Assert: text appears, PID unchanged.
- **new-class**: create `Widgets.kt` (new `@Composable fun NewFileWidget() {
  Text("NEW CLASS INJECT OK") }`), wait for its `hot-swapped:` (injected, 0 groups),
  then add `NewFileWidget()` to `MainScreen`. Assert: text appears, PID unchanged.
- **error-recovery**: change Greeting body to `error("e2e crash")`. Assert the watch
  log shows `recomposition failed:` and the UI STILL shows the previous frame (PID
  unchanged, no crash). Then restore Greeting; assert the UI recovers fully.

Print a per-case PASS line and a total wall-time summary.

## Out of scope
- CI wiring (GitHub Actions) — separate task.
- Any change to engine/, runtime-client/, protocol/, samples/ source (the harness only
  edits the sample AT RUNTIME and must restore it byte-identical on exit).
- Multi-module, resource edits, live literals (later phases).

## Acceptance
From repo root: `e2e/run.sh` exits 0 with all cases PASS on the pinned AVD, and
`git status --porcelain` is empty afterwards (sample restored). Run it twice in a row —
the second run must also pass (no leaked device/app state).
