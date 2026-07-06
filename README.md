# Compose Hot Reload for Android

## 1. What it is
A Flutter-style hot reload for Jetpack Compose on real Android devices and emulators. Save a `.kt` file and see your UI update in ~1s with app state preserved—with **zero modifications to existing application code**. V1 is driven via a CLI daemon.

### How it works
It leverages a custom JVMTI agent attached to debuggable apps to perform class redefinition in place (including ART's structural redefinition for added methods/fields), and injects brand-new classes into the app's own classloader via ART's `add_to_dex_class_loader` JVMTI extension. When code changes, the engine compiles and dexes just the diff, pushes the patched dex bytes to the device, and uses reflection into Compose internals (`invalidateGroupsWithKey`) to trigger targeted recomposition.

## 2. Requirements
- **Device**: API 30+ (Android 11+) emulator or physical device via adb
- **Build**: Debuggable build variant only
- **Pinned Toolchain**: 
  - Kotlin 2.4.0
  - Android Gradle Plugin (AGP) 9.2.1
  - Gradle 9.6.1
  - Compose BOM 2026.06.01

## 3. Quickstart
Apply the `dev.hotreload` plugin in your application's `build.gradle.kts`, and make it resolvable in `settings.gradle.kts` (this is how `samples/single-module` is wired):

```kotlin
// settings.gradle.kts
pluginManagement {
    includeBuild("../../gradle-plugin")
}

// app/build.gradle.kts
plugins {
    id("dev.hotreload")
}
```

Make sure your emulator is up, then install and launch the sample:
```bash
(cd samples/single-module && ./gradlew :app:installDebug)
adb shell monkey -p dev.hotreload.sample -c android.intent.category.LAUNCHER 1
```

Start the hot-reload watcher from the repository root:
```bash
source scripts/env.sh   # JAVA_HOME etc.
./gradlew -q :cli:run --args="watch --project $PWD/samples/single-module --app-id dev.hotreload.sample"
```

Edit a composable in the sample project, save the file, and watch the UI update on the device instantly.

For multi-module projects, pass `--module` with a comma-separated list of Gradle module names (first entry = app module; nested paths use `/`):
```bash
./gradlew -q :cli:run --args="watch --project $PWD/samples/multi-module --app-id dev.hotreload.multisample --module app,feature,core"
```
Pure-Kotlin (`kotlin-jvm`) modules are supported — edits there recompose the whole tree with state preserved.

## 4. What hot-reloads today
| Case | Mechanics | Time |
|---|---|---|
| structural add (new `@Composable` + call site, one save) | 1 injected (new lambda class), 6 redefined structural, 7 groups invalidated | ~3.2s |
| new file / new class | 2 injected, no invalidation | ~1.0s |
| call site into injected class | 6 redefined body-only (correctly NOT structural) | ~2.4s |
| broken edit (`error()` in a body) | detected + reported with source location; UI keeps last-good frame | ~1.2s |
| fix-and-save after broken edit | full UI recovers in place, no reinstall | ~1.2s |
| resource **value** edit (`res/values/*.xml` string/color) | `ResourcesLoader` overlay + whole-tree `invalidateAll`; new value on screen, all state preserved | ~2s |
| vector **drawable** edit (`res/drawable*/*.xml`) | `ResourcesLoader` overlay + Compose asset-cache clear + whole-tree recompose; new drawable on screen, all state preserved | ~1–2s |
| member **removal** / non-composable method **signature** change / hierarchy change | AOSP LiveEdit **bytecode interpreter** on-device: the class is primed (host stub-transform → structural redefine) and its edited bodies are interpreted, same process, state preserved | ~1.5s (first prime ~4.5s) |

Edits the classifier would otherwise reject as "rebuild" (member removal, a non-composable
method's signature change, a hierarchy change) are **interpreted** on-device instead: the affected
class's method entries are diverted into the vendored AOSP LiveEdit interpreter (see `NOTICE`), so
the change lands in the same process with `remember`/`rememberSaveable` preserved — no reinstall.
Once a class is interpreted, all its later edits go through the interpreter too. **Known
limitation:** a `@Composable` function's own *signature* change still requires a rebuild — Compose
regenerates the function's restart lambda with a changed constructor, which needs AOSP's `Proxies`
codegen (out of scope for v1; see `docs/phase6-interpreter-research.md` §4 addendum). `<clinit>`
and constructor edits also stay rebuild.

Resource edits are **values-only** in v1: changing the *value* of an existing string/color
hot-reloads. Adding, removing, or renaming a resource is detected and reported as
"reinstall required" (aapt2 renumbers IDs, which an overlay can't remap). Vector (XML)
drawable edits under `res/drawable*/` hot-reload too — the overlay is applied and Compose's
per-`Context` asset caches (`ImageVectorCache`/`ResourceIdCache`) are cleared before the
recompose. Bitmap drawables (`png`/`webp`) still need a reinstall.

### State-reset semantics
The state-preservation semantics match Android Studio's Live Edit: `invalidateGroupsWithKey` discards `remember` AND `rememberSaveable` state in the **edited function's subtree** because the runtime must re-run initializers that may capture new code. Editing a leaf preserves everything else; editing a parent resets its children's counters to 0.

### Broken-edit behavior
If you make a broken edit (like throwing an exception in a composable body), the recomposition error is detected and reported with the source location. The UI will keep the last-good frame running. Once you fix the code and save, the full UI recovers in-place without needing a reinstall.

## 5. Repo layout
- `protocol/` — Shared message definitions and binary protocol.
- `runtime-client/` — Android AAR injected into apps: startup init, JVMTI agent `.so`, socket server, and Compose bridge shims.
- `engine/` — JVM library: orchestrator for watcher, Gradle compiler, class diffing, D8 dexing, and client protocol.
- `cli/` — Thin CLI wrapper around the engine.
- `gradle-plugin/` — Gradle plugin that wires the runtime-client into debug builds and sets compiler flags.
- `samples/` — Example apps (currently a single-module sample).
- `e2e/` — Scripted emulator end-to-end tests.
- `docs/` — Project plan, findings, and architectural notes.
- `tasks/` — Task specifications and progress tracking.

## 6. Status
**Experimental.** Hot reload currently only supports single-module applications. For future milestones, multi-module support, and IDE integration, see the [Project Plan](docs/PLAN.md) phases.

## 7. Troubleshooting
If hot reload fails to connect or experience issues on device, run `hotreload doctor` to verify your environment, SDK toolchain, device status, project configuration, and runtime handshake:
```bash
./gradlew -q :cli:run --args="doctor --project $PWD/samples/single-module --app-id dev.hotreload.sample"
```

## 8. Live-literals fast path (experimental, opt-in)
Editing only a constant inside a composable — a plain string, number, boolean, or char — can skip Gradle + d8 + class redefinition entirely and push the new value straight into Compose's live-literals mechanism, for a sub-100ms update. It is **off by default** because the Compose compiler's live-literals instrumentation adds overhead to debug builds.

To use it, build the app with the property and watch with `--literals`:
```bash
./gradlew :app:installDebug -Photreload.liveLiterals=true
./gradlew -q :cli:run --args="watch --literals --project $PWD/samples/single-module --app-id dev.hotreload.sample"
```
`watch --literals` fails fast if the installed app wasn't built with the property. Only single, contiguous literal edits take the fast path; anything else (template strings, `const val`, structural edits, multi-file saves) falls back to the normal compile-and-swap path automatically.
