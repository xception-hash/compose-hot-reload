# T33b: Gradle plugin — runtime-client on every debuggable build type (T33 phase 7 core)
Status: DONE (2026-07-14, Sonnet background agent + coordinator review; branch t33/phase1-wiring)
Assignee: agy

## Outcome (2026-07-14)
Implemented per spec by a Sonnet background agent in an isolated worktree. `finalizeDsl`
wiring reaches the new `qa` fixture build type (QA-WIRED + DEBUG-WIRED greps pass); tripwire
negative test still fails configuration on a hand-added releaseImplementation. Coordinator
fix-up on merge: the tripwire exception message's last sentence still said "adds it as
`debugImplementation`" — updated to "wires it into every debuggable build type automatically"
(spec mandated keeping tripwire logic untouched, which the agent did; only the message text
was stale). README one-liner added after the `--variant stageDebug` example.

## Goal
Close the configured-mode gap found on a large layered app: `HotReloadPlugin` adds the
runtime AAR via a literal `debugImplementation`, so a CUSTOM debuggable build type (e.g.
`stage`, or a flavored `vendorStage`) silently gets no runtime-client — the watch then
fails "device is not hot-swappable" with nothing pointing at the real cause. Wire the
dependency per debuggable build type instead.

## Spec

### 1. `gradle-plugin/src/main/kotlin/dev/hotreload/gradle/HotReloadPlugin.kt`
Inside the existing `withPlugin("com.android.application")` block, REPLACE
```kotlin
project.dependencies.add("debugImplementation", "com.github.xception-hash.compose-hot-reload:runtime-client:0.1.0")
```
with build-type-aware wiring using the DSL-finalize hook (build types are final there;
`onVariants` is too late to add to `<name>Implementation` configurations):
```kotlin
val runtimeCoord = "com.github.xception-hash.compose-hot-reload:runtime-client:0.1.0"
val androidComponents = project.extensions.getByType(ApplicationAndroidComponentsExtension::class.java)
androidComponents.finalizeDsl { ext ->
    ext.buildTypes.filter { it.isDebuggable }.forEach { bt ->
        project.dependencies.add("${bt.name}Implementation", runtimeCoord)
    }
}
```
Notes:
- `${bt.name}Implementation` exists for every build type (AGP creates it); flavored
  variants inherit build-type configs, so flavors need nothing extra.
- Keep the release tripwire (`onVariants` + `variant.debuggable`) EXACTLY as is — it is
  the safety net proving this change never leaks the AAR to a non-debuggable variant.
- Keep the single `runtimeCoord` constant referenced by both the wiring and the tripwire's
  group/name check (extract group+name once; do not duplicate the literal three times).
- Move the existing `getByType(ApplicationAndroidComponentsExtension...)` lookup so it is
  fetched once and shared by finalizeDsl + onVariants.
- Update the class KDoc sentence "adds a `debugImplementation`…" to describe per-
  debuggable-build-type wiring.

### 2. Fixture: custom debuggable build type on the single-module sample
`samples/single-module/app/build.gradle.kts` — add to `android {}`:
```kotlin
buildTypes {
    create("qa") {
        initWith(getByName("debug"))
        isDebuggable = true
        matchingFallbacks += "debug"
    }
}
```
(`qa` exists purely as the regression fixture for this wiring; e2e keeps using `debug`.)

### 3. README
In the advanced-variants section, replace the sentence that implies custom debuggable
build types need a hand-added dependency (if present) — one sentence: the plugin wires
runtime-client into every debuggable build type automatically.

## Out of scope
Zero-touch/init-script mode, runtime version-locking to the tool version, doctor
Gradle-metadata checks (all separate T33 phase-7+ items). The AGP version pin. e2e flows.

## Acceptance
Run from repo root; all must pass:
```bash
export REPO_ROOT="$PWD" && source scripts/env.sh
./gradlew -p gradle-plugin build
# the new wiring reaches a custom debuggable build type:
(cd samples/single-module && ./gradlew -q :app:dependencies --configuration qaRuntimeClasspath | grep -q "runtime-client") && echo QA-WIRED
# debug unchanged:
(cd samples/single-module && ./gradlew -q :app:dependencies --configuration debugRuntimeClasspath | grep -q "runtime-client") && echo DEBUG-WIRED
# tripwire still guards release (must FAIL configuration when hand-added):
#   temporarily add `releaseImplementation("com.github.xception-hash.compose-hot-reload:runtime-client:0.1.0")`
#   to samples/single-module/app/build.gradle.kts, run `./gradlew :app:help`, assert the
#   GradleException message appears, then REVERT the edit (verify `git diff` clean).
(cd samples/single-module && ./gradlew -q :app:assembleDebug) && echo BUILD-OK
```
Device gate (coordinator runs, emulator required): `./e2e/run.sh` green.
