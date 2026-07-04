# Resource-edits research notes (AOSP Studio deployer)

**Task:** T11. Reading-only map of how Android Studio "Apply Changes" swaps resources
(strings/colors/dimens/drawables) into a **running** app without reinstall, so a later
implementation session in this repo starts from the AOSP code instead of re-researching.
No code in this repo was changed.

**Source browsed:** `platform/tools/base`, branch `mirror-goog-studio-main`, via
`android.googlesource.com` / `cs.android.com`. Paths below are relative to `tools/base/`.
All class/file names are spot-checkable at
`https://cs.android.com/android-studio/platform/tools/base`.

---

## 1. Mechanism

Apply Changes is **not** a runtime resource overlay in the RRO/framework sense and it does
**not** rebuild or reinstall the APK. It has two independent halves:

### Host side (compile/diff, no full rebuild)
1. The Gradle build produces the resource-linked APK the normal way — AGP's
   `processDebugResources` runs **aapt2 compile + link**. The deployer does **not** invoke
   aapt2 itself; it consumes the freshly-built APK.
2. `Deployer` computes a **diff of APK entries** against the previously installed APK.
   Resource/asset entries are pulled out separately from dex by `ApkEntryExtractor` using a
   path filter matching the **`res`** and **`assets`** prefixes (plus `resources.arsc`).
3. The changed resource entries + changed dex are bundled into an **`OverlayUpdate`** and the
   new state is stamped with a new **`OverlayId`** (a content hash of what should now be
   on-device). This is the "optimistic swap" path — `OptimisticApkSwapper.optimisticSwap()`.
   The overlay is a *sidecar set of files*, never a re-linked full package.

So the "compile" question: **partial, incremental** — only entries that changed since the
last deploy travel to the device; there is no separate partial-`aapt2` step in the deployer,
the linking already happened in the normal Gradle resource task.

