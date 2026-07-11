# Compose Hot Reload for Android

## 1. What it is
A Flutter-style hot reload for Jetpack Compose on real Android devices and emulators. Save a `.kt` file and see your UI update in ~1s with app state preserved—with **zero modifications to existing application code**. V1 is driven via a CLI daemon.

### How it works
It leverages a custom JVMTI agent attached to debuggable apps to perform class redefinition in place (including ART's structural redefinition for added methods/fields), and injects brand-new classes into the app's own classloader via ART's `add_to_dex_class_loader` JVMTI extension. When code changes, the engine compiles and dexes just the diff, pushes the patched dex bytes to the device, and uses reflection into Compose internals (`invalidateGroupsWithKey`) to trigger targeted recomposition.

### Feature matrix

| Edit type | Mechanism | Latency |
|---|---|---|
| Constant literal (color, dp, string in code) | Live Literals patch over socket | ~22 ms |
| Composable/function body change | JVMTI `RedefineClasses` + `invalidateGroupsWithKey` | <1 s |
| New composable/function/field in existing class | Structural redefinition (API 30+) + recompose | <1 s |
| New class / changed lambda | Inject new dex via `InMemoryDexClassLoader` + redefine call sites | <1 s |
| Resource value edit (`res/values/*.xml` string/color) | `ResourcesLoader` overlay + whole-tree recompose | ~2 s |
| Vector/bitmap drawable edit (`res/drawable*/*.xml`, `.png`, `.webp`, `.jpg`, `.jpeg`) | `ResourcesLoader` overlay + Compose asset-cache clear + recompose | ~1–2.5 s |
| Member removal / class hierarchy change | AOSP LiveEdit bytecode interpreter on-device (same process, state preserved) | ~1.5 s |
| `@Composable` signature change (params added/removed) | Interpreter + AOSP lambda proxies: regenerated restart lambda ships as support class, proxy-constructed on device | ~1–4 s |

## 2. Requirements
- **Device**: API 30+ (Android 11+) emulator or physical device via adb
- **Build**: Debuggable build variant only
- **Pinned Toolchain**:
  - Kotlin 2.4.0
  - Android Gradle Plugin (AGP) 9.2.1
  - Gradle 9.6.1
  - Compose BOM 2026.06.01
  - NDK 28.2.13676358
  - Build-tools 36.0.0
  - JBR (JetBrains Runtime from Android Studio) as `JAVA_HOME`

## 3. Quickstart

### Try it on the bundled sample (fastest way to see it work)
Want to see hot reload before wiring it into your own app? This repo ships a ready-to-go sample
(`samples/single-module`, app id `dev.hotreload.sample`) that already applies the plugin. With an
API 30+ emulator/device running:
```bash
git clone https://github.com/xception-hash/compose-hot-reload.git
cd compose-hot-reload
source scripts/env.sh                                   # sets JAVA_HOME (JBR) etc.
(cd samples/single-module && ./gradlew :app:installDebug)
adb shell monkey -p dev.hotreload.sample -c android.intent.category.LAUNCHER 1
./gradlew -q :cli:run --args="watch --project $PWD/samples/single-module --app-id dev.hotreload.sample"
```
Now edit any composable in
`samples/single-module/app/src/main/kotlin/dev/hotreload/sample/MainActivity.kt` (e.g. change the
`Greeting()` text), save, and watch the device update in ~1s with the on-screen counter state
preserved. To add hot reload to **your own** app instead, follow a)–c) below.

### a) Add the JitPack repository and plugin dependency

In your **root** `settings.gradle.kts`:
```kotlin
pluginManagement {
    repositories {
        maven { url = uri("https://jitpack.io") }
        gradlePluginPortal()
        google()
        mavenCentral()
    }
    // The plugin's ID is `dev.hotreload`, but on JitPack it is served under the
    // repo's coordinates — map the plugin request to the JitPack module.
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "dev.hotreload") {
                useModule("com.github.xception-hash.compose-hot-reload:gradle-plugin:${requested.version}")
            }
        }
    }
}

dependencyResolutionManagement {
    repositories {
        maven { url = uri("https://jitpack.io") }
        google()
        mavenCentral()
    }
}
```

In your **app** `build.gradle.kts`:
```kotlin
plugins {
    id("dev.hotreload") version "0.1.0"
}
```

The plugin automatically adds the runtime-client AAR (`com.github.xception-hash.compose-hot-reload:runtime-client:0.1.0`) to your debug build — no manual dependency needed.

### b) Build and install

Make sure your emulator/device is up (API 30+, debuggable build):
```bash
./gradlew :app:installDebug
adb shell monkey -p <your.app.id> -c android.intent.category.LAUNCHER 1
```

### c) Start the hot-reload watcher

Clone this repository and run from its root:
```bash
source scripts/env.sh   # JAVA_HOME etc.
./gradlew -q :cli:run --args="watch --project /path/to/your/project --app-id <your.app.id>"
```

Edit a composable, save the file, and watch the UI update on the device instantly.

