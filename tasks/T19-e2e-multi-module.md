# T19: Multi-module e2e cases + README update
Status: TODO
Assignee: agy

## Goal
Automate the multi-module validation that was done by hand on 2026-07-05 (see
`docs/multi-module-design.md` §Live validation) so the capability can't regress, and document
the new `--module` list in the README. Everything here is mechanical — the engine behavior is
already implemented, committed (8246b9b), and live-verified.

## Spec
1. New script `e2e/run-multi.sh`, closely mirroring `e2e/run.sh`'s structure and helpers
   (same logging, same PASS/FAIL accounting, same cleanup discipline). Differences:
   - Project: `samples/multi-module`, app id `dev.hotreload.multisample`.
   - Watch command (from repo root):
     `./gradlew -q :cli:run --args="watch --project $REPO_ROOT/samples/multi-module --app-id dev.hotreload.multisample --module app,feature,core"`
   - Watch readiness: wait for the `watching ` line in the watch log (it appears AFTER the
     initial build and module probe; allow the same timeout run.sh uses for its first case).
   - The sample's buttons have no text labels usable by `taps.sh`; tap by uiautomator bounds
     lookup: dump with `adb exec-out uiautomator dump /dev/tty`, find the clickable node whose
     bounds enclose the "App count:"/"Feature count:" text, tap its center (helper function).
2. Cases (each verifies same-PID before/after, like run.sh):
   - **multi-core-body**: tap App counter 2x → edit
     `core/src/main/kotlin/dev/hotreload/multisample/core/CoreLabel.kt`
     `"core says:"` → `"core edit:"` → expect watch log
     `whole tree invalidated (no compose keys)` AND UI shows `core edit:` AND `App count: 2`
     still on screen (state preserved through InvalidateAll).
   - **multi-feature-composable**: edit
     `feature/src/main/kotlin/dev/hotreload/multisample/feature/FeatureCard.kt`
     `"FeatureCard: "` → `"FeatureX: "` → expect log `groups invalidated` (NOT whole-tree)
     AND UI shows `FeatureX:`.
   - **multi-abi-rebuild**: edit CoreLabel.kt signature `coreLabel(n: Int)` →
     `coreLabel(n: Int, suffix: String = "")` → expect log `cannot hot-swap` and
     `signatures changed`; then revert the edit → expect `no bytecode changes`.
   - **multi-resource-edit**: edit `feature/src/main/res/values/strings.xml` value
     `feature res v1` → `feature res e2e` → expect log `resource-swapped` AND UI shows
     `feature res e2e`.
   - Restore every edited file to its committed content at the end (git checkout of the three
     files is fine, as run.sh does), and back up strings.xml OUTSIDE res/ (AGP's resource
     merger rejects any non-.xml file under res/values — bit us in T17).
3. Cleanup: identical discipline to run.sh — `pkill -9 -f dev.hotreload.cli.MainKt` in the
   trap AND pre-flight (leaked watchers corrupt runs — T06 lesson). Reinstall
   (`:app:installDebug` in samples/multi-module) + relaunch at script start so the device has
   the protocol-v4 runtime-client and a clean resource-ID baseline.
4. README.md: in the watch-command section, document `--module` as a comma list (first entry =
   app module; nested paths use `/`), with a one-line multi-module example using
   samples/multi-module. Mention pure-Kotlin (kotlin-jvm) modules are supported and that edits
   there recompose the whole tree with state preserved. Keep the existing table format; do not
   restructure the README.

## Out of scope
- Any change under engine/, protocol/, runtime-client/, gradle-plugin/, cli/.
- e2e/run.sh (single-module suite stays as-is) and CI workflow wiring (separate task if wanted).
- samples/multi-module sources (the resource testbed is already committed).

## Acceptance
From repo root, emulator booted (`scripts/emulator-up.sh`):
1. `./e2e/run-multi.sh` → all 4 cases PASS, exit 0 (run twice back-to-back, both green).
2. `./e2e/run.sh` afterwards → 10/10 PASS (multi script leaked nothing).
3. `pgrep -f dev.hotreload.cli.MainKt` → empty after each run.
4. `git status --short` → only run-multi.sh + README.md changed; sample sources byte-identical.
5. README renders with the `--module` list documented (verbatim-correct command lines).
