# T05: Gradle plugin — zero-config app setup
Status: DONE
Assignee: agy

## Goal
Users shouldn't hand-edit their app module like `samples/single-module/app/build.gradle.kts`
does today. A Gradle plugin `dev.hotreload` applied to the app module must add everything
the runtime needs (Phase 1 leftover; see docs/phase1.md "Remaining Phase 1 items").

## Spec
Create `gradle-plugin/` as a **standalone Gradle build** (mirror the layout/pins of
`runtime-client/`: its own `settings.gradle.kts`, wrapper NOT needed — it is consumed via
`includeBuild`, same as runtime-client is by the sample).

- Plugin id: `dev.hotreload`, implementation class `dev.hotreload.gradle.HotReloadPlugin`,
  group `dev.hotreload`, version `0.1`. Use `java-gradle-plugin` + `kotlin-dsl`.
- Pins (from `scripts/env.sh` / root project — do not change): Kotlin 2.4.0, AGP 9.2.1,
  Gradle 9.6.1. Compile against AGP `com.android.tools.build:gradle-api:9.2.1` (compileOnly).
- The plugin must fail with a clear message if `com.android.application` is not applied.
- On apply, replicate EXACTLY what the sample's build file hand-configures today
  (`samples/single-module/app/build.gradle.kts` is the reference — read it first):
  1. `dependencies { debugImplementation("dev.hotreload:runtime-client:0.1") }`
  2. `android.packaging.jniLibs.useLegacyPackaging = true`
  3. Kotlin free compiler args (module-wide, same as the sample — per-variant scoping is
     explicitly NOT required for v1):
     `-P plugin:androidx.compose.compiler.plugins.kotlin:generateFunctionKeyMetaAnnotations=true`,
     `-Xlambdas=class`, `-Xsam-conversions=class`, `-Xstring-concat=inline`.
     Use AGP 9 built-in-Kotlin DSL (`kotlin { compilerOptions { freeCompilerArgs.addAll(...) } }`
     equivalent from plugin code). If the built-in-Kotlin extension is not reachable from
     plugin code in AGP 9.2.1, configure via `project.extensions` lookup by name — do NOT
     add a dependency on the standalone KGP.
- Convert `samples/single-module` to use it: add `includeBuild("../../gradle-plugin")` to
  its `settings.gradle.kts` `pluginManagement`, apply `id("dev.hotreload")` in the app
  module, and delete the three hand-configured blocks it replaces (keep the Compose
  BOM/activity deps and the compose plugin — those are the app's own).

## Out of scope
- Publishing to any repository (composite build only).
- Per-variant compiler-arg scoping, release-build behavior, multi-module projects.
- Any change under `runtime-client/`, `engine/`, `protocol/`, `cli/`.

## Acceptance
From repo root, with `source scripts/env.sh`:
1. `(cd samples/single-module && ./gradlew -q :app:assembleDebug)` succeeds.
2. Agent .so present: `unzip -l samples/single-module/app/build/outputs/apk/debug/app-debug.apk | grep -q hotreload_agent` (arm64-v8a lib listed).
3. FunctionKeyMeta emitted: `scripts/extract-keys.sh dev.hotreload.sample.MainActivityKt` prints at least one key.
4. `grep -q useLegacyPackaging samples/single-module/app/build.gradle.kts` returns NON-zero
   (hand-config actually removed), and the app build file contains `id("dev.hotreload")`.