For multi-module projects, pass `--module` with a comma-separated list of Gradle module names (first entry = app module; nested paths use `/`):
```bash
./gradlew -q :cli:run --args="watch --project /path/to/your/project --app-id <your.app.id> --module app,feature,core"
```
Pure-Kotlin (`kotlin-jvm`) modules are supported — edits there recompose the whole tree with state preserved.

> **Using an AI coding agent?** Point it at [`AGENTS.md`](AGENTS.md) — preflight checks,
> how to run the (blocking) watcher in the background, and the exact log lines that signal
> success or failure at each step.

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
| bitmap **drawable** edit (`res/drawable*/*.{png,webp,jpg,jpeg}`) | `ResourcesLoader` overlay + bashing `painterResource`'s internal remember groups (`invalidateGroupsWithKey`); new bitmap on screen, all state preserved | ~1–2.5s |
| member **removal** / class hierarchy change | AOSP LiveEdit **bytecode interpreter** on-device: the class is primed (host stub-transform → structural redefine) and its edited bodies are interpreted, same process, state preserved | ~1.5s (first prime ~4.5s) |
| `@Composable` **signature change** (params added/removed, caller updated in the same save) | interpreter + AOSP lambda **proxies**: the regenerated restart lambda ships as a *support class* and is proxy-constructed on device; whole batch interprets, same process | ~1–4s |

