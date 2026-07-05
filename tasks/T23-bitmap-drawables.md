# T23: Bitmap (png/webp) drawable hot-reload
Status: TODO
Assignee: agy (device needed — run interactively)
Priority: 2 of T21–T27 (after T21)

## Goal
Vector-drawable XML edits hot-reload since drawables-v2 (`docs/resource-edits-v1.md` §v2);
bitmap drawables (`.png`/`.webp`) still require reinstall because (a) the watcher only delivers
`.kt`/`.xml` and (b) `painterResource` caches decoded bitmaps with `remember(path, id, theme)`
in the caller's slot-table group. This task ships (a) and validates that (b) is already busted
for free by the overlay path change — likely YES, because every edit lands in a NEW
`hotreload-overlay-<session>-N` dir, so the resolved asset *path* (a `remember` key) changes,
and `invalidateAll` re-executes every `painterResource` call site.

## Spec
1. `engine/src/main/kotlin/dev/hotreload/engine/SourceWatcher.kt`: deliver `.png` and `.webp`
   too (extension allowlist: `kt`, `xml`, `png`, `webp`). Never read these as text anywhere
   downstream — audit the batch path for UTF-8 reads of event paths (there should be none;
   files are only *classified*, the apk provides the bytes).
2. `engine/.../WatchSession.kt`: the resource-batch predicate `isResourceXml` becomes
   `isResourceFile` = any `res/<type>/*.{xml,png,webp}` under a module resDir. Same routing to
   `ResourceSwapper` (the value-only guard already treats file-based resources by `(type,name)`
   — a png content change keeps the ID set identical, so it passes the guard; verify this).
3. Testbed in `samples/single-module`: add `res/drawable/hot_photo.png` — a 48x48 solid
   `#2196F3` PNG, generated once with python3/PIL and committed — rendered by a `HotPhoto()`
   composable via `painterResource`, `contentDescription = "HOT_PHOTO"`, 48dp, placed under
   `HotIcon()`. Keep a v2 file `e2e/fixtures/hot_photo_red.png` (solid `#FF0000`) committed for
   the e2e swap (binary edit = file replace, not perl).
4. e2e case 12 `bitmap-edit` in `e2e/run.sh`, mirroring case 11 exactly but: copy the red
   fixture over `hot_photo.png`, wait for `resource-swapped:`, assert centre pixel of the
   HOT_PHOTO node is `#FF0000` (extend `scripts/icon-pixel.sh` to take the node label as an
   arg, default `HOT_ICON` — keep case 11 calls working), state + PID preserved, restore via
   `git checkout` in the trap.
5. **If the pixel stays stale** (the remember-key theory is wrong): do NOT improvise a fix.
   Record exactly what you observed in `docs/resource-edits-v1.md` §Open questions (logcat,
   whether a manual activity recreate surfaces it), mark this task IN-REVIEW, and stop — the
   cache then needs a Fable/Claude look. Watcher + testbed changes still land either way
   (an edit then correctly reports its result; nothing regresses).

## Out of scope
- runtime-client/ and protocol/ — no changes (T21's loader work is separate; this task works
  with or without T21 landed, but run acceptance after rebasing on it if it merged first).
- `.jpg`, nine-patch, mipmap launcher icons, font resources.
- e2e/run-multi.sh.

## Acceptance
From repo root, emulator booted:
1. `./e2e/run.sh` → 12/12 PASS twice back-to-back; pgrep clean; tree clean after run
   (hot_photo.png restored byte-identical).
2. Manual: `hotreload watch` on the sample, replace hot_photo.png with the red fixture →
   `resource-swapped:` line, red pixel on screen ≤3s, `Count`/`Saved` values and PID unchanged.
3. README.md table: add the bitmap row (or extend the drawable row) consistent with observed
   behavior; update the "png/webp needs reinstall" limitation note.
