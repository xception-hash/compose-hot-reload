# T03: runtime-client AAR build + sample wiring
Status: DONE (2026-07-04)
Assignee: agy

> Outcome: agy scaffolded everything per spec, then correctly stopped at a compile error
> in ComposeBridge.kt — a real bug in the provided source (`?: return Log.e(...)`:
> Int-returning expression in a `return` from a Unit function; also `Method.invoke` on a
> void method returns null, so the elvis "missing method" branch fired on success too).
> Claude fixed ComposeBridge.kt and ran all acceptance commands: PASS. AAR artifact is
> `runtime-client-debug.aar` (project renamed via settings projectDir mapping, as the
> spec anticipated).

## Goal
Make the runtime-client compile and ship: a standalone Android library build at
`runtime-client/` producing an AAR that packages the JVMTI agent (.so), the client
Kotlin sources, and the protocol sources; then wire `samples/single-module` to consume
it. All core sources already exist (written by Claude) — this task is build plumbing only.

## Spec

### 1. Standalone Gradle build at `runtime-client/`
Mirror `samples/single-module` conventions exactly (same wrapper, versions, repo blocks):
- Copy `gradlew`, `gradlew.bat`, `gradle/wrapper/` from `samples/single-module/` (Gradle 9.6.1).
- `gradle.properties`: copy from `samples/single-module/`.
- `local.properties`: copy from `samples/single-module/` (gitignored; do not commit).
- `settings.gradle.kts`: same pluginManagement/dependencyResolutionManagement repos as the
  sample; `rootProject.name = "runtime-client"`; include the existing `lib/` directory as a
  project named `:runtime-client`:
  ```kotlin
  include(":runtime-client")
  project(":runtime-client").projectDir = file("lib")
  ```
- Root `build.gradle.kts`: same pattern as sample (AGP 9.2.1, Kotlin 2.4.0 pinned the same
  way the sample's root build does it — replicate whatever the sample root does).

### 2. `runtime-client/lib/build.gradle.kts` — Android library module
- Plugin: `com.android.library`. NO compose plugin (the client uses only reflection).
- `namespace = "dev.hotreload.client"`, `compileSdk = 36`, `minSdk = 30`.
- `group = "dev.hotreload"`, `version = "0.1"` (top level, for composite-build substitution).
- Native build:
  ```kotlin
  ndkVersion = "28.2.13676358"
  externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt") } }
  defaultConfig { ndk { abiFilters += listOf("arm64-v8a", "x86_64") } }
  ```
- Compile the protocol sources into the AAR (single source of truth, no publishing):
  ```kotlin
  sourceSets { getByName("main") { kotlin.srcDir("../../protocol/src/main/kotlin") } }
  ```
- Dependencies: `implementation("androidx.startup:startup-runtime:1.2.0")` only.
  (If 1.2.0 does not resolve, use the latest stable androidx.startup and note it in the PR.)

### 3. Wire `samples/single-module` to consume the AAR
In `samples/single-module/settings.gradle.kts`:
```kotlin
includeBuild("../../runtime-client")
```
In `samples/single-module/app/build.gradle.kts`:
- Add `debugImplementation("dev.hotreload:runtime-client:0.1")` (composite build substitutes
  the local project — debug only, never in release).
- In `packaging { jniLibs { ... } }`: REMOVE the `excludes += "**/*.so"` line (the agent .so
  must ship now); KEEP `useLegacyPackaging = true` (required for attachJvmtiAgent by bare
  lib name — see comment there).

### 4. Files that already exist — do not modify
- `runtime-client/lib/src/main/cpp/{CMakeLists.txt,hotreload_agent.cpp,include/jvmti.h}`
- `runtime-client/lib/src/main/kotlin/dev/hotreload/client/*.kt`
- `runtime-client/lib/src/main/AndroidManifest.xml`
- `protocol/**`
If something in them blocks the build, STOP and report the exact error instead of editing.

## Out of scope
- No changes to `protocol/`, `engine/`, `cli/`, `spike/`, root build, or any Kotlin/C++ source.
- No publishing (maven-publish) setup.
- No release-variant hardening; debug is what matters.

## Acceptance
From repo root, all must pass:
```bash
(cd runtime-client && ./gradlew -q :runtime-client:assembleDebug)
unzip -l runtime-client/lib/build/outputs/aar/lib-debug.aar | grep -E "jni/arm64-v8a/libhotreload_agent.so|classes.jar"
unzip -p runtime-client/lib/build/outputs/aar/lib-debug.aar classes.jar | jar tv 2>/dev/null | grep -E "dev/hotreload/client/PatchServer.class|dev/hotreload/protocol/Wire.class" || { unzip -p runtime-client/lib/build/outputs/aar/lib-debug.aar classes.jar > /tmp/rc-classes.jar && unzip -l /tmp/rc-classes.jar | grep -E "PatchServer.class|protocol/Wire.class"; }
(cd samples/single-module && ./gradlew -q :app:assembleDebug)
unzip -l samples/single-module/app/build/outputs/apk/debug/app-debug.apk | grep "lib/arm64-v8a/libhotreload_agent.so"
```
(If the AAR output filename differs, e.g. `runtime-client-debug.aar`, adjust the grep paths
accordingly and note it — content checks are what matter.)
