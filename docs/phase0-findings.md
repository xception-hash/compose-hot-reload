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
- **ART RedefineClasses takes single-class DEX bytes** (not JVM .class format). `d8 --no-desugaring --min-api 30 <one .class>` output works as-is → returned `JVMTI_ERROR_NONE`.

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
lambda/SAM classes instead of invokedynamic; then a patch built with `d8 --no-desugaring` references
the same synthetic shapes as the installed APK. (Without this, D8 desugars indy lambdas into
`$$ExternalSyntheticLambda*` classes whose names won't match across builds.)

### Group-key stability
Body-only edit left all FunctionKeyMeta keys unchanged (only endOffset shifted) — key survives body
edits, as the classifier design assumes.

### Spike transport (not for the product)
adb push dex to `/sdcard/Android/data/<pkg>/files/` + exported debug `BroadcastReceiver`
(`am broadcast -n dev.hotreload.toy/.PatchReceiver -a dev.hotreload.toy.PATCH --es file patch.dex
--es cls <fqcn> --ei key <key> [--ez structural true] [--ez reset true]`).

## Experiment C — structural redefinition: (pending)

## Experiment D — changed lambda / new-class path: (pending)

## Experiment E — HotReloader reset tier: (pending)
