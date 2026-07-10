# T30: Robustness leftovers — deferred interpreter/resource edge cases
Status: DONE (2026-07-10 — all 4 items closed; see per-item Outcome notes. Headline finding:
`synchronized` blocks in interpreted bodies SIGABRT under CheckJNI → new classifier
MONITORENTER→Rebuild guard in `Classifier.plan()` + spike asserts the documented abort.)
Assignee: Fable (decisions + classifier guard) + Sonnet subagents (spike/device/doc mechanics)

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

**Outcome (2026-07-10, device emulator-5554, FINAL): spike GREEN (run.sh exit 0, 2/2 runs) with
the SYNC assert flipped to assert the DOCUMENTED CheckJNI abort — super.toString() verified
live; `synchronized` in interpreted bodies is documented-fatal; engine guard landed
(FactsExtractor records MONITORENTER methods, `Classifier.plan()` demotes such interpret/support
batches to Rebuild; host tests green — coordinator). `run.sh` now asserts, in order: `SUPER
PASS`, aggregate `SPIKE PASS` (both logged durably before the fatal case, which runs last), then
the abort signature `Still holding a locked object on JNI end` in logcat →
`== synchronized-in-interpreted-body FATAL (documented, T30) PASS ==` (same philosophy as the
`parseX=escaped:NumberFormatException` assert). Research doc §5 has the dated entry covering
both findings. Investigation record follows.**

Harness first: the spike originally couldn't reach the natives at all (first pass failed with
`UnsatisfiedLinkError: No implementation found for ... JNI.invokespecialL` — they are bound only
by an explicit `RegisterNatives`, and standalone `spike/toy-app` had neither
`interpreter_jni.cpp` in its `.so` nor any registration entry point). Closed per coordinator
go-ahead, with `interpreter_jni.cpp` unmodified: toy `CMakeLists.txt` now compiles it verbatim
from its runtime-client path (no copy → no drift), new `spike/toy-app/app/src/main/cpp/
interp_bridge.cpp` forwards the toy-mangled symbol
`Java_dev_hotreload_toy_HotSwap_nativeRegisterInterpreterJni` to the file's real export
`Java_dev_hotreload_client_HotSwap_nativeRegisterInterpreterJni` (signature read from source:
`(JNIEnv*, jobject, jclass) -> jint`; both symbols confirmed in the built arm64 `.so` via
llvm-nm), toy `HotSwap.kt` gained the matching `external fun`, and `SpikeDriver` now registers
the natives once — after interp.dex inject, before any interpreted call — mirroring production
`LiveEditInterp.ensureInitialized` ordering. Registration works: logcat tag `HotReloadInterp`
prints `registered interpreter.JNI natives`.

Results with natives reachable (run.sh, fresh toy install, pid 9639):
- All pre-existing asserts PASS: `SPIKE PASS nativeFib=55 interpFib=1055 greet=v2:Hello, ART!
  parse42=int:42 parseX=escaped:NumberFormatException`.
- `invokespecial super.toString()` assert **PASS**: `super-call:
  nativeToString=dev.hotreload.spike.SpikeTarget@2596f94
  interpToString=v2super:dev.hotreload.spike.SpikeTarget@2596f94 -> SUPER PASS` — interpreted v2
  `toString()` dispatched `super.toString()` through `invokespecialL` to the compiled
  `Object.toString()` on the same instance (identity hash matches the native call). No
  `CallNonvirtual*`-variant bug.
- `synchronized` (monitorenter/exit) assert **FAIL — process SIGABRT**, deterministic (pids
  9464, 9578, 9639). The instant `JNI.enterMonitor` returns from its first `env->MonitorEnter`,
  ART kills the app: `signal 6 (SIGABRT)`, `Abort message: 'JNI DETECTED ERROR IN APPLICATION:
  Still holding a locked object on JNI end: <0x008bca3d> (a java.lang.Object)'`, backtrace
  `art::JNIEnvExt::CheckNoHeldMonitors()` ← `artQuickGenericJniEndTrampoline`. The interpreted
  `synchronized:` verdict line never prints; `exitMonitor` is never reached.
- Why: CheckJNI is late-enabled for EVERY debuggable app (confirmed in this process's logcat:
  `Late-enabling -Xcheck:jni`), and its end-of-native-method check forbids returning from a JNI
  native while holding a monitor acquired in that native. AOSP's `enterMonitor` design does
  exactly that by construction (acquire, then return; release happens in a later `exitMonitor`
  call). So this is not a wrong-variant port bug in our `interpreter_jni.cpp` (which is
  byte-faithful and, per instruction, untouched) — the upstream jni_dispatch design itself
  cannot survive CheckJNI, and hot reload only ever runs on debuggable apps. Consequence:
  `synchronized` blocks inside INTERPRETED method bodies are fatal, not merely unsupported —
  now guarded engine-side (MONITORENTER → Rebuild demotion in `Classifier.plan()`, above).
  `synchronized` METHODS are unaffected: ART holds their monitor on the real frame, no JNI
  round-trip. If the FATAL assert ever flips (no abort), the platform changed — revisit the
  classifier guard and research doc §5.
