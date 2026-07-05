# Resource edits v1 — implementation findings (T17)

> **v1.1 (2026-07-05):** post-ship review of the T17 diff found three bugs, all fixed and
> e2e-covered (cases 9–10). Details in §v1.1 review fixes below; the rest of this doc is
> updated in place where the fixes changed behavior.
>
> **v2 (2026-07-05): drawables are now tier-1 too.** Vector-drawable XML edits hot-reload
> with full state preservation — no reinstall, no recreate. See §v2 drawables below; it
> also corrects T16's theory of *why* drawables looked unreloadable.

Shipped + live-validated 2026-07-04 on `samples/single-module`, API 36 arm64 emulator,
**protocol v3**. This is the build-out of the research in `docs/resource-edits-notes.md`
(T11), `docs/resourcesloader-compose-experiment.md` (T14), and
`docs/resource-invalidation-experiment.md` (T16). Those three settled *what to do*; this
records what building it into the real `hotreload watch` loop confirmed, decided, and
surfaced.

## What now works end-to-end via `hotreload watch`
| Case | Mechanics | Time |
|---|---|---|
| string/color **value** edit in `res/values/*.xml` | `assembleDebug` → extract `resources.arsc`+`res/` → push → `run-as cp` into `code_cache` → `LoadResources` → fresh `ResourcesLoader` + whole-tree `invalidateAll` | ~1.3–1.9s |
| **vector drawable** edit in `res/drawable*/*.xml` (v2) | same overlay pipeline + `ComposeBridge.clearAssetCaches()` between loader attach and `invalidateAll` | ~1.1–2.1s |
| add / remove / rename a resource | value-only guard trips → prints "reinstall required", **app untouched**, watch keeps running | <0.1s (no build) |

Same-PID throughout; `remember` **and** `rememberSaveable` preserved across a value swap
(counter held at `Count: 3 / Saved: 3` while the string flipped). e2e case 8 added; v1.1
added cases 9 (watch-restart-resource-edit) and 10 (pending-resource-swap); full gate is
**10/10 in 119s**, no leaked watcher.

## The invalidation half — T16's route confirmed at the engine level
T16 proved `ControlledComposition.invalidateAll()` is the only keyless, state-preserving
whole-tree recompose, via a broadcast-driven experiment. T17 is the first time that route
runs through the real `PatchServer`, and it works **verbatim** as T16 documented:

- `ComposeBridge.invalidateAllCompositions()`: static field
  `Recomposer._runningRecomposers` (a `MutableStateFlow`) → `getValue()` →
  `Set<RecomposerInfoImpl>` → each element's synthetic `this$0` (the owning `Recomposer`)
  → `_knownCompositions` (`List<ControlledComposition>`) → `invalidateAll()` on each.
- Field lookups use **prefix `startsWith` matching** (mirrors the existing method-lookup
  shim) to tolerate any `$runtime`-style mangling; the names carried the underscore
  prefix exactly as T16 saw them.
- The list lives on the **Recomposer instance**, not the Companion — so this bridge
  method resolves the `Recomposer` class fresh rather than reusing the cached Companion
  the other shims use.
- Runs on the main thread (via `PatchServer.onMainThread`, same as `Invalidate`/`Reset`).
- One composition is reachable through multiple recomposers; de-duped by identity so
  `invalidateAll` fires once per composition.

## Design decisions (and why)
- **A fresh `ResourcesLoader` per edit, not persistent-loader + `setProviders`.** T14 and
  T16 both only ever exercised the fresh-loader path; `setProviders` is an AOSP-source
  inference never run in our experiments. For v1, with a limited on-device iteration
  budget, correctness-certainty won: `ResourcesLoader().addProvider(...)` +
  `addLoaders(...)` is proven, and "last-added wins" makes each edit correct. The cost is
  loader/dir accumulation (see Open questions). The T17 spec's own protocol text sanctions
  this ("Repeat LoadResources = add a NEW loader"); persistent-loader + `setProviders`
  (AOSP `ResourceOverlays.java`) is the documented follow-up.
- **Attach to the resumed activity's `Resources` AND the application's.** Compose reads
  `LocalContext.current.resources`, which is the **activity's** `Resources` — so a new
  `ActivityTracker` (an `ActivityLifecycleCallbacks` installed by the startup initializer,
  before any activity) hands `loadResources` the foreground activity. We add to the
  application's `Resources` too, matching T14 (its step d showed the loader survives a
  recreate when it's on `application.resources`). *Which of the two is strictly required
  was not isolated* — we add both, as T14 did.
- **Full `assembleDebug`, then extract `resources.arsc` + `res/` from the APK.** No aapt2
  partial-compile pipeline in v1 (spec: "full assembleDebug is the v1 cost"). Reuses the
  exact overlay-delivery recipe T14 proved. In a mixed code+resource batch the class swap
  (`compileDebugKotlin` + redefine) runs first, then `assembleDebug` (kotlin already
  up-to-date, so it just repackages), then a single `verifyRecomposition`.
