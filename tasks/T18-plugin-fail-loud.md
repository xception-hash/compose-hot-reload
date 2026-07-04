# T18: dev.hotreload plugin — fail loud when compiler flags can't be applied
Status: DONE (Opus, 2026-07-04)
Assignee: agy (tiny, mechanical) → done by Opus in-session

## Result
`HotReloadPlugin.addFreeCompilerArgs` now returns `Boolean` (false = `kotlin` extension
absent). A captured `var flagsApplied` is set true whenever the flags actually land in any
of the three `withPlugin` hooks. The existing `afterEvaluate` block, after the
unsupported-plugin check, throws a `GradleException` when a supported plugin IS applied but
`flagsApplied` stayed false. Working samples are byte-identical (kotlin ext always present
under AGP built-in Kotlin, so flagsApplied=true → no new throw).

### Acceptance results
1. `samples/multi-module :app:assembleDebug` → BUILD SUCCESSFUL (exit 0);
   `samples/single-module :app:assembleDebug` → BUILD SUCCESSFUL (exit 0).
2. Negative test: scratch `com.android.library` + `dev.hotreload` with
   `android.builtInKotlin=false` (so no `kotlin` extension ever appears). Build fails at
   configuration with:
   > The dev.hotreload plugin on project ':' could not apply its Kotlin compiler flags: the `kotlin` extension was not found. This usually means dev.hotreload was applied before the Android/Kotlin plugin, or the module uses an unsupported Kotlin setup (e.g. AGP without built-in Kotlin). Apply dev.hotreload after the Android/Kotlin plugin.
   Control run (same scratch, `android.builtInKotlin=true`) → BUILD SUCCESSFUL, isolating
   the failure to the missing `kotlin` extension.
3. `git status` clean except `gradle-plugin/.../HotReloadPlugin.kt` + this spec.

---
## Original spec (below)

## Goal
Review finding from T15 verification: `HotReloadPlugin.addFreeCompilerArgs` uses
`extensions.findByName("kotlin")` and SILENTLY no-ops when the extension is absent (e.g.
`dev.hotreload` applied before the android/kotlin plugin, or an AGP without built-in
Kotlin). The user then gets none of the class-shape flags and hits confusing redefine
failures at watch time instead of a build-time error.

## Spec
1. In `HotReloadPlugin.kt`: track whether `addFreeCompilerArgs` actually added the flags.
   In the existing `afterEvaluate` block (which already validates module type), if a
   supported plugin IS applied but the flags were never added, throw `GradleException`
   naming the project and saying the `kotlin` extension was not found — likely plugin
   apply-order or an unsupported Kotlin setup.
2. Keep behavior byte-identical for the working samples (no new flags, no API change).

## Out of scope
No retry/ordering magic (no `withPlugin` re-nesting), no DSL, no README beyond one line
in the plugin row if it already documents constraints.

## Acceptance
`source /abs/scripts/env.sh` in every command:
1. `(cd samples/multi-module && ./gradlew :app:assembleDebug --console=plain)` and
   `(cd samples/single-module && ./gradlew :app:assembleDebug)` still succeed.
2. Negative test: in a scratch module (or temporarily reorder plugins in a sample copy
   under /tmp), applying `dev.hotreload` where the `kotlin` extension never appears
   fails the build with the new message. Paste the error line into this spec's status.
3. `git status` clean except gradle-plugin + this spec.
