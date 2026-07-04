# T14 — Does Compose UI pick up a `ResourcesLoader` overlay without activity recreate?

**Question (the single riskiest unknown from `resource-edits-notes.md` §5):** after adding a
`ResourcesLoader` overlay to the live `Resources`, does a Compose `stringResource(...)` show the
new value on (a) nothing/plain redraw, (b) a plain recomposition, (c) a tier-2 reset, or only on
(d) a full activity recreate?

**Answer: tier-1 (a plain recomposition) is sufficient.** No activity recreate is needed to
surface a changed *string* resource in Compose. See conclusion for the caveat on Views/drawables.

Ran on-device 2026-07-04. Emulator: `Medium_Phone_API_36.0` (API 36). Sample:
`samples/single-module` (`dev.hotreload.sample`, minSdk 30 — `ResourcesLoader` floor is API 30).
All sample edits were temporary and have been reverted (`git status` clean except this doc).

---

## Setup (reproduce)

1. **Instrument the sample (temporary).** Added `res/values/strings.xml` with
   `exp_label = "ORIGINAL RES"`; rendered it via a composable that also reads the counter state so
   a tap forces it to recompose:
   ```kotlin
   @Composable fun ExpLabel(count: Int) =
       Text("EXP[$count]: " + stringResource(R.string.exp_label))
   ```
   Added a broadcast-receiver hook (`action = dev.hotreload.EXP`, registered `RECEIVER_EXPORTED`)
   with `cmd = add | log | recreate`:
   - `add`: `ResourcesProvider.loadFromDirectory(codeCacheDir/exp-overlay, null)` →
     `ResourcesLoader().apply { addProvider(p) }` → `resources.addLoaders(loader)` **and**
     `application.resources.addLoaders(loader)`, then logs `getString(R.string.exp_label)` for both.
   - `log`: logs `getString` only. `recreate`: calls `activity.recreate()`.
   - Import note: the classes are `android.content.res.loader.{ResourcesLoader,ResourcesProvider}`
     — **not** `android.content.res.*` (a wrong-package import fails to compile).

2. **Build + install the ORIGINAL build** (`getString` at launch logged `ORIGINAL RES`).

3. **Build the overlay.** Flipped `exp_label` → `"OVERLAY RES"`, `./gradlew :app:assembleDebug`
   (did **not** reinstall). Unzipped `resources.arsc` + `res/` from that APK into a dir, pushed to
   `/data/local/tmp/exp-overlay`, then `run-as dev.hotreload.sample` copied it into the app's
   `code_cache/exp-overlay` (app-private, app-readable). Restored the source string to
   `"ORIGINAL RES"` — the *installed, running* app is still the ORIGINAL build; only the on-device
   overlay dir carries `OVERLAY RES`. Resource-table structure is identical between the two builds
   (only the string *value* differs), so `R.string.exp_label`'s ID is stable across them.

Broadcasts used to drive each step:
```
adb shell am broadcast -a dev.hotreload.EXP -p dev.hotreload.sample --es cmd add
adb shell am broadcast -a dev.hotreload.EXP -p dev.hotreload.sample --es cmd log
adb shell am broadcast -a dev.hotreload.EXP -p dev.hotreload.sample --es cmd recreate
scripts/taps.sh 1     # taps the "Count" button -> recomposes ExpLabel
scripts/ui-state.sh   # reads on-screen text (PKG=dev.hotreload.sample)
```

---

## Step -> observation

`getString` = value returned by `resources.getString(R.string.exp_label)` logged in-process
(bypasses Compose). On-screen = text read by `scripts/ui-state.sh` (what Compose actually shows).

| Step | Action | `getString` (direct) | On-screen (`ui-state.sh`) |
|---|---|---|---|
| launch | ORIGINAL build starts, no loader | `ORIGINAL RES` | `EXP[0]: ORIGINAL RES` |
| **a** | `add` loader, **no** other action | **`OVERLAY RES`** (activity + application) | `EXP[0]: ORIGINAL RES` — **unchanged** |
| **b** | tap counter (plain recomposition) | `OVERLAY RES` | **`EXP[1]: OVERLAY RES`** ✅ |
| c | tier-2 HotReloader reset | *skipped* | *skipped* (see below) |
| **d** | `activity.recreate()` | `OVERLAY RES` | **`EXP[0]: OVERLAY RES`** ✅ (count reset = fresh activity) |

Tier-2 (c) was **skipped**: driving a HotReloader reset needs a live engine/`PatchServer`
round-trip (out of scope here, and steps a/b/d already bound the answer — b succeeding makes any
higher tier unnecessary for the string case).

---

## What each step proves

- **Step a** is the crux. `addLoaders` takes effect immediately: a *fresh* `getString` on the very
  same `Resources` instance Compose reads (`activity.resources` == `LocalContext.current.resources`)
  returns `OVERLAY RES`. But the already-composed `ExpLabel` does **not** update on its own —
  `addLoaders` does not dispatch a configuration change, so nothing invalidates the composition.
  This rules out the "loader not applied to the right instance" failure mode: the direct
  `getString` and the on-screen text diverge *only* because Compose hasn't recomposed yet.
- **Step b** closes it: any recomposition of the reader re-invokes `stringResource`, which re-calls
  `getString` (Compose's `stringResource` is `@ReadOnlyComposable` — it is not memoized across
  recompositions), so the overlay value reaches the screen. A counter tap is just a stand-in for
  "the composable recomposes"; the engine's tier-1 invalidation recomposes *all* readers and would
  surface the edit app-wide the same way.
- **Step d** shows recreate also works and, notably, that the loader **persisted** across recreate —
  because it was added to `application.resources`, the recreated activity's `Resources` inherited
  it (no re-add needed in this single-activity case). AOSP's `InstrumentationHooks.addResourceOverlays`
  re-applies on startup as a safety net for cases where it does *not* persist; we did not need it here.

---

## Conclusion

**Resource (string) edits need only tier-1.** For a Compose-only UI, adding a `ResourcesLoader` to
the live `Resources` makes `getString` return the new value instantly; the *only* remaining
requirement is that the composable reading the string recomposes — which the engine's existing
tier-1 (recomposition) invalidation already does on every reload. **Activity recreate is not
required to surface changed strings**, so resource edits do not have to be the state-losing tier-3
path. This removes the single riskiest unknown blocking a resource-edit feature.

**Caveats / not yet tested:**
- This covers `stringResource`. `painterResource`/`imageResource` cache the decoded asset
  (`ImageBitmap`) keyed by id inside Compose, and already-inflated Android `View`s / theme values
  are not retroactively rewritten — those may still need a recreate (matches
  `resource-edits-notes.md` §3). Worth a follow-up experiment before shipping drawable/color edits.
- Only the value of an existing resource was changed (stable IDs). Adding/removing resources can
  make aapt2 renumber IDs and desync already-loaded dex — the deployer sidesteps this by shipping
  the matching dex in the same swap (§4, unknown #3); still out of scope.
- Loader-persistence across recreate was observed for a single-activity app via
  `application.resources`; multi-activity / process-death behavior not exercised.
