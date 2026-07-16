# Phase 0 spike — findings

Working recipe + every flag/reflection call, at exact pinned versions. Companion to `docs/PLAN.md` Phase 0.

## Pinned versions (resolved 2026-07-03, latest stable)

| Tool | Version | Notes |
|---|---|---|
| Kotlin | **2.4.0** | Compose compiler is bundled with Kotlin since 2.0 |
| AGP | **9.2.1** | Built-in Kotlin (no `org.jetbrains.kotlin.android` plugin). KGP pinned to 2.4.0 via root `buildscript { classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.0") }` — AGP 9 otherwise bundles KGP 2.2.10 |
| Gradle | **9.6.1** | AGP 9.2 needs ≥ 9.1 |
| Compose BOM | **2026.06.01** | |
| activity-compose | **1.13.0** | |
| Build-tools | **36.0.0** | `d8`, `dexdump` |
| NDK | **28.2.13676358** | AGP 9's default NDK |
| CMake | 4.1.2 | |
| JDK | Android Studio JBR (OpenJDK 21) | no system JDK on this machine |
| Device | emulator `Medium_Phone`, API 36, arm64, google_apis_playstore | ≥ API 30 floor for structural redefinition |

Toy app: `spike/toy-app` (package `dev.hotreload.toy`, minSdk 30, targetSdk/compileSdk 36).

## Experiment A — FunctionKeyMeta extraction: ✅ WORKS

