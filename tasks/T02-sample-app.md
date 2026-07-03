# T02: samples/single-module — clean target app for Phase 1
Status: TODO
Assignee: agy

## Goal
Phase 1's MVP is verified against a *clean* Compose app that has none of the spike's plumbing
(no agent, no receiver, no HotSwap code) — the product must work with zero app-code edits.

## Spec
- Create `samples/single-module/` as a standalone Gradle project. Copy the build setup from
  `spike/toy-app` **exactly** (same versions, same `settings.gradle.kts` structure, same
  `gradle.properties`, wrapper 9.6.1, AGP 9.2.1, Kotlin 2.4.0, Compose BOM 2026.06.01,
  compiler flags incl. `generateFunctionKeyMetaAnnotations`, `-Xlambdas=class`,
  `-Xsam-conversions=class`, `useLegacyPackaging = true`).
- Package `dev.hotreload.sample`, applicationId `dev.hotreload.sample`, minSdk 30.
- NO cpp/, NO HotSwap/ComposeBridge/PatchReceiver/App classes, NO receiver in the manifest —
  just MainActivity with: a counter Button using `remember` + `rememberSaveable`
  (like the toy app's `Counter`), a `Greeting` Text, and a simple list of 5 items.

## Out of scope
Everything else. Do not modify `spike/`.

## Acceptance
```
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
cd samples/single-module && ./gradlew :app:assembleDebug   # succeeds
unzip -l app/build/outputs/apk/debug/app-debug.apk | grep -c "\.so"   # prints 0
```
