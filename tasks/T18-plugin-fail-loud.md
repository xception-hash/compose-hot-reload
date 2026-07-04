# T18: dev.hotreload plugin — fail loud when compiler flags can't be applied
Status: TODO
Assignee: agy (tiny, mechanical)

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
