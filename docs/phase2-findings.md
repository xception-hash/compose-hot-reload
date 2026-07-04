# Phase 2 findings — structural/inject through the real watch loop + error escalation

Live-validated 2026-07-04 on `samples/single-module`, API 36 arm64 emulator, protocol v2.
All swaps same-PID. This extends the Phase 1 body-edit-only gate (docs/phase1.md).

## What now works end-to-end via `hotreload watch`
| Case | Mechanics | Time |
|---|---|---|
| structural add (new `@Composable` + call site, one save) | 1 injected (new lambda class), 6 redefined structural, 7 groups invalidated | ~3.2s |
| new file / new class | 2 injected, no invalidation | ~1.0s |
| call site into injected class | 6 redefined body-only (correctly NOT structural) | ~2.4s |
| broken edit (`error()` in a body) | detected + reported with source location; UI keeps last-good frame | ~1.2s |
| fix-and-save after broken edit | full UI recovers in place, no reinstall | ~1.2s |

## Recomposition-error detection (the Phase 2 "escalation" item)
Compose (with `setHotReloadEnabled(true)`) **captures** recomposition exceptions,
restores the last-good frame and keeps running — a broken swap is otherwise invisible
("Error was captured in composition while live edit was enabled", tag `ComposeInternal`).
- Protocol v2 adds `GetErrors(clear)` → `ComposeErrors(list<message, recoverable>)`;
  bridged via `Recomposer.Companion.getCurrentErrors()/clearErrors()` reflection.
- Engine verifies 300ms after every Invalidate (recomposition runs a frame later than
  the Invalidate ack) and prints the captured error + "fix the edit and save again".
- **Auto tier-2 reset on error was implemented, tested, and removed**: a deterministic
  error fails again during the reset's recompose, which leaves a BLANK screen (the
  save/dispose/load destroyed the last-good frame) and wipes remember state — strictly
  worse than doing nothing. Fix-and-save recovers in place from either state. Tier-2
  stays in the protocol for future policies.

## State-preservation semantics (matches Android Studio Live Edit)
`invalidateGroupsWithKey` discards remember AND rememberSaveable state in the **edited
function's subtree** (the runtime must re-run initializers that may capture new code).
Editing a leaf preserves everything else (Phase 1 gate); editing a parent like
`MainScreen` resets its children's counters to 0. Not a bug; document it in the README.

## Bugs found & fixed (would have bitten any non-trivial edit)
1. **Silent save-drop**: `SourceWatcher.flush` ran `onSave` inside a `ScheduledFuture`;
   any exception was captured unobserved — the save vanished, session looked healthy.
   Now caught + stack-traced + session continues. (Symptom: "changed: X" then nothing.)
2. **`$` in inject names**: new lambda/nested classes (`MainActivityKt$Badge$1`) failed
   the device's `[A-Za-z0-9._-]+` filename check. Engine sanitizes `$` → `_` (label only).
3. **String templates → invokedynamic**: Kotlin 2.4 (JVM target ≥9) compiles `"$a $b"`
   to `StringConcatFactory.makeConcatWithConstants`; AGP's D8 desugars it in the APK,
   but our patch pipeline is `d8 --no-desugaring` → ART hidden-api-blocks the link →
   `NoSuchMethodError` **inside recomposition** of any redefined body with a template.
   Fix: `-Xstring-concat=inline` next to `-Xlambdas=class`/`-Xsam-conversions=class`
   (sample + T05 plugin spec). Any other invokedynamic source would hit the same wall.

## Operational gotchas
- Multi-part edits (new function + call site) must land in ONE save: the watcher builds
  on every save, and a call to a not-yet-written function is a compile error (recovered,
  but noisy). IDE users save atomically; scripted edits must write once.
- The engine JVM's `:cli:run` compiles at launch — engine changes need a watch restart;
  runtime-client/protocol changes need `installDebug` + app relaunch (protocol version
  in the Ping output confirms which client is on device).
