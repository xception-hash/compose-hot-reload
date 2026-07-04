# T15: dev.hotreload plugin — support com.android.library and kotlin-jvm modules
Status: DONE (Opus, 2026-07-04). All 6 acceptance checks pass: multi-module + single-module build,
        javap flag-proof clean in app/feature/core, hand-wiring deleted, e2e 7/7, no leaked watcher.
Assignee: agy (mechanical Gradle work; ground truth is all in docs/multi-module-ground-truth.md)

## Goal
Close the gap found in T10 §1: the `dev.hotreload` plugin only handles `com.android.application`,
so `samples/multi-module`'s `:feature`/`:core` hand-wire the three compiler flags with
`// TODO: absorbed by dev.hotreload plugin once multi-module lands` comments. Make the plugin
applicable to all three module types and delete the hand-wiring. This is flag wiring ONLY —
it does not implement multi-module watching/diffing (that design is a separate Fable session;
this task is safely orthogonal to it).

## Spec
1. In `gradle-plugin/.../HotReloadPlugin.kt`: detect module type and behave per type —
   - `com.android.application`: unchanged (flags + `debugImplementation dev.hotreload:runtime-client`).
   - `com.android.library` and `org.jetbrains.kotlin.jvm`: apply the SAME three compiler flags
     (`-Xlambdas=class -Xsam-conversions=class -Xstring-concat=inline`), but do NOT add the
     runtime-client dependency (device runtime lives only in the app module).
   - Any other/unknown module type: fail with a clear message naming the module.
   React to whichever plugin is applied (`pluginManager.withPlugin(...)` for all three ids) rather
   than assuming application; keep the existing reflection style used for the AGP-facing parts.
2. Convert `samples/multi-module/feature` and `core` to `id("dev.hotreload")` and DELETE their
   hand-wired `kotlin { compilerOptions { ... } }` flag blocks + TODO comments. (`settings.gradle.kts`
   already resolves the plugin for `:app` — verify includeBuild/management covers subprojects.)
3. Do not touch `samples/single-module` behavior (it must build byte-identically).

## Out of scope
No engine/cli/protocol/runtime-client changes. No multi-module watch support. No new plugin DSL.

## Acceptance
Run from repo root (source scripts/env.sh in EVERY command — see ground-truth doc env gotcha):
1. `(cd samples/multi-module && ./gradlew :app:assembleDebug --console=plain)` succeeds.
2. Flag proof, all three modules (same method as ground-truth §4): `$JAVAP -v` over every .class in
   each module's classes dir → zero `invokedynamic`, zero `makeConcatWithConstants`. Paths:
   app+feature = `<mod>/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes`,
   core = `core/build/classes/kotlin/main`.
3. `grep -rn "freeCompilerArgs" samples/multi-module/{feature,core}` → no matches (hand-wiring gone).
4. `(cd samples/single-module && ./gradlew :app:assembleDebug)` still succeeds.
5. e2e still green: `e2e/run.sh` 7/7 once (emulator up first), `pgrep -f dev.hotreload.cli.MainKt` empty after.
6. `git status` clean except gradle-plugin, the two sample build files, this spec's status.