- **Value-only guard by `(type,name)` set, not arsc entry count.** aapt2 keeps resource
  IDs stable across pure value edits but renumbers when the resource *set* changes, which
  would desync the already-loaded dex's `R.*` constants. The guard parses every
  `res/values*/**.xml` with the JDK DOM parser, collects `type/name` (element tag, or the
  `type` attr for `<item>`), **plus every file-based resource as `type/stem`**
  (`res/<type>[-qualifier]/<name>.<ext>` → `type/name`, since adding `res/drawable/foo.xml`
  renumbers IDs exactly like adding a string — v1.1 fix, the original scan missed these),
  and compares to the last-known-good set. Any add/remove/rename → "reinstall required",
  skip the swap, **keep the old baseline** so the message persists until the user
  reinstalls (or reverts). Config qualifiers collapse (`values/` and `values-night/` with
  the same name = one ID, `drawable/` and `drawable-hdpi/` likewise), which is exactly
  what governs ID stability.
- **`run-as cp` from `/data/local/tmp`.** `ResourcesProvider.loadFromDirectory` reads from
  the app-private `code_cache`, which `adb push` can't write directly; push to
  `/data/local/tmp` then `run-as <pkg> cp -r` into
  `code_cache/hotreload-overlay-<sessionTag>-N` (the overlay dir name is the relative path
  sent in `LoadResources`, same file-based convention as `InjectDex`). `sessionTag` is the
  session start time in base36 and the dest is `rm -rf`ed before the copy — both v1.1
  fixes, see below. The host-side `/data/local/tmp` staging is cleaned after the copy.

## Gotchas hit while building
- **A backup file under `res/` breaks the build.** The e2e case first backed `strings.xml`
  up to `strings.xml.bak` *in the same `res/values/` dir*; AGP's resource merger rejects
  any non-`.xml` file there ("The file name must end with .xml") and `packageDebugResources`
  fails. The backup must live outside `res/` (moved to `mktemp`). This also means the engine
  must never write scratch files under a watched `res/` tree.
