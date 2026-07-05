# T20: Drawable hot-reload e2e case + README row
Status: TODO
Assignee: agy

## Goal
Regress the drawables→tier-1 capability shipped 2026-07-05 (see
`docs/resource-edits-v1.md` §v2): vector-drawable XML edits hot-reload via the resource
overlay + `ComposeBridge.clearAssetCaches()` with full state preservation. The engine and
runtime-client behavior is already implemented and live-verified; this task only adds the
automated e2e case and documents the capability. Everything here is mechanical.

## Spec
1. `e2e/run.sh`: add **case 11 `drawable-edit`** after case 10, following the existing
   case structure (counter grep on the watch log, `assert_ui`-style checks, same-PID
   assert). Steps:
   - The testbed already exists in the sample:
     `samples/single-module/app/src/main/res/drawable/hot_icon.xml` (full-viewport vector,
     fill `#FF2196F3`) rendered by `HotIcon()` (contentDescription `HOT_ICON`, 48dp).
   - Read the icon's on-screen colour with `scripts/icon-pixel.sh` (already committed:
     prints `#RRGGBB` of the HOT_ICON node's centre pixel; needs python3 + PIL, same as
     nothing else new — it's screencap-based). Assert baseline `#2196F3`.
   - Tap the counter 2x (taps.sh) so there is remember/rememberSaveable state to preserve.
   - Edit the drawable: `perl -pi -e 's/#FF2196F3/#FFFF0000/' .../hot_icon.xml`.
   - Wait for the next `resource-swapped:` line (same wait helper as case 8).
   - Assert `scripts/icon-pixel.sh` now prints `#FF0000` (allow up to ~10s of retries —
     recomposition lands a frame after the swap; mirror assert_ui's retry loop).
   - Assert `Count: 2 / Saved: 2` still on screen and PID unchanged.
   - Restore hot_icon.xml at cleanup exactly like the other edited files (git checkout in
     the trap; the file is committed with the blue fill as baseline).
   - Update the script's TOTAL case count and any summary text.
2. README.md: add one row to the "What hot-reloads today" table:
   vector drawable edit in `res/drawable*/*.xml` | resource overlay + compose asset-cache
   clear + whole-tree recompose, state preserved | ~1–2s. Keep the existing table format;
   do not restructure the README. If the README has a "resource edits" limitations note
   saying drawables need reinstall, update it (bitmap png/webp still needs reinstall —
   only XML drawables hot-reload).

## Out of scope
- Any change under engine/, protocol/, runtime-client/, gradle-plugin/, cli/.
- e2e/run-multi.sh (multi-module drawable coverage can be a later task).
- samples/ sources (testbed already committed) and scripts/icon-pixel.sh.

## Acceptance
From repo root, emulator booted (`scripts/emulator-up.sh`):
1. `./e2e/run.sh` → **11/11 PASS**, exit 0, run twice back-to-back, both green.
2. `pgrep -f dev.hotreload.cli.MainKt` → empty after each run.
3. `git status --short` → only e2e/run.sh + README.md changed; sample sources byte-identical
   (hot_icon.xml restored to `#FF2196F3`).
4. README table row verbatim-consistent with the actual watch-log line (`resource-swapped:`)
   and the doc (`docs/resource-edits-v1.md` §v2).
