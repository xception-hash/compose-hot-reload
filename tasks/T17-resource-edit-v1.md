# T17: Resource edits v1 — string/color value edits, end-to-end via `hotreload watch`
Status: BLOCKED on T16 (needs its invalidation recommendation). Everything else is specced.
Assignee: Opus session (protocol message is DESIGNED below — implementation + wiring only)

## Goal
`hotreload watch` picks up a saved change to an EXISTING string/color value in
`res/values/*.xml`, overlays it onto the running app via `ResourcesLoader` (T14-proven),
triggers T16's recommended invalidation, and the new value is on screen without reinstall
or state loss. Value-changes only — add/remove/rename resources (aapt2 ID renumbering) is
detected and reported as "needs reinstall", not attempted.

## Protocol design (FIXED — implement as written)
- Bump `Protocol.kt` VERSION 2 → 3.
- New request `LoadResources(val overlayDir: String)` — path RELATIVE to the app's
  codeCacheDir (matches InjectDex's file-based convention). Response: existing
  Ack/Failure. Client behavior: `ResourcesProvider.loadFromDirectory` →
  `ResourcesLoader.addProvider` → `addLoaders` on BOTH activity and application
  resources (T14 recipe verbatim, `.loader` subpackage gotcha), then run T16's
  invalidation mechanism on the main thread (like Invalidate/Reset do). Repeat
  LoadResources = add a NEW loader (last-added wins in the loader stack); no removal in v1.
- Wire.kt: add the codec in BOTH write() and readRequest (code-map gotcha).

## Spec
1. runtime-client `PatchServer.kt`/`ComposeBridge.kt`: handle LoadResources per above.
   Path validation like InjectDex (reject `..`, absolute paths).
2. engine:
   - `SourceWatcher`: also watch the module's `src/main/res` (values xml only for v1).
   - New `ResourceSwapper.kt`: on res-save → run the sample's `assembleDebug` via the
     existing warm GradleCompiler connection → unzip ONLY `resources.arsc` + `res/` from
     the APK into a temp dir (T14 §Setup shows this works and keeps IDs stable for
     value-only edits) → `adb push` to /data/local/tmp + `adb shell run-as <pkg> cp`
     into `code_cache/hotreload-overlay-<seq>` (reuse Adb.kt helpers) → send
     LoadResources.
   - Value-only guard: diff the old/new resource file set; if a resource NAME was
     added/removed/renamed (parse the changed values xml, or compare arsc entry count),
     print "resource added/removed — reinstall required", skip the swap.
   - Mixed batches (code + res in one save window): do the class swap first, then the
     resource swap; both settle before verifyRecomposition.
3. e2e case 8 "resource-edit": edit a string value in the sample → assert new text on
   screen AND counter state preserved; restore file. Follow e2e/run.sh case conventions
   (pkill lesson).
4. README: add res-edit row to the capability table; note values-only v1 limit.

## Out of scope
Drawable edits unless T16 says tier-1 works for them (else leave a TODO naming T16's
tier). No aapt2 partial compile pipeline (full assembleDebug is the v1 cost). No loader
removal/stacking GC. No multi-module res (single-module sample only).

## Acceptance
Run from repo root, `source /abs/path/scripts/env.sh` in EVERY command (env gotcha):
1. Protocol version handshake: watch's Ping line shows v3 after app reinstall.
2. Manual loop: `hotreload watch` on samples/single-module, edit a string value →
   new text visible < 5s, counter state preserved (scripts/ui-state.sh + taps.sh).
3. Add-resource guard: add a NEW string + save → watch prints the reinstall-required
   message, app untouched (no Failure spam, watch keeps running).
4. `e2e/run.sh` 8/8, `pgrep -f dev.hotreload.cli.MainKt` empty after.
5. `git status` clean except protocol/, runtime-client/, engine/, e2e/, README, this spec.