- **`android.content.res.loader.*`, not `android.content.res.*`** for `ResourcesLoader` /
  `ResourcesProvider` (re-confirming T14's import gotcha).
- **`safeName` permits `.`** (`[A-Za-z0-9._-]+`), so a bare `..` passes the InjectDex-style
  filename check; `loadResources` adds an explicit `".." !in overlayDir` reject.
- The runtime-client change is a protocol bump → the device needs **reinstall + relaunch**
  for the v3 client (the `Ping` line prints `protocol=3`); an engine-only change just needs
  a watch restart.

## Timings (observed)
`resource-swapped: hotreload-overlay-1 in 1874ms`, then `...overlay-2 in 1275ms` (warmer).
The cost is dominated by `assembleDebug` (mostly up-to-date, since kotlin is already
compiled — it's the resource repackage + APK write); extract + push + `run-as cp` +
`LoadResources` + `invalidateAll` are the small remainder. Comfortably under the 5s target.

## v1.1 review fixes (2026-07-05)
Three bugs found by a full-diff review after the DONE; none were reachable by e2e as it
stood (it reinstalls per run and never mixes a broken `.kt` with a values edit).

1. **Overlay-name collision across watch sessions** (worst — silent stale values).
   `ResourceSwapper.seq` restarts at 1 every session, but `code_cache` survives watch
   restarts (only a reinstall clears it). `run-as cp -r` into the leftover
   `code_cache/hotreload-overlay-1` **nests** the copy (POSIX/toybox), leaving the stale
   session-1 `resources.arsc` on top — the client re-attached last session's values and
   the new edit never surfaced. Repro: restart watch without reinstalling (a supported
   flow), edit a string. Fix: overlay names carry a session tag
   (`hotreload-overlay-<startMillis-base36>-N`) **and** the dest is `rm -rf`ed before the
   `cp`. e2e case 9 (watch-restart-resource-edit) regresses this.
2. **Guard missed file-based resources.** The `(type,name)` scan read only `res/values*`
   XML, so adding e.g. `res/drawable/foo.xml` shifted aapt2 IDs undetected and the *next*
   value edit could overlay an arsc whose IDs no longer match the installed dex's `R.*`
   constants. Fix: file-based resources are scanned as `type/stem` (qualifiers and
   extensions collapse; `foo.9.png` → `foo`; dotfiles like `.DS_Store` ignored).
3. **A mixed broken-`.kt` + values save dropped the resource edit.** The batch bailed on
   compile failure and the watcher never re-delivers the unchanged `.xml`, so the value
   edit was silently lost until re-saved. Fix: `WatchSession.pendingResourceSwap` — a seen
   values edit stays pending until a swap succeeds; the fix-and-save flushes it (the swap
   is still skipped *within* the failing batch, since `assembleDebug` shares the failing
   compile). Side effect: after a guard trip, the "reinstall required" reminder now prints
   on every subsequent save, not only on `.xml` re-saves — intended. e2e case 10
   (pending-resource-swap) regresses this.

## v2 (2026-07-05): drawables → tier-1

Live-validated on `samples/single-module` (new `HotIcon()` + `res/drawable/hot_icon.xml`
full-viewport vector; on-screen truth read by `scripts/icon-pixel.sh` — screencap +
centre-pixel of the `HOT_ICON` node): **3 consecutive fill-color edits each surfaced in
~1.1–2.1s, same PID, `Count: 3 / Saved: 3` preserved** (`remember` + `rememberSaveable`).
No protocol change (still v4) — the clear rides inside the existing `LoadResources`
handler — but the runtime-client changed, so devices need one reinstall.

What shipped:
- **Engine**: `WatchSession.isResValues` → `isResourceXml` — any `.xml` directly under a
  `res/<type>/` dir of any module routes to `ResourceSwapper` (values, drawable, color,
  ...). The (type,name) guard already covered file-based resources since v1.1, so editing
  an existing drawable passes and add/rename still trips "reinstall required".
- **Runtime-client**: `ComposeBridge.clearAssetCaches()`, called by
  `PatchServer.loadResources` between `addLoaders` and `invalidateAllCompositions` (same
  main-thread block → ordering is race-free).

**T16's "drawables never surface" theory was wrong in an interesting way.** With the shipped
pipeline (no cache clear), a drawable edit surfaces *nondeterministically*: live we saw
edit 1 fresh, edits 2–3 stale, twice in a row. Reason: at ui 1.11.4 `ImageVectorCache` is
`HashMap<Key, WeakReference<ImageVectorEntry>>` — freshness depends on whether a GC has
collected the weakly-held entry since the icon last rendered. T16 sampled the "no GC yet"
side and concluded "only recreate works"; a GC lottery isn't shippable either way, so the
deterministic fix stands: clear the cache, then `invalidateAll` (`Image` slot state like
`VectorPainter` is `remember`-keyed on the ImageVector instance, so a fresh decode swaps
the painter without touching sibling state).

**How the caches are reached — the T16-era route is gone.** Two dead ends, then the fix:
1. Compose's own clear path is `ComponentCallbacks2.onTrimMemory` (proven live:
   `adb shell am send-trim-memory <pkg> RUNNING_CRITICAL` + next `invalidateAll` surfaced
   the edit). But in-process, `Application.mComponentCallbacks` is hidden-API-filtered:
   the field is *invisible to `getDeclaredFields`* (a debuggable app + attached JVMTI
   agent does NOT lift enforcement — reflection sees only 3 fields on `Application`).
   External `am send-trim-memory` from the engine would race the swap's own recompose.
2. At ui **1.9** the cache was a `remember { ImageVectorCache() }` slot. At **1.11.4 it is
   not in the slot table at all**: it lives on `androidx.compose.ui.platform.
   ComposeViewContext` (fields `imageVectorCache` + `resourceIdCache`), reachable as
   `composition.composable._block.$composeViewContext` (found by an on-device BFS dump).
3. Shipped route, version-tolerant: bounded BFS (50k cap; ~5.9k visited live) over the
   object graph from every `_knownCompositions` element, expanding only `androidx.*`
   objects and arrays, `clear()`ing every `androidx.compose.ui.res.*Cache` encountered —
   finds both caches at 1.11 (ComposeViewContext layout) and would find 1.9's slot-table
   layout the same way. Results memoized via `WeakReference` so repeat edits skip the walk.
   Loud `Log.e` when nothing is found (the "drawables may stay stale" canary).

Scope notes:
- **Bitmap drawables (png/webp) are NOT hot-reloaded**: `SourceWatcher` only delivers
  `.kt`/`.xml`, and `painterResource`'s bitmap branch caches via `remember(path,id,theme)`
  — which `invalidateAll` deliberately preserves. Vector XML is the dominant Compose case;
  bitmaps stay on the reinstall path for now.
- Non-drawable file-based XML (layout/anim/xml/) also routes through the swapper now —
  the overlay is correct (next inflate reads it) but nothing forces a re-inflate; harmless.

## Open questions / follow-ups (not built)
- **Loader / dir accumulation.** N value edits in one watch session = N `ResourcesLoader`s
  stacked on the Resources + N `code_cache/hotreload-overlay-N` dirs. Correct (last wins)
  but a slow leak. Fix = the AOSP persistent-loader + `setProviders` in-place pattern (also
  enables GC). Out of scope for v1 by spec.
- **Which `Resources` is strictly necessary** (activity vs application) — not isolated; we
  attach both.
- **Multi-activity / navigation.** We attach at `LoadResources` time to the *then-current*
  activity + application. An activity launched *after* an edit may not carry the overlay
  unless it inherits from `application.resources`. Untested — the sample is single-activity.
  AOSP re-applies overlays on activity start as a safety net; we do not.
- **Bitmap (png/webp) drawables** — see §v2 scope notes: needs watcher support for binary
  extensions plus a way to bust the `remember(path,id,theme)` slot without nuking state
  (or accept a targeted `invalidateGroupsWithKey` on readers, which resets their subtree
  `remember`). Not started.
- ~~Drawables / decoded assets still need a reinstall~~ — **DONE in v2** (see above).
- ~~Multi-module resources~~ — **DONE** (multi-module design, commit 8246b9b): the guard
  unions `(type,name)` across all module res roots; e2e `run-multi.sh` case 4 covers it.
