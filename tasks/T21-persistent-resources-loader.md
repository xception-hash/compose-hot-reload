# T21: Persistent ResourcesLoader + setProviders + overlay GC
Status: TODO
Assignee: agy (device needed — run interactively)
Priority: 1 of T21–T27 (do first)

## Goal
Kill the per-edit leak in resource hot-reload: today every value/drawable edit creates a fresh
`ResourcesLoader` (stacked forever on the `Resources` objects) plus a new
`code_cache/hotreload-overlay-<session>-N` dir that is never deleted. Replace with the AOSP
in-place pattern (`third_party/tools-base/deploy/agent/runtime/src/main/java/com/android/tools/
deploy/instrument/ResourceOverlays.java`, greppable after `scripts/fetch-aosp-deployer.sh`):
ONE loader per `Resources`, swapped via `setProviders`, old overlay dirs cleaned up.
Background: `docs/resource-edits-v1.md` §Open questions (loader/dir accumulation).

## Spec
All changes in `runtime-client/lib/src/main/kotlin/dev/hotreload/client/PatchServer.kt`
(+ a small helper class if cleaner). Engine stays untouched.

1. Keep a session-scoped singleton `ResourcesLoader` (one instance is enough; the SAME loader
   object may be added to both the activity and application `Resources` — that is exactly what
   AOSP does). On each `LoadResources(overlayDir)`:
   - build the new `ResourcesProvider.loadFromDirectory(codeCache/overlayDir, null)`;
   - `loader.setProviders(listOf(newProvider))` (replaces, does not stack);
   - `close()` the PREVIOUS provider and `deleteRecursively()` its overlay dir;
   - ensure the loader is attached (`addLoaders`) to the current activity's and the
     application's `Resources` — attach is idempotent per Resources instance, but only call it
     when not already attached (keep a `WeakHashMap<Resources, Unit>` of attached targets).
2. On the FIRST `LoadResources` of a process lifetime, delete any stale
   `code_cache/hotreload-overlay-*` dirs that are not the one just received (leftovers from
   crashed sessions).
3. Do NOT change the engine's per-edit overlay dir naming (`hotreload-overlay-<session>-N`).
   The incrementing name is load-bearing: `painterResource` bitmaps are cached with
   `remember(path, id, theme)` and a *changed* file path is what busts that cache (see
   T23). In-place = same provider dir would break that.
4. Keep everything on the main thread inside the existing `LoadResources` handler block
   (same ordering: addLoaders/setProviders → `clearAssetCaches()` → `invalidateAllCompositions()`).
5. Runtime-client change ⇒ device needs `installDebug` + app relaunch (check the Ping line
   still says protocol 4 — no protocol change here).

## Out of scope
- engine/, protocol/, cli/, gradle-plugin/ — no changes.
- Multi-activity re-attach on resume (T22 builds on this loader).
- ResourceSwapper push-path (`/data/local/tmp` staging) — unchanged.

## Acceptance
From repo root, emulator booted (`scripts/emulator-up.sh`):
1. `./e2e/run.sh` → 11/11 PASS, twice back-to-back; `pgrep -f dev.hotreload.cli.MainKt` empty.
2. Overlay GC proof: after a run (the suite performs ≥3 resource swaps in cases 8–11),
   `adb shell run-as dev.hotreload.sample ls code_cache/ | grep -c hotreload-overlay` prints `1`.
3. Loader-stacking proof: 3 consecutive manual string edits in a `hotreload watch` session all
   land on screen (last wins; `scripts/ui-state.sh` shows the 3rd value), same PID throughout.
4. `./e2e/run-multi.sh` → 4/4 PASS (multi-module resource case exercises the same client path).
