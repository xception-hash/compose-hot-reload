# T12: Extra e2e cases — multi-file save + rapid successive saves
Status: TODO
Assignee: agy

## Goal
Extend `e2e/run.sh` (5 cases, T06) with two stress cases that mirror real IDE behavior. These
exercise `SourceWatcher`'s 150ms debounce/batching — if either FAILS, that is a real engine
finding: STOP, write the failure symptoms into this file under a "Findings" heading, and leave
the engine alone (fix is Claude's job).

## Spec
Follow the existing case pattern in `e2e/run.sh` (same helpers, same emulator/app lifecycle,
same `pkill -f dev.hotreload.cli.MainKt` cleanup discipline — see T06 notes; never kill only the
Gradle wrapper). Add:

- **Case 6 — multi-file batch save:** edit TWO different source files of `samples/single-module`
  (both body-only edits, e.g. two different visible strings), writing both files within the 150ms
  debounce window (write file A, write file B immediately after — plain sequential `sed`/writes are
  fine, they land well under 150ms). Expect ONE compile/apply batch and BOTH new strings visible
  via `scripts/ui-state.sh`. Same-PID check as existing cases.
- **Case 7 — rapid successive saves:** two saves to the SAME file ~1s apart (second save lands
  while/just after the first batch is compiling). Expect: session stays healthy, final UI shows the
  SECOND edit's text, same PID, and a subsequent normal edit (reuse case-1 style) still applies.

Keep total added runtime ≤ ~40s. Restore all sample sources at the end (existing harness pattern).

## Out of scope
- No engine/runtime-client/protocol changes (see Goal: failures are documented, not fixed).
- No changes to existing cases 1–5. No CI workflow changes.

## Acceptance
Run from repo root (emulator up via `scripts/emulator-up.sh`):
1. `e2e/run.sh` passes 7/7, TWICE back-to-back.
2. `pgrep -f dev.hotreload.cli.MainKt` empty after each run.
3. `git status` clean except `e2e/run.sh` (+ this spec's status line updated).
