# Multi-module watching & diffing — design (v1)

Inputs: `docs/multi-module-ground-truth.md` (T10 — every fact referenced here was measured there),
T15 (plugin supports app/library/jvm), T16/T17 (whole-tree `invalidateAll` mechanism).
Testbed: `samples/multi-module/` (`:app → :feature → :core`).

## Decisions

### D1. Modules are declared, not discovered
`--module app,feature,core` — comma list, **first entry is the app module** (holds the
applicationId, the APK, the resources baseline). Default stays `app`, so single-module
behavior and the CLI surface are unchanged. Parsing `settings.gradle.kts` or querying the
Tooling API project model is deferred: it adds startup cost and failure modes for zero v1
value (the user knows their modules). Nested paths use `/` (`libs/core` → `:libs:core`).

### D2. Layout is probed from the build output, not from build files
Ground truth §2: the real split is **AGP vs pure-JVM**, not app-vs-library —
`com.android.application` and `com.android.library` share
`build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes`; `kotlin-jvm` uses
`build/classes/kotlin/main`. Task names follow the same split (`compileDebugKotlin` vs
`compileKotlin`, §3). So after the **initial build**, each module's layout is resolved by
probing which classes dir exists (AGP wins if both — a stale `build/classes` can survive a
plugin migration). No build-file parsing, no extra Gradle round-trip; fail loud when neither
dir exists (module not in the app's dependency graph, or has no Kotlin). The app module must
resolve to AGP.

`ModuleSpec` derives everything from (name, layout): gradlePath, sourceRoots, classesDir,
compileTask, resDir (AGP only).

### D3. One compile task per save: the app module's `compileDebugKotlin`
Ground truth §3: an ABI change fans out one hop to direct dependents; a body edit recompiles
only its module. Rather than mapping changed-file→module and re-implementing that fan-out
(compile the edited module, parse which dependent tasks also ran, re-scan those), we always
invoke `:<app>:compileDebugKotlin`. Gradle's task graph runs exactly the right upstream
compile tasks and skips the rest (compile avoidance) — the fan-out logic already exists in
Gradle; duplicating it in the engine is pure risk. The initial build uses the same task, which
also guarantees every module's classes dir exists before the layout probe (D2).

Cost: configuration + up-to-date checks over the whole graph on every save. §6 measured
sub-second per-module compiles through the slower `./gradlew` client; the warm Tooling API
connection stays within the Phase-1 latency gate. Revisit only if a large real project shows
the up-to-date sweep dominating.

### D4. One merged snapshot across all modules' classes dirs
`ClassSnapshot.scan(dirs)` walks every module's classes dir into a single map keyed by JVM
internal name (entries carry their file path, so dexing is module-agnostic). Diff/classify/
plan are unchanged — the Classifier never cared where a class came from. Cross-module
fan-out is handled for free: when a `:core` ABI change recompiles `:feature`, the merged
re-scan sees both modules' changed classes in the same batch and classifies them together
(typically Rebuild — an ABI change means removed/changed member ids, which is already the
correct verdict).

A class present in two modules' outputs would make the diff ambiguous → fail loud at startup.

### D5. Redefines with no compose keys fall back to whole-tree `invalidateAll` (protocol v4)
`:core` is pure Kotlin: a body edit there redefines a class with **no** FunctionKeyMeta, so
`plan.invalidateKeys` is empty and nothing recomposes — the swap lands but the UI stays stale
until the next state change. (Latent in single-module too — any non-composable helper edit —
but multi-module makes it the *common* case.) Fix: new request `InvalidateAll` (opcode 0x08,
protocol **v4**) → `ComposeBridge.invalidateAllCompositions()` on the main thread — the exact
T16 mechanism LoadResources already uses: keyless, preserves `remember` AND `rememberSaveable`.
Engine rule: if a hot-swap applied (inject/redefine happened) and `invalidateKeys` is empty,
send `InvalidateAll` instead of skipping invalidation. Targeted keys stay the fast path when
they exist.

### D6. Resources: watch every AGP module's `res/`, keep one merged baseline
AGP merges library resources into the app's table, so the T17 pipeline
(`:<app>:assembleDebug` → extract arsc+res from the app APK → overlay) already produces the
right bytes for a library `res/values` edit — only the *inputs* were single-module. Changes:
watch all AGP modules' resDirs; the ID-stability guard scans the **union** of `(type,name)`
across those resDirs (dedup matches merge semantics: an app resource overriding a library
resource of the same type/name is one merged entry, so the union set tracks exactly what
governs aapt2 ID stability). assembleTask/apkDir come from the app module.

### D7. FunctionKeyMeta in library modules — rely on it, verify live
Ground truth §5 extracted a real key from `:feature`'s classes even though only `:app` gets
the compose `-P` arg (T15 note), so library composables are expected to hot-reload with
targeted invalidation unchanged. Validation must include a `:feature` composable body edit;
if the runtime key ever mismatches, D5's `InvalidateAll` is the fallback (correct, just
whole-tree).

## Out of scope (v1)
- Auto-discovery of modules / variants other than debug (D1).
- Per-module compile-task selection + fired-task tracking as an optimization over D3.
- Cross-module ABI-change hot-swap: stays a correct "rebuild required" report (D4).
- KMP source sets; only `src/main/{kotlin,java,res}` roots.

## Live validation (2026-07-05, emulator API 36, same PID throughout each run)
All on `samples/multi-module` via `hotreload watch --module app,feature,core`:
- Probe: `:app`/`:feature` → AGP, `:core` → JVM; merged snapshot 14 classes. Ping shows protocol 4.
- `:core` body edit (`"core says:"`→`"core value:"`): **1 redefined, whole tree invalidated — 1625ms**;
  on screen instantly, App/Feature counters preserved (D5 confirmed: `InvalidateAll` keeps
  remember state).
- `:feature` composable body edit: **2 redefined, 2 groups invalidated — 1711ms** (D7 confirmed:
  library FunctionKeyMeta keys match runtime; targeted invalidation, edited subtree's remember
  resets = normal Live Edit semantics, sibling subtrees keep state).
- `:core` ABI change (added default param): correct `cannot hot-swap … signatures changed` +
  `:app:installDebug` notice; baseline kept; revert-save → `no bytecode changes` (D3/D4 fan-out
  path exercised: Gradle recompiled `:feature` too, merged diff classified both).
- `:feature` structural add (new @Composable + call site, one save): **1 injected, 3 redefined
  (structural) — 2455ms**; new UI on screen.
- `:feature` `res/values` string edit (testbed added: `feature/src/main/res/values/strings.xml` +
  `androidResources = true` — off by default for AGP 9 libraries): **resource-swapped 1936ms**,
  state preserved (D6 confirmed). Guard: adding `string/feature_extra` in `:feature` →
  `resource set changed … reinstall` (union baseline works cross-module).
- Single-module regression: e2e **10/10 PASS, 117s**, no leaked watcher.

## Touch list
protocol v4: `Protocol.kt` (+`InvalidateAll`), `Wire.kt` (both codecs) · runtime-client:
`PatchServer.kt` dispatch arm · engine: `DeviceClient.invalidateAll()`, `ModuleSpec` (new),
`WatchSession` (Config→modules, probe, merged snapshot, watch roots, fallback), `ClassSnapshot.scan(List)`,
`ResourceSwapper` (app-module tasks, union guard) · cli: `--module` comma list · runtime-client
reinstall required on device (Ping shows protocol 4).