**Compiler flag** (exact name valid in Kotlin 2.4.0's Compose compiler):

```kotlin
// app/build.gradle.kts — with AGP 9 built-in Kotlin this is the top-level kotlin {} block
kotlin {
    compilerOptions {
        freeCompilerArgs.addAll(
            "-P",
            "plugin:androidx.compose.compiler.plugins.kotlin:generateFunctionKeyMetaAnnotations=true",
        )
    }
}
```

- There is also a **deprecated** `generateFunctionKeyMetaClasses` option (jar string: "Deprecated. Use 'generateFunctionKeyMetaAnnotation' instead").
- Result: every `@Composable` function in the compiled `.class` gets a `RuntimeInvisibleAnnotation`
  `androidx.compose.runtime.internal.FunctionKeyMeta(key=<Int>, startOffset=<Int>, endOffset=<Int>)`.
  Offsets are source-file character offsets — usable to map key → function even without parsing names.
- Extracted from the toy app (`MainActivityKt.class`):
  - `MainScreen` key=796097800, `Counter` key=-96578675, `Greeting` key=-2116809900.

**How to extract (host side):** read the annotation from the compiled `.class` (javap for spikes; ASM in the real engine):

```
javap -v .../built_in_kotlinc/debug/compileDebugKotlin/classes/dev/hotreload/toy/MainActivityKt.class \
  | grep -A4 FunctionKeyMeta
```

**Gotchas:**
- AGP 9 built-in Kotlin puts `.class` output at
  `app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/` (not `tmp/kotlin-classes/`).
- **D8 strips the annotations from the dex** (CLASS retention). Not a problem: the engine reads keys
  from host-side `.class` files, never from the device. (Do not plan on reading FunctionKeyMeta via
  reflection on device.)
- D8 desugars composable lambdas into `...$$ExternalSyntheticLambda0` classes with
  `com.android.tools.r8.annotations.LambdaMethod` runtime annotations pointing back at the holder
  (`ComposableSingletons$MainActivityKt`). Relevant to the Experiment D lambda path.

## Experiment B — JVMTI body swap + invalidateGroupsWithKey: ✅ WORKS

Full loop proven on emulator (API 36, arm64): edit composable body → recompile → dex → redefine →
targeted invalidate → **UI updated, same PID, `remember` counter preserved**. Script: `spike/scripts/hotswap.sh`.

### Agent (spike/toy-app/app/src/main/cpp/hotreload_agent.cpp)
- `jvmti.h` vendored from AOSP `platform/art/openjdkjvmti/include/jvmti.h` (NDK doesn't ship one).
- `Agent_OnAttach`: `GetEnv(JVMTI_VERSION_1_2)` then `AddCapabilities(can_redefine_classes, can_retransform_classes)` → both granted (err 0) on a debuggable app, attach-time.
- **ART RedefineClasses takes DEX bytes** (not JVM `.class` format). The original API-30
  spike proved `d8 --no-desugaring --min-api 30 <one .class>` works. The product now keeps D8
  desugaring enabled, uses the installed APK's minSdk, and supplies watched class outputs as
  classpath so APKs below API 24 and their patches agree on interface `$-CC` helper ownership.

### Agent attach (app side)
- `Debug.attachJvmtiAgent(lib, options, classLoader)` **rejects any '=' in the lib string**
  (`IllegalArgumentException` from Preconditions) — and `nativeLibraryDir` paths contain base64 `==`.
  Fix: pass the **bare filename** `libhotreload_agent.so` + the app classloader; the loader's native
  library namespace resolves it. Requires `packaging { jniLibs { useLegacyPackaging = true } }`.
- Then `System.loadLibrary("hotreload_agent")` to bind the JNI natives.

### JVMTI extensions present on API 36 emulator (discovered via GetExtensionFunctions)
- `com.android.art.class.structurally_redefine_classes` (2 params — same shape as RedefineClasses) ✅ found
- `com.android.art.class.is_structurally_modifiable_class` — pre-flight check, use in classifier
- `com.android.art.classloader.add_to_dex_class_loader_in_memory` — **injects dex into an existing
  classloader**; this is the clean mechanism for the new-class/lambda path (better than
  InMemoryDexClassLoader parent games)
- `com.android.art.misc.get_last_error_message` / `clear_last_error_message` — diagnostics for failed redefines

### Compose runtime reflection surface (Compose BOM 2026.06.01, runtime aar; internal → `$runtime` name mangling)
On `androidx.compose.runtime.Recomposer$Companion` (instance via static `Companion` field):
- `void invalidateGroupsWithKey$runtime(int)` ← tier-1 targeted invalidation, **works**
- `Object saveStateAndDisposeForHotReload$runtime()` / `void loadStateAndComposeForHotReload$runtime(Object)` ← tier-2 reset
- `void setHotReloadEnabled$runtime(boolean)`
- `List getCurrentErrors$runtime()` / `List getRecomposerErrors$runtime()` / `clearErrors$runtime()` ← error-recovery hooks for auto-escalation
- `StateFlow getRunningRecomposers()`

### Determinism flags
Compile the app (and every patch) with `-Xlambdas=class -Xsam-conversions=class` so kotlinc emits
lambda/SAM classes instead of invokedynamic; then patch D8 does not invent unstable lambda shapes.
(Without this, D8 desugars indy lambdas into
`$$ExternalSyntheticLambda*` classes whose names won't match across builds.)

### Group-key stability
Body-only edit left all FunctionKeyMeta keys unchanged (only endOffset shifted) — key survives body
edits, as the classifier design assumes.

### Spike transport (not for the product)
adb push dex to `/sdcard/Android/data/<pkg>/files/` + exported debug `BroadcastReceiver`
(`am broadcast -n dev.hotreload.toy/.PatchReceiver -a dev.hotreload.toy.PATCH --es file patch.dex
--es cls <fqcn> --ei key <key> [--ez structural true] [--ez reset true]`).

## Experiment C — structural redefinition: ✅ WORKS

Added a brand-new `@Composable fun Badge()` to `MainActivityKt` (new static method) + a call to it
from `Greeting`. `com.android.art.class.structurally_redefine_classes` (same argument shape as
`RedefineClasses`) → 0. Targeted `invalidateGroupsWithKey(Greeting)` recomposed and the **slot table
inserted the new group naturally** — new composable rendered, same PID, `remember` state intact.
Non-structural `RedefineClasses` on the same patch fails with **error 63**
(`Total number of declared methods changed from 7 to 8` via `get_last_error_message`) — classifier
must route added-member patches to the structural call.

## Experiment D — new-class injection (lambda path): ✅ WORKS (file variant only)

Whole new file `NewFeature.kt` (composable `NewBanner`) absent from the installed APK, made live:
1. d8 its classes (incl. the `-Xlambdas=class` synthetic `NewFeatureKt$NewBanner$1`) into one dex.
2. **`com.android.art.classloader.add_to_dex_class_loader_in_memory` FAILS on API 36**: err 103,
   `SecurityException: Writable dex file '/proc/self/fd/N' is not allowed` — the extension's memfd
   trips ART's writable-dex check. Do not rely on it.
3. Working path: copy dex to `context.codeCacheDir`, `setWritable(false)`, then
   **`com.android.art.classloader.add_to_dex_class_loader`** (loader, path) → 0. Classes join the
   app classloader itself, so redefined code resolves them with no classloader tricks.
4. Structurally redefine the calling class → new composable from the injected class renders;
   same PID; `remember` state intact.

## Experiment E — HotReloader reset tier: ⚠️ WORKS, but preserves NO composition state

`saveStateAndDisposeForHotReload$runtime()` → token (ArrayList) → `loadStateAndComposeForHotReload$runtime(token)`
rebuilds every composition without process death — including code arriving from redefines/injection.
**Empirically (even with `setHotReloadEnabled$runtime(true)` called before first composition):
both `remember` AND `rememberSaveable` reset to initial values.** The token holds composition
content lambdas, not state: this tier = "recreate composition in place".

Tier semantics for the engine (revised):
| Tier | Mechanism | Survives |
|---|---|---|
| 1 | redefine + `invalidateGroupsWithKey` | everything (`remember`, all state) |
| 2 | HotReloader save/dispose/load | ViewModels, singletons, activity — **no composition state** |
| 3 | activity recreate | ViewModels + `rememberSaveable` (normal config-change semantics) |

Note tier 3 preserves *more* composition state than tier 2; tier 2's value is speed (no lifecycle
churn) when group structure changed too much for tier 1.

## Phase 0 exit status: COMPLETE (2026-07-03)

Every mechanism the architecture depends on is proven on device at the pinned versions. Remaining
research handled in later phases: group-key renumbering detection (classifier work, Phase 1/2) and
the interpreter tier (Phase 6). Source in `spike/toy-app` reflects the final state of all
experiments; `spike/scripts/hotswap.sh` is the one-shot loop.