- `SpikeDriver` logs every verdict durably BEFORE attempting the fatal monitor case (which runs
  last, behind an "attempting … expected FATAL" marker that deliberately avoids the grep
  signature substring), so all verdicts survive the SIGABRT.
- Files changed: `spike/toy-app/app/src/main/cpp/CMakeLists.txt`,
  `spike/toy-app/app/src/main/cpp/interp_bridge.cpp` (new),
  `spike/toy-app/app/src/main/kotlin/dev/hotreload/toy/HotSwap.kt`,
  `spike/interpreter/target-v2/SpikeTarget.java`,
  `spike/interpreter/driver/dev/hotreload/spike/SpikeTarget.java`,
  `spike/interpreter/driver/dev/hotreload/spike/SpikeDriver.java`, `spike/interpreter/run.sh`.
  `interpreter_jni.cpp`, engine/, runtime-client Kotlin, e2e/: untouched.

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

**Outcome (2026-07-10, device emulator-5554):**
- **(a) `.jpg`/`.jpeg`: SUPPORTED.** One-line extension-allowlist extension, exactly as
  predicted: `SourceWatcher`'s watch filter, `WatchSession.pendingBitmapSwap`'s trigger check,
  and `WatchSession.isResourceFile`'s guard scan now share a `BITMAP_EXTENSIONS =
  setOf("png","webp","jpg","jpeg")` set (`WatchSession.kt` companion object); doc comments in
  `SourceWatcher.kt`, `WatchSession.kt`, `ResourceSwapper.kt`, `PainterKeyExtractor.kt` updated
  to match. No overlay-pipeline change needed — `assembleDebug` + whole-`res/`-tree extraction
  already ships any extension, and the bashed painter group key is format-agnostic. Live: added
  a temp `hot_jpg.jpg` drawable + `Image(painterResource(...))` call, blue→red edit while
  watching produced `bitmap-invalidated: painterResource remember groups bashed
  (key=-1771643000)` then `resource-swapped: ...`, on-device pixel `#2196F3` → `#FE0000`
  (JPEG lossy rounding of pure red, expected). Guard regression check (same session): a save
  that removed a private helper method AND added a `synchronized` block in the same class
  printed exactly `cannot hot-swap: dev.hotreload.sample.MainActivityKt: synchronized block in
  locked()I — interpreted monitorenter aborts under CheckJNI`, app PID unchanged (no SIGABRT) —
  the T30 item 1 MONITORENTER guard holds under the new allowlist.
- **(b) Nine-patch (`.9.png`): NOT SUPPORTED — root cause is Compose itself, not our
  pipeline.** `Path.extension` of `foo.9.png` was already `png`, so nothing needed to change
  for the file to reach the swapper, and a hand-built valid 9-patch source compiled fine via
  `assembleDebug`/aapt2 (reinstall succeeded). The break is one layer up: even on a **fresh
  install with no hot reload involved**, `painterResource(R.drawable.<ninepatch>)` throws
  `ClassCastException: android.graphics.drawable.NinePatchDrawable cannot be cast to
  android.graphics.drawable.BitmapDrawable` inside
  `androidx.compose.ui.res.ImageResources_androidKt.imageResource` (logcat tag
  `ComposeInternal`, full stack captured live) — Compose's bitmap-branch decoder hard-casts to
  `BitmapDrawable`; aapt2's compiled 9-patch output is a sibling `NinePatchDrawable`, not a
  subtype. No engine change attempted or needed (correctly matches the spec's "don't sink time
  into aapt2" guidance, though for a different reason than anticipated — aapt2 was never the
  blocker).
- **(c) Fonts (`res/font/*.ttf`): NOT SUPPORTED, confirmed — stricter than the "ignored"
  message.** `SourceWatcher`'s `DirectoryWatcher` listener gates on extension
  (`kt`/`xml`/`png`/`webp`/`jpg`/`jpeg`) *before* the changed path is added to the debounced
  batch, so a font create/write event never reaches `onBatch` — no `changed:` line and no
  `ignored (...)` line print at all, not even the generic ignored-message path. Verified live:
  appended a byte to a watched `res/font/*.ttf`, zero watch-log output, app PID unchanged. A
  full reinstall is required for font changes.
- README.md feature matrix + limitations updated (jpg/jpeg rows extended, nine-patch and font
  limitation bullets added); `docs/resource-edits-v1.md` Open questions has the full dated
  entry. `./e2e/run.sh`: **14/14 PASS** (1 expected SKIP: live-literals, needs
  `HOTRELOAD_LITERALS=1`), 220s. `samples/` reverted to pristine before the gate ran (verified
  clean `git status`).

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
