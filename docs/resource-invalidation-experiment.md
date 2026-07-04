# T16 — Drawable/color overlays + the zero-app-edit, state-preserving invalidation

Closes the two gaps T14 left before resource-edit support (`docs/resourcesloader-compose-experiment.md`):

1. Do `colorResource`/`painterResource` pick up a `ResourcesLoader` overlay on a tier-1
   recomposition, like `stringResource` does — or does a cached decoded asset force a recreate?
2. What is the **zero-app-edit** mechanism that recomposes **all** resource readers with `remember`
   **preserved**? A pure resource edit has no edited-function key, and
   `invalidateGroupsWithKey` resets `remember` in the keyed subtree (phase-2 finding), so keying
   "everything" would be tier-2-grade state loss.

**Answers (on-device, emulator `Medium_Phone_API_36.0` / API 36, Compose BOM 2026.06.01, 2026-07-04):**

- **String + color** surface on a plain tier-1 recomposition (`colorResource` behaves exactly like
  `stringResource`).
- **Drawable does NOT** surface on *any* recomposition (neither `invalidateAll` nor
  `invalidateGroupsWithKey`) — only an activity **recreate** updates it. The decoded asset is cached
  in a **Context/root-scoped** cache, not a composition-subtree cache.
- The state-preserving whole-tree mechanism is **`ControlledComposition.invalidateAll()` on every
  known composition**, reached by reflection from `Recomposer._runningRecomposers`. It recomposes
  all readers with `remember` **and** `rememberSaveable` fully preserved, and needs no key.

All sample edits reverted; `git status` clean except this doc + the T16 spec status line.

---

## §0 — What the AOSP deployer does after `addResourceOverlays` (free grep, thanks T13)

Source: `third_party/tools-base/deploy/` @ `14c65dad` (`third_party/PINNED.txt`).

- **`ResourceOverlays.java`** keeps **one persistent `ResourcesLoader`**. First application uses
  `resources.addLoaders(loader)` (per-`Resources`) or, pre-API-31, walks
  `ResourcesManager.mResourceReferences` and adds to every live `Resources`. On a *subsequent* swap
  it does **not** re-add — it calls `resourcesLoader.setProviders(newProviders)`, and the comment is
  explicit: *"This will update every AssetManager that currently references our loader."* So the
  loader object is attached once and its content is swapped in place. (We attach a fresh loader each
  time in the experiment; for the engine, the persistent-loader + `setProviders` pattern is the one
  to port — it avoids stacking loaders across edits.)
- **How it refreshes the UI after overlaying resources:** `agent/native/restart_activity.cc` calls
  `addResourceOverlays()` **then** `restartActivity()` → `Activity.recreate()`. **The deployer's
  resource path is a full activity recreate (tier-3).** It has **no** state-preserving whole-tree
  resource refresh.
- Its Compose-aware paths (`agent/native/live_edit.cc` + `recompose.cc`) exist only for **code**
  Live Edit and offer exactly three modes: `RESTART_ACTIVITY` (recreate),
  `INVALIDATE_GROUPS` = `invalidateGroupsWithKey(group_ids)` (needs the edited function's keys), and
  `SAVE_AND_LOAD` = `saveStateAndDispose` + `loadStateAndCompose` (== our state-losing tier-2). None
  of the three is a keyless, `remember`-preserving whole-tree recompose. **The prime candidate below
  (`invalidateAll`) is therefore a mechanism AOSP does not use — and it strictly beats their
  resource path for strings/colors.**

---

## Setup

Extends the T14 harness (its §Setup is the base recipe, incl. the
`android.content.res.loader.*` import gotcha and the broadcast hook). Additions:

- **Resources** (`app/src/main/res`, temporary): `exp_label` string, `exp_color` color
  (`#FF2196F3` blue), `exp_icon` vector drawable (blue filled **circle**). The overlay build flips
  all three: `OVERLAY RES`, `#FFFF0000` red, and a red filled **square** (shape + colour change so a
  single centre-pixel read distinguishes them).
- **UI** — `ExpReader()` reads all three (`stringResource` / `colorResource` /
  `painterResource(Image)`) and owns an **Inner** `remember` counter that is both a recompose driver
  and an in-subtree state canary. `Counter()` (outer, a sibling) carries `remember` **and**
  `rememberSaveable` canaries. `inner` is read directly in `ExpReader`'s body so a tap invalidates
  `ExpReader`'s *own* scope and re-runs the resource reads (a read only inside the `Button` lambda
  recomposes just that child — an early false negative that cost one rebuild).
- **Overlay delivery** identical to T14: build the flipped APK, extract `resources.arsc` + `res/`,
  `run-as` copy into `code_cache/exp-overlay` (resource-table structure identical between builds, so
  `R.*` IDs are stable). The installed, running app is always the ORIGINAL build.
- **Measurement.** `direct` = in-process `getString`/`getColor`/rasterised-drawable-centre-pixel
  (bypasses Compose), logged for both `activity.resources` and `application.resources`. `on-screen`
  = `uiautomator` text for string/color-hex; the drawable is read by `screencap` + PIL sampling the
  `EXP_ICON` node's centre pixel (drawables aren't text).

Broadcast commands (`-a dev.hotreload.EXP -p dev.hotreload.sample --es cmd <x>`):
`add` (attach loader), `log` (direct reads), `recreate`, `invalidateall`,
`invalidatekey --ei key <k>`.

---

## Table 2 — per-type surface at each step

Seeded before `add`: outer Count 4 / Saved 4, Inner 3. `direct` is identical on `activity.resources`
and `application.resources` throughout.