Edits the classifier would otherwise reject as "rebuild" — **member removal**, **hierarchy
changes**, and **signature changes** — are **interpreted** on-device instead: the affected class's
method entries are diverted into the vendored AOSP LiveEdit interpreter (see `NOTICE`), so the
change lands in the same process with `remember`/`rememberSaveable` preserved (leaf-edit semantics
— a parent edit still resets its subtree, and a signature change always edits its caller, so its
parent's subtree resets). Once a class is interpreted, all its later edits go through the
interpreter too, and if any class in a save routes to the interpreter the whole batch does (mixing
compiled callers with interpreter-only methods would `NoSuchMethodError`). For a composable
signature change, the regenerated restart lambda's changed constructor is handled by AOSP's
generated lambda `Proxies`: the lambda travels as a *support class* and is proxy-constructed on
device (`docs/phase6-interpreter-research.md` §7). **Remaining rebuild cases:** `<clinit>` changes,
constructor changes on non-lambda classes, field add/remove/type-change, Compose group-key
renumbering, and suspend-lambda constructor changes.

Resource edits are **values-only** in v1: changing the *value* of an existing string/color
hot-reloads. Adding, removing, or renaming a resource is detected and reported as
"reinstall required" (aapt2 renumbers IDs, which an overlay can't remap). Vector (XML)
drawable edits under `res/drawable*/` hot-reload too — the overlay is applied and Compose's
per-`Context` asset caches (`ImageVectorCache`/`ResourceIdCache`) are cleared before the
recompose. Bitmap drawables (`png`/`webp`/`jpg`/`jpeg`, T30 item 4) hot-reload as well:
`painterResource` remembers the decoded bitmap keyed on the *intra-APK* path string (identical
in every overlay), so the engine additionally bashes exactly those remember groups via
`invalidateGroupsWithKey` with the ui-version-specific group key it extracts from the app's own
dex (`PainterKeyExtractor`). Only the painter groups rebuild — user state is untouched. Direct
`ImageBitmap.imageResource()` call sites are not covered (no targetable group);
`painterResource` is the standard path. Nine-patch (`.9.png`) drawables are **not** supported —
not a hot-reload pipeline gap but a Compose framework limitation: `painterResource()`'s bitmap
branch unconditionally casts the resolved `Drawable` to `BitmapDrawable`
(`ImageResources_androidKt.imageResource`), and aapt2 compiles `.9.png` sources to
`NinePatchDrawable`, which is not a `BitmapDrawable` subtype — the cast throws
`ClassCastException` on a fresh install with no hot reload involved (verified live, T30 item 4).
Font resources (`res/font/*.ttf`/`.otf`) are also not hot-reloaded: `SourceWatcher`'s file-system
listener only matches `.kt`/`.xml`/`.png`/`.webp`/`.jpg`/`.jpeg`, so a font edit never reaches the
engine at all — no "changed"/"ignored" line prints, the save is silently invisible to the watcher
(verified live, T30 item 4).

### State-reset semantics
The state-preservation semantics match Android Studio's Live Edit: `invalidateGroupsWithKey` discards `remember` AND `rememberSaveable` state in the **edited function's subtree** because the runtime must re-run initializers that may capture new code. Editing a leaf preserves everything else; editing a parent resets its children's counters to 0.

### Broken-edit behavior
If you make a broken edit (like throwing an exception in a composable body), the recomposition error is detected and reported with the source location. The UI will keep the last-good frame running. Once you fix the code and save, the full UI recovers in-place without needing a reinstall.

## 5. Limitations

- **`<clinit>` / static-initializer edits** cause a full rebuild (ART cannot re-run class initializers).
- **Constructor edits** on non-lambda classes cause a full rebuild (existing object instances cannot be re-constructed).
- **Compose group-key renumbering** (reordering composable calls) causes a full rebuild (the invalidation key map becomes stale).
- **Field removal / field type change** causes a full rebuild (ART's structural redefinition can only add fields, not remove or retype them).
- **`ImageBitmap.imageResource()` call sites are not covered** by bitmap drawable hot reload — unlike `painterResource`'s bitmap branch, they have no recomposition group of their own for the engine to target, so edits there stay stale until a full reinstall.
- **Nine-patch (`.9.png`) drawables are not supported** — this is a Compose framework limitation, not a hot-reload gap: `painterResource()`'s bitmap branch casts the resolved drawable to `BitmapDrawable`, but aapt2 compiles `.9.png` to `NinePatchDrawable`, which throws `ClassCastException` even on a plain install (T30 item 4).
- **Font resources (`res/font/*.ttf`/`.otf`) are not hot-reloaded** — `SourceWatcher`'s extension filter doesn't include font extensions, so font edits never reach the engine; use a full reinstall (T30 item 4).
- **Debuggable builds only** — ART's JVMTI agent attachment and class redefinition require `android:debuggable="true"`.
- **Minimum API 30** (Android 11) — structural class redefinition (`RedefineClassesStructurally`) is only available on API 30+.
- **Compiled-callee exceptions skip interpreted try/catch** — when a class is interpreted and calls a compiled method that throws, the exception propagates through the native call boundary and cannot be caught by the interpreter's try/catch handler (see `docs/phase6-interpreter-research.md` §5).
- **`@Composable` signature changes hot-reload via lambda proxies** but may reset the enclosing subtree's `remember` state: because the signature change necessarily edits the caller, the parent composable is invalidated and its subtree's `remember`/`rememberSaveable` state resets (parent-invalidate semantics, not a rebuild fallback).
- **Suspend-lambda constructor changes** cause a full rebuild (suspend continuations have structural constraints the proxy path cannot satisfy).

## 6. IDE plugin

An IntelliJ IDEA / Android Studio plugin is available for driving hot reload from the IDE.

**What it does:**
- Spawns the `hotreload` CLI (`hotreload watch …`) and reflects its state in the status bar.
- Status-bar widget shows: `Hot Reload: off / starting… / ready (Nms) / reloading… / error(n) / rebuild needed`. Click it to Start/Stop.
- **Tools ▸ Start/Stop Hot Reload** menu toggle.
- **Settings ▸ Tools ▸ Compose Hot Reload** for CLI path, project dir, app id, modules, and extra CLI args (persisted per-project).
- Balloons on failure: reload error (with first compiler/recompose error) and rebuild-required notice.

**Install from disk:**
Build the plugin zip:
```bash
cd intellij-plugin && ./gradlew buildPlugin
```
This produces `build/distributions/hotreload-intellij-plugin-0.1.0.zip`. Install it in your IDE via **Settings ▸ Plugins ▸ ⚙ ▸ Install Plugin from Disk…**.

See [`intellij-plugin/README.md`](intellij-plugin/README.md) for full details.

## 7. Repo layout
- `protocol/` — Shared message definitions and binary protocol.
- `runtime-client/` — Android AAR injected into apps: startup init, JVMTI agent `.so`, socket server, and Compose bridge shims.
- `engine/` — JVM library: orchestrator for watcher, Gradle compiler, class diffing, D8 dexing, and client protocol.
- `cli/` — Thin CLI wrapper around the engine.
- `gradle-plugin/` — Gradle plugin that wires the runtime-client into debug builds and sets compiler flags.
- `intellij-plugin/` — IntelliJ IDEA / Android Studio plugin (status bar widget, CLI spawner).
- `samples/` — Example apps (single-module and multi-module).
- `e2e/` — Scripted emulator end-to-end tests.
- `docs/` — Project plan, findings, and architectural notes.
- `tasks/` — Task specifications and progress tracking.

## 8. Status
**0.1.0 — first public release.** Hot reload supports single-module and multi-module applications, resource edits, the AOSP LiveEdit interpreter for structural changes, and composable signature changes via lambda proxies. See the [Project Plan](docs/PLAN.md) for future milestones.

## 9. Troubleshooting
If hot reload fails to connect or experience issues on device, run `hotreload doctor` to verify your environment, SDK toolchain, device status, project configuration, and runtime handshake:
```bash
./gradlew -q :cli:run --args="doctor --project $PWD/samples/single-module --app-id dev.hotreload.sample"
```

## 10. Live-literals fast path (experimental, opt-in)
Editing only a constant inside a composable — a plain string, number, boolean, or char — can skip Gradle + d8 + class redefinition entirely and push the new value straight into Compose's live-literals mechanism, for a sub-100ms update. It is **off by default** because the Compose compiler's live-literals instrumentation adds overhead to debug builds.

To use it, build the app with the property and watch with `--literals`:
```bash
./gradlew :app:installDebug -Photreload.liveLiterals=true
./gradlew -q :cli:run --args="watch --literals --project $PWD/samples/single-module --app-id dev.hotreload.sample"
```
`watch --literals` fails fast if the installed app wasn't built with the property. Only single, contiguous literal edits take the fast path; anything else (template strings, `const val`, structural edits, multi-file saves) falls back to the normal compile-and-swap path automatically.
