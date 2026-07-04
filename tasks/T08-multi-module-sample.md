# T08: Multi-module sample app
Status: DONE (Opus, 2026-07-04) — all 3 acceptance checks pass on emulator
Assignee: agy or Opus

## Goal
`samples/multi-module/` — the testbed the multi-module diffing design session (Fable) will
develop against. Hot reload does NOT need to work on it yet; it only needs to build, install,
launch, and render composables from every module.

## Spec
Copy the structure/pins of `samples/single-module` (same Kotlin/AGP/Gradle/BOM versions,
same `pluginManagement { includeBuild("../../gradle-plugin") }` + `includeBuild("../../runtime-client")`
wiring). Package base `dev.hotreload.multisample`, applicationId `dev.hotreload.multisample`.

Three modules:
1. `:app` — Android application, applies `id("dev.hotreload")`. MainActivity hosts a Column
   with an app-level counter composable plus the composables from `:feature`.
2. `:feature` — Android library with Compose. One `FeatureCard()` composable containing its
   own `remember` counter and a `Text` that renders a string from `:core`.
3. `:core` — Kotlin-only (non-Android) module. A top-level function `fun coreLabel(n: Int): String`
   used by `:feature`.

Dependency chain: app → feature → core.

Compiler flags: `:app` gets them from the plugin. `:feature` and `:core` must set the same
flags manually in their build files (`-Xlambdas=class -Xsam-conversions=class -Xstring-concat=inline`)
with a comment `// TODO: absorbed by dev.hotreload plugin once multi-module lands`.

## Out of scope
No engine/cli/gradle-plugin/protocol changes. No e2e cases. Hot reload working across
modules is explicitly NOT expected.

## Acceptance
1. `(cd samples/multi-module && ./gradlew :app:installDebug)` succeeds (emulator up via
   `scripts/emulator-up.sh`).
2. `adb shell monkey -p dev.hotreload.multisample -c android.intent.category.LAUNCHER 1`
   launches; `scripts/ui-state.sh` shows texts from both the app-level composable and
   `FeatureCard` (including the `coreLabel` string).
3. Both counters increment on tap.