| Step | Action | direct (str / color / drawable-px) | on-screen string | on-screen color | on-screen drawable px | outer Count/Saved | Inner |
|---|---|---|---|---|---|---|---|
| launch | ORIGINAL, no loader | `ORIGINAL RES` / `#FF2196F3` / `#FF2196F3` | `ORIGINAL RES` | `#FF2196F3` | `#2196F3` blue | 4 / 4 | 3 |
| **a** | `add` loader, no other action | **`OVERLAY RES` / `#FFFF0000` / `#FFFF0000`** | `ORIGINAL RES` — unchanged | `#FF2196F3` — unchanged | blue — unchanged | 4 / 4 | 3 |
| **b** | tap = **plain recomposition** of reader | `OVERLAY` / red / red | **`OVERLAY RES`** ✅ | **`#FFFF0000`** ✅ | `#2196F3` blue ❌ | 4 / 4 | 4 |
| c | tier-2 reset | *skipped* (needs live engine; bounded by a/b/d) | | | | | |
| **d** | `activity.recreate()` | `OVERLAY` / red / red | `OVERLAY RES` ✅ | `#FFFF0000` ✅ | **`#FF0000` red** ✅ | **0** / 4 | 0 |

Reading: `add` flips **all three** direct reads instantly (the loader reaches the drawable at the
`Resources` level too) but changes nothing on screen — no recomposition yet. A plain recomposition
surfaces the **string and color** but leaves the **drawable stale** (the `[n]` in `EXPLABEL[n]`
confirms `ExpReader`'s body re-ran, yet `painterResource` returned its cached painter). Only
`recreate` updates the drawable — and it drops `remember` (Count 4→0) while keeping `rememberSaveable`
(Saved 4).

---

## Table 3 — invalidation candidates (the real question)

Fresh launch each row; seeded outer Count 5 / Saved 5, Inner 2; loader added; then the one command.

| Candidate | string | color | drawable px | outer Count (`remember`) | outer Saved (`rememberSaveable`) | Inner (`remember`, in reader subtree) | state preserved? |
|---|---|---|---|---|---|---|---|
| **a. `ControlledComposition.invalidateAll()`** on all 6 known compositions | `OVERLAY` ✅ | red ✅ | blue ❌ | **5** ✅ | **5** ✅ | **2** ✅ | **YES — all `remember` + `rememberSaveable`** |
| **b. `invalidateGroupsWithKey(ExpReader)`** (key `1674349897`) | `OVERLAY` ✅ | red ✅ | blue ❌ | 5 ✅ | 5 ✅ | **0** ❌ | NO — reader-subtree `remember` reset |
| c. deployer path (§0): `addResourceOverlays` + `restartActivity` = recreate (== Table 2 step d) | `OVERLAY` ✅ | red ✅ | **red** ✅ | 0 ❌ | 4 ✅ | 0 ❌ | NO — `remember` lost (`rememberSaveable` kept) |

**Reflection route for (a)**, logged live and confirmed at this Compose version:
`androidx.compose.runtime.Recomposer._runningRecomposers` (static `MutableStateFlow`) → `.value`
(a `Set<RecomposerInfoImpl>`) → each element's `this$0` field → the `Recomposer` →
`Recomposer._knownCompositions` (`List<ControlledComposition>`, size 6 here) → `invalidateAll()` on
each. (ComposeBridge already reaches `Recomposer.Companion`; the composition list hangs off the
`Recomposer` instance, not the companion. Field names carry an underscore prefix:
`_knownCompositions`, not `knownCompositions`.)

**Why the drawable never updates on a recomposition (a and b both leave it blue):** candidate (b)
*does* reset `ExpReader`'s own subtree `remember` (Inner 2→0) — proving the reset reached inside the
reader — yet the drawable stayed stale. So the decoded vector is **not** cached in the reader's slot
table; `painterResource` resolves it through a **Context/root-scoped cache**
(`LocalImageVectorCache`, provided at the Android composition-locals root and cleared on
configuration change / new Context), which no subtree invalidation touches. Only `recreate` — a new
`Context` and therefore a new cache — re-decodes it.

---

## Recommendation

- **Engine mechanism for a pure resource edit (no edited-function key): trigger
  `ControlledComposition.invalidateAll()` on every composition in
  `Recomposer._runningRecomposers` → `_knownCompositions`.** It is keyless, recomposes *all* readers,
  and preserves `remember` **and** `rememberSaveable` by construction (candidate a). This is strictly
  better than the AOSP deployer's resource path, which recreates the activity (§0), and better than
  reusing our `invalidateGroupsWithKey`/tier-2, both of which lose state. Attach the overlay via a
  **single persistent `ResourcesLoader` + `setProviders`** (the AOSP in-place-update pattern, §0) so
  edits don't stack loaders.
- **Minimum tier per resource kind:**
  - **String, color (and other value resources resolved live from `Resources`): tier-1 =
    `invalidateAll`.** Zero state loss.
  - **Drawables / other decoded assets: tier-3 = activity `recreate`** for v1 (preserves
    `rememberSaveable`, loses `remember`). The `ResourcesLoader` reaches the asset at the `Resources`
    level immediately; the blocker is purely Compose's context-scoped asset cache. A future
    optimization — clear `LocalImageVectorCache` (and the bitmap/image cache) and *then*
    `invalidateAll` — could bring drawables down to tier-1 without a recreate; not attempted here
    (would touch beyond log-and-observe scope).

So resource edits split cleanly: **value edits are a state-preserving tier-1 `invalidateAll`; asset
edits are tier-3 recreate for v1.** Neither is forced onto the fully state-losing tier-2.