### Device side (swap into the live process)
1. The native **installer** writes the overlay files into an app-private overlay directory
   (`Sites::AppOverlays(pkg)`, under the app's `code_cache`) — `overlay_install.cc` /
   `overlay_swap.cc`.
2. The installer attaches the JVMTI **agent** to the running process and sends a swap request
   (`SendAgentMessageRequest`) carrying the dex classes, the resource-overlay path, and flags
   `restart_activity` and `structural_redefinition`.
3. The agent's Java instrumentation applies the resources to the live process via the public
   **`ResourcesLoader` / `ResourcesProvider`** APIs (API 30+): it calls
   `ResourcesProvider.loadFromDirectory(<overlay dir with res/ or resources.arsc>)`, wraps the
   providers in a `ResourcesLoader` (`setProviders(...)`), then walks **every live `Resources`
   object** and adds the loader to it. Newly resolved resource lookups then hit the overlay
   first. (Pre-30 builds of the deployer used the older `ResourcesManager` reflection path —
   see §3.)
4. Whether the current Activity is torn down and recreated is decided by the
   `restart_activity` flag set on the host (`codeSwap` vs `fullSwap`, see §3), not by the
   device.

**Net:** host diffs APK entries and ships only the deltas as an overlay; device installs the
overlay directory and injects it into the running process's `Resources` graph through
`ResourcesLoader`. No reinstall, no `pm install`.

---

## 2. Exact AOSP pointers

### Host (JVM, runs in Studio) — `deploy/deployer/src/main/java/com/android/tools/deployer/`
- **`Deployer.java`** — orchestrator. `codeSwap()` (restartActivity = **false**, passes class
  redefiners) and `fullSwap()` (restartActivity = **true**, code + resources) both funnel into
  `optimisticSwap()` / `swap()`. `isBaseInstall()` gates whether an overlay swap is even legal.
- **`OptimisticApkSwapper.java`** — builds the `OverlayUpdate` / `nextOverlayId` and performs
  the optimistic (no-reinstall) swap. `ApkSwapper.java` is the legacy non-optimistic path.
- **`ApkEntryExtractor.java`** — extracts changed archive entries; the **`res`/`assets`** filter
  is what separates resource content from dex.
- **`OverlayId`** (deployer state) — records what the on-device overlay currently contains so
  the next deploy can verify consistency before applying (`isBaseInstall`).
- **`ApkDiffer.java` / `ApkPreInstaller.java`** — diff of APK entries / pre-install staging.
- **`DexComparator.java`, `CachedDexSplitter` / `D8DexSplitter`** — the code half (context; not
  the resource path).

### Installer (native, pushed to device) — `deploy/installer/`
- **`overlay_swap.cc` / `overlay_swap.h`** — `OverlaySwapCommand`; `BuildOverlayUpdateRequest()`
  sets `overlay_path` to `Sites::AppOverlays(pkg)` and populates the files (dex + resources) to
  write; sends `SendAgentMessageRequest` with `restart_activity()` / `structural_redefinition()`;
  `ProcessResponse()` checks for `proto::SwapResponse::OK` and commits the new overlay id.
- **`overlay_install.cc` / `overlay_install.h`** — writes/prepares the overlay directory.
- **`swap.cc` / `swap.h`** — shared swap plumbing (agent attach, IPC).

### Agent (native + Java, runs inside the app) — `deploy/agent/`
- Native `deploy/agent/native/`: `swapper.cc`, `hotswap.cc`, `instrumenter.cc`, plus the
  `jvmti/` glue — attaches, redefines classes, and drives the Java instrumentation.
- Java `deploy/agent/runtime/src/main/java/com/android/tools/deploy/instrument/`:
  - **`ResourceOverlays.java`** — *the resource-swap heart on device*. Locates overlay APK
    directories containing a `res` dir or `resources.arsc`, builds `ResourcesProvider`s via
    `ResourcesProvider.loadFromDirectory(...)`, aggregates them into a `ResourcesLoader`
    (`setProviders`), and iterates `ResourcesManager`'s `mResourceReferences`
    (`ArrayList<WeakReference<Resources>>`) to add the loader to every live `Resources`.
    Reaches `ActivityThread.currentActivityThread()` / `currentPackageName()` via reflection.
  - **`InstrumentationHooks.java`** — `addResourceOverlays()` applies overlays to the
    `ResourcesManager`; `addResourceOverlays(Resources)` re-applies on app startup so overlays
    persist; `handleFindResourceEntry()` rewrites stale `DexPathList$Element` paths;
    `handleDispatchPackageBroadcast*` refresh Application/Activity `LoadedApk` references.
  - **`Overlay.java`**, **`ReflectionHelpers.java`**, **`Breadcrumb.java`**, **`DexUtility.java`**,
    **`LiveLiteralSupport.java`**, **`Phase.java`** (supporting).

(≥5 spot-checkable names: `Deployer.java`, `OptimisticApkSwapper.java`, `ApkEntryExtractor.java`,
`overlay_swap.cc`, `overlay_install.cc`, `ResourceOverlays.java`, `InstrumentationHooks.java`.)

---

## 3. Constraints

### Minimum API
- **Code swap** (JVMTI `RedefineClasses`): **API 26** (Android 8.0 Oreo) — this is the floor
  Studio advertises for Apply Changes overall.
- **Runtime resource swap via `ResourcesLoader` / `ResourcesProvider`**: **API 30** (Android 11).
  These classes did not exist before R, so the `ResourceOverlays.java` path above is 30+. On
  API 26–29 the deployer used the older reflection path into framework `ResourcesManager`
  (append asset paths / rebuild `ResourcesImpl` for affected `ResourcesKey`s) — this is the
  approach `docs/PLAN.md` Phase 2 refers to as "swap via `ResourcesManager` reflection". It is
  fragile across OEM/framework versions, which is a large part of why Google moved to
  `ResourcesLoader` once R shipped it as public API.

### What works live vs what forces a restart / reinstall
- **Apply Code Changes** (`codeSwap`, no restart): method-body-only bytecode changes. No new
  classes/methods/fields, no signature changes. Purely code; no resource guarantee.
- **Apply Changes and Restart Activity** (`fullSwap`, `restart_activity=true`): code **and**
  resources. The current Activity is torn down and recreated so views re-inflate and re-resolve
  against the overlay. **Any resource change that must show up in already-inflated UI needs this
  tier** — a `Resources`-level overlay swaps what *new* lookups return, but existing inflated
  Views/Drawables/theme values are not retroactively rewritten, so a recreate is the reliable
  way to surface changed strings/colors/dimens/drawables.
- **Forces a full reinstall (Apply Changes refuses / falls back to Run)**:
  - `AndroidManifest.xml` changes (components, permissions, metadata).
  - Native library (`.so`) changes.
  - Structural changes the swap can't represent (added/removed classes, methods, or fields,
    changed signatures) when not routed through structural redefinition.
  - Changes to resources referenced by the manifest / that alter the manifest's resource table.

