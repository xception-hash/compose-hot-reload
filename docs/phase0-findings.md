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

## Experiment B — JVMTI body swap + invalidateGroupsWithKey: (in progress)

## Experiment C — structural redefinition: (pending)

## Experiment D — changed lambda / new-class path: (pending)

## Experiment E — HotReloader reset tier: (pending)
