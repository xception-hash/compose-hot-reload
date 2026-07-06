# T30: Robustness leftovers — deferred interpreter/resource edge cases
Status: TODO
Assignee: opus (device items) + agy (doc items)

## Goal
Close the small deferred items from T23/T27. Each item is INDEPENDENTLY droppable — do them in
order, stop anytime; none blocks v0.1 (T29). Every item has a pointer into an authoritative doc;
do not re-research.

## Spec

### 1 (opus + device) — interpreter_jni super/synchronized spike checkpoint
The 12 JNI natives in `runtime-client/lib/src/main/cpp/interpreter_jni.cpp` (T27 step 1) were
never exercised for `invokespecial` super-calls (`super.toString()`) or
`enter/exitMonitor` (synchronized blocks) — removal/add edits don't hit them. Extend the spike
(pattern: `spike/interpreter/`, v2 target gains a `super.toString()` call + a `synchronized`
block) and assert both in logcat. If green: one line in research doc §5. If red: capture the
exact failure here, fix in the .cpp (unbox flags are byte-identical to AndroidEval.java — most
likely bug class is a wrong `CallNonvirtual*` variant).

### 2 (opus, decision + possibly one line) — callee-exception routing
`docs/phase6-interpreter-research.md` §5 first bullet: exceptions thrown by COMPILED callees
skip interpreted try/catch (sneaky-rethrow in `doInterpret`). Candidate one-line-class fix in
our interp copy: try `exceptionCaught(...)` (real `instanceOf`) before the sneaky rethrow.
Decide: apply + rebuild interp.dex (then re-run spike asserting `parseX` now CAUGHT, and update
that assert!) or document as permanent limitation in README. Weigh: divergence from AOSP vs UX.

### 3 (agy) — `ImageBitmap.imageResource` note
T23 covers `painterResource` bitmaps only. Direct `ImageBitmap.imageResource()` call sites have
no targetable recomposition group (`docs/resource-edits-v1.md` §Open questions, T23 v3). Add a
README limitation line + a one-para note in resource-edits-v1.md on the candidate mechanism
(tier-2 reset on such edits — needs a call-site scan, out of scope here).

### 4 (opus + device, cheap) — `.jpg` / nine-patch / fonts
The T23 engine path triggers on png/webp batches (`Invalidate([painter key])` after
LoadResources). Try: (a) `.jpg` drawable edit — likely just extend the extension allowlist in
the engine's resource batch classifier; (b) nine-patch (`.9.png`) — compiled format, may need
aapt2 in the overlay build, if hard: document as unsupported; (c) fonts — different Compose
cache (no painter group), expect NOT to work: document. Each: one live check on the sample app,
then README matrix row.

## Out of scope
T28 (own task), new resource types beyond the three listed, non-emulator devices.

## Acceptance
Per item (any subset may ship):
1. `spike/interpreter/run.sh` prints PASS including the two new asserts.
2. Either the fix commit (+ spike green with flipped assert) or a README limitation line —
   plus a dated decision note in research §5.
3. README + resource-edits-v1.md updated (doc-only).
4. For each format: live check done + README matrix row says supported/unsupported.
`./e2e/run.sh` green after any engine/interp change (items 2, 4a).