---

## 4. Mapping onto our stack

Our architecture: host engine ↔ on-device `PatchServer` over an abstract-namespace Unix socket,
protocol **v2** (`protocol/Protocol.kt`): opcodes `PING/REDEFINE/INJECT_DEX/INVALIDATE/RESET/
GET_ERRORS`, three-tier Compose invalidation on the client. A resource-swap would slot in as a
new opcode plus a host build step. Prose sketch only — nothing below is implemented.

### Host side (engine)
- Watch `res/` (and merged/generated res) alongside the existing source watch.
- On a resource change, invoke Gradle's `processDebugResources` (aapt2 compile+link) to get the
  freshly-linked APK — mirror the deployer: **do not hand-roll aapt2**.
- Diff APK entries against the last-deployed set, keep only changed `res/` + `resources.arsc`
  entries (our analogue of `ApkEntryExtractor`'s `res`/`assets` filter). We do **not** need the
  full `OverlayId` bookkeeping for a first cut — a monotonically-numbered overlay dir suffices.
- Send the changed entries as bytes over the socket.

### Device side (runtime-client / PatchServer)
- A new request, e.g. **`LoadResources(overlayName, bytes)`** (hypothetical opcode `0x07`,
  response `ACK`/`FAILURE`) — the client writes the bytes into an app-private overlay dir under
  `codeCacheDir` (same idea as `Sites::AppOverlays`).
- Client calls `ResourcesProvider.loadFromDirectory(dir)` → `ResourcesLoader.setProviders(...)`,
  then walks live `Resources` and `Resources.addLoaders(loader)` — a direct port of
  `ResourceOverlays.java`. Requires **API 30+**; below that we either no-op or use the framework
  `ResourcesManager` reflection fallback (out of scope for a first cut).
- Then trigger a **tier-3 activity recreate** (we already own that tier as the third Compose
  invalidation step) so inflated UI re-resolves against the overlay. For pure Compose UI that
  reads resources through `LocalContext`/`stringResource`, a `Recomposer` invalidation (tier-1)
  *might* suffice without recreate — but Compose caches some resource reads, so recreate is the
  safe default.

### Genuinely unknown until prototyped
1. **Does a Compose-only app actually need activity recreate, or does tier-1/tier-2 invalidation
   re-read the overlayed resources?** `stringResource`/`painterResource` go through
   `LocalContext.resources`; whether adding a loader to that `Resources` instance is picked up by
   a plain recomposition (vs. requiring recreate) is the key empirical question.
2. **Which live `Resources` instances to enumerate** without the framework's `mResourceReferences`
   being stable across API 30→latest (that field/reflection is the brittle part).
3. Interaction of resource-ID assignment: aapt2 can renumber IDs when resources are
   added/removed; a change that renumbers IDs may desync already-loaded dex referencing old IDs
   (deployer sidesteps this by shipping the matching dex in the same swap — we'd need the same
   coupling between a resource change and any code referencing new IDs).

---

## 5. Feasibility verdict

**Effort: medium.** The host side is small (reuse the existing Gradle/watch plumbing + one
aapt2-diff step + one new opcode). The device side is a fairly direct port of
`ResourceOverlays.java` — ~one class using `ResourcesLoader`/`ResourcesProvider` + iterating live
`Resources` — and we already own the activity-recreate tier. The protocol change is one request
type. What pushes it above "small" is the API-30 floor + reflection brittleness and the coupling
to resource-ID changes.

**Single riskiest unknown:** whether, for a Compose-only UI, adding a `ResourcesLoader` to the
live `Resources` plus a cheap recomposition/recreate actually makes changed strings/colors/
drawables appear — i.e. whether Compose's resource caching forces a full activity recreate every
time (acceptable but state-losing) or whether a lighter tier can surface resource edits. Everything
else has a proven AOSP reference implementation; this is the one behavior with no guarantee until
we run it on-device.
