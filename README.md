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

## 2. Supported path and compatibility tiers

**The stable, release-blocking path is configured mode:** explicitly apply the `dev.hotreload`
Gradle plugin, review and save an explicit CLI profile, run matching `prepare` and `doctor`, then
use `watch` (or `start`). The CLI owns the installed APK baseline; do not describe an ordinary
Android Studio `installDebug` APK plus a profile as the supported path.

| Tier | What it means |
|---|---|
| **Stable** | Configured Gradle-plugin integration; explicit app/module/variant configuration; profiles; `doctor`; `prepare`; `start`; `watch`; `config show`; target JDK/device/activity and explicit module mappings. |
| **Setup helper** | `inspect`, `configure`, automatic discovery and include/exclude filters suggest a setup. Review their result and pin explicit values if it is wrong. |
| **Advanced** | `--gradle-arg`, `--sdk`, `--build-tools`, and non-default variants are valid when deliberately pinned identically in preparation and watching. |
| **Experimental** | `--zero-touch` and `--literals` remain available and tested, but are not release-blocking onboarding paths. |
| **Diagnostic escape hatch** | `--ignore-fingerprint` can permit an APK/class baseline mismatch and Compose state corruption. Use matching `prepare` instead. |
| **Machine-readable** | `inspect --json` is schema-v1 output for tools and agents; it is not another integration mode. |

## 3. Requirements and tested lanes

- **Device:** exactly one selected API 30+ emulator or physical device via adb.
- **Build:** a debuggable variant only. The runtime is deliberately absent from non-debuggable variants.
- **Tested lane A:** AGP 8.12.x, standalone Kotlin 2.3.x, and a full JDK 17.
- **Tested lane B:** AGP 9.2.x, built-in Kotlin/Kotlin 2.4.x, and JDK/JBR 21.

Azul, Temurin, and other conforming full JDKs are acceptable when their major version matches the
target lane and both `bin/java` and `bin/javac` exist. The CLI/source and IDE workflows use Android
Studio's JBR 21; `--project-java-home` independently selects the target project's Gradle JDK.
Kotlin DSL is tested. Groovy scripts, multiple flavor dimensions, included-build application
modules, multiple devices, and unlisted toolchains are best effort rather than rejected.

## 4. Stable Quickstart: configured Gradle plugin

Clone this repository, connect one API-30+ device, and keep the CLI on Android Studio's JBR:

```bash
git clone https://github.com/xception-hash/compose-hot-reload.git
cd compose-hot-reload
unset JAVA_HOME
source scripts/env.sh
```

Add JitPack/plugin resolution to the target's root `settings.gradle.kts`, without removing its
existing repositories or plugins:

```kotlin
pluginManagement {
    repositories {
        maven { url = uri("https://jitpack.io") }
        gradlePluginPortal()
        google()
        mavenCentral()
    }
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

Apply the plugin to the Android app and every watched Android-library or Kotlin-JVM module:

```kotlin
plugins {
    id("dev.hotreload") version "0.2.0"
}
```

The plugin adds the matching runtime-client AAR to debuggable builds; do not add that AAR directly
or to a release/non-debuggable configuration.

Use discovery only as a setup suggestion, review it, and save an explicit profile. This example
pins an app, a watched library, and the target project's JDK:

```bash
./gradlew -q :cli:run --args="configure \
  --project /path/to/your/project \
  --save-as my-app \
  --app-id com.example.app.debug \
  --module :app=applications/main,:feature=features/feature \
  --variant debug \
  --project-java-home /path/to/jdk-17 \
  --device emulator-5554"
./gradlew -q :cli:run --args="config show --profile my-app"
./gradlew -q :cli:run --args="prepare --profile my-app"
./gradlew -q :cli:run --args="doctor --profile my-app"
./gradlew -q :cli:run --args="watch --profile my-app" > /tmp/hotreload.log 2>&1 &
```

Wait for `watching …` in the log before editing. Make one reversible composable-body edit; a
successful save prints `changed:` followed by `hot-swapped:`, `interpreted:`, or
`literal-pushed:`. Stop the watcher when finished. `start --profile my-app` is the equivalent
one-command path when preparation is needed.

For a target whose discovery is correct, begin with `configure --project … --save-as …`, inspect
`config show`, then add explicit flags for the app ID, module closure, variants, physical module
directories, JDK, device, or activity before preparing. Profiles live outside the target repository
at `~/.config/compose-hot-reload/projects/<name>.toml`; explicit flags override profile values.

### Experimental zero-touch and live literals

`--zero-touch` instruments a selected debuggable build through a bundled init script without
editing project inputs. It is experimental: init scripts/composite builds, reflective AGP/Kotlin
lifecycle handling, exact prepare/watch parity, and included-build boundaries are known risk areas.
Use matching `prepare --zero-touch` and `watch --zero-touch`; do not use it as the normal
recovery for configured mode.

`--literals` is also experimental. The same profile and `--literals` choice must build, install,
and watch the APK. A mismatch can corrupt Compose's slot table. If a fingerprint, protocol, or
integration mismatch occurs, stop, run matching `prepare`, relaunch, and restart; do **not** lead
with `--ignore-fingerprint`.

### Use an AI agent

Read the public [AI project setup and recovery guide](docs/ai-project-setup.md) and give an agent
the copy/paste prompt there. [`AGENTS.md`](AGENTS.md) supplies the machine-readable watcher
readiness and failure contract.

### CLI reference

`help` and `--help` print this reference. `watch`, `prepare`, `start`, `doctor`, `inspect`, and
`configure` require a project directly or through a profile; `config show` requires a profile.
`prepare` and `watch` must use the same APK-shaping inputs (integration mode, modules, variant,
JDK, Gradle arguments, and literals choice); `start` maintains that parity for one profile.

| Command or option | Applies to | Tier | Changes / when needed | Common failure and safe recovery |
|---|---|---|---|---|
| `help`, `--help` | CLI | Stable | Prints commands and flags. Use before scripting an unfamiliar release. | Unknown command/flag: read help; do not guess spelling. |
| `watch` | CLI | Stable | Watches one prepared profile and sends edits. | No `watching …`: fix Doctor/prepare configuration, then start one watcher. |
| `prepare` | CLI | Stable | Builds, installs, launches and fingerprints the configured APK. | Fingerprint mismatch later: rerun matching prepare, relaunch, then watch. |
| `start` | CLI | Stable | Runs Doctor, prepares if necessary, then watches. | Use `doctor` separately to inspect a failure without starting a watcher. |
| `doctor` | CLI | Stable | Checks JDK, SDK, device, project configuration and runtime handshake. | Correct the reported JDK/SDK/device/configuration; do not bypass it first. |
| `inspect` | CLI | Setup helper | Discovers app/module/variant suggestions. | Wrong result: provide explicit app/module/variant values. |
| `configure` | CLI | Setup helper | Reviews discovery and writes a named explicit profile. | Wrong closure: correct flags and run configure again. |
| `config show` | CLI | Stable | Prints saved profile and expanded watch command. | Missing/stale profile: configure it again; never infer hidden defaults. |
| `--project <dir>` | all except `config show` | Stable | Target Gradle root. | Wrong root/no classes: select the wrapper/settings root. |
| `--profile <name>` | watch/prepare/start/doctor/config show | Stable | Loads a reviewed saved profile; explicit flags override it. | Parity mismatch: use one profile or rerun matching prepare. |
| `--save-as <name>` | configure | Stable | Names the saved profile. | Invalid/missing name: use letters, digits, `.`, `_`, `-`, with no `..`. |
| `--app-id <id>` | config-resolving commands | Stable | Installed debuggable application ID. | Socket/app not found: verify selected variant's application ID and prepare again. |
| `--app-module <path>` | config-resolving commands | Stable | Declares which watched module is the Android app. | Non-AGP module: correct the app module or module ordering. |
| `--app-module-dir <dir>` | config-resolving commands | Stable | Physical directory override for the app module. | Metadata/classes absent: use the actual module directory. |
| `--module <names>` | config-resolving commands | Stable | Ordered watched modules; first is app unless overridden; supports `:path=dir`. | Missing library edits: include the reachable owner and run matching prepare. |
| `--module-variant <path=variant>` | config-resolving commands | Stable | Per-module variant override. | Variant task missing: inspect Gradle's real variant and correct mapping. |
| `--variant <name>` | config-resolving commands | Advanced | Global debuggable build variant. | Release/non-debuggable build: select a debuggable variant. |
| `--project-java-home <dir>` | config-resolving commands | Stable | Full JDK for target Gradle, separate from CLI JBR. | Java/Gradle error: verify both `bin/java` and `bin/javac`, then correct path. |
| `--device <serial>` | config-resolving commands | Stable | Selects one adb device. | Zero/multiple devices: connect one or pass the correct serial. |
| `--launch-activity <name>` | prepare/start | Stable | Activity to launch if app is stopped. | Launch fails: supply the target launcher activity and prepare again. |
| `--include-module <path>` | discovery commands | Setup helper | Restricts discovered closure while retaining the app. | Library/resource unseen: remove restriction or include its owner, then prepare. |
| `--exclude-module <path>` | discovery commands | Setup helper | Drops a discovered module. | Needed edits disappear: remove exclusion and prepare. |
| `--json` | inspect | Machine-readable | Emits schema-v1 discovery JSON for tools. | Parse/schema issue: consume only documented JSON, or use human inspect output. |
| `--gradle-arg <arg>` | config-resolving commands | Advanced | Repeats an exact target Gradle argument. | APK differs: pin it in the profile and run matching prepare/watch. |
| `--sdk <dir>` | config-resolving commands | Advanced | Android SDK root. | Missing d8/dexdump: correct SDK path/build-tools installation. |
| `--build-tools <v>` | config-resolving commands | Advanced | Chooses Android build-tools used for d8. | Tool absent: install/select that version; do not silently use another. |
| `--zero-touch` | inspect/configure/doctor/prepare/watch/start | Experimental | Bundled init-script integration without target edits. | Composite/lifecycle/parity issue: return to configured mode or use matching zero-touch prepare/watch. |
| `--literals` | configure/prepare/watch/start/doctor | Experimental | Enables live-literal compiler/runtime path. | Slot-table/fingerprint mismatch: stop and prepare/watch with the same literals choice. |
| `--ignore-fingerprint` | watch/start | Diagnostic escape hatch | Skips a known APK fingerprint mismatch for diagnosis only. | Possible state corruption: stop, matching prepare, relaunch, restart; do not keep using the override. |

## 5. What hot-reloads today
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

## 6. Limitations

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

## 7. IDE plugin

An IntelliJ IDEA / Android Studio plugin is available for driving hot reload from the IDE.

**What it does:**
- Spawns the `hotreload` CLI (`hotreload watch …`) and reflects its state in the status bar.
- Status-bar widget shows: `Hot Reload: off / starting… / ready (Nms) / reloading… / error(n) / rebuild needed`. Click it to Start/Stop.
- **Tools ▸ Start/Stop Hot Reload** menu toggle.
- **Settings ▸ Tools ▸ Compose Hot Reload** for structured project/profile settings. Use **Refresh
  discovery** to populate editable app-module, debuggable-variant, application-id, and watched-module
  choices; the page previews the resolved `hotreload watch` command. Target JDK, device, SDK,
  literals, zero-touch, repeatable Gradle args, and one-token-per-line advanced overrides are also
  persisted per project.
- Balloons on failure: reload error (with first compiler/recompose error) and rebuild-required notice.

**Install from JetBrains Marketplace:**
Install [Compose Hot Reload](https://plugins.jetbrains.com/plugin/32850-compose-hot-reload) from
**Settings ▸ Plugins**. The Marketplace plugin includes its CLI, so no repository clone or separate
CLI installation is required. Then open **Settings ▸ Tools ▸ Compose Hot Reload**, use **Refresh
discovery** as a suggestion, review and save an explicit configured profile, run matching
**Doctor**/**prepare**, and use the status-bar widget or **Tools ▸ Start Hot Reload**. Wait for
**Ready** before the first save; use **Stop** when finished. Use one selected API-30+ device and one
watcher at a time.

The currently published Marketplace listing is version 0.1.8. Its description will be expanded in
the next maintainer-authorized plugin update; the source documentation below already describes the
stable configured workflow.

**Install from disk:**
Build the plugin zip:
```bash
cd intellij-plugin && ./gradlew buildPlugin
```
This produces `build/distributions/hotreload-intellij-plugin-0.2.0.zip`. Install it in your IDE via **Settings ▸ Plugins ▸ ⚙ ▸ Install Plugin from Disk…**.

The next unified release is 0.2.0: install the currently published IDE plugin from the
[JetBrains Marketplace](https://plugins.jetbrains.com/plugin/32850-compose-hot-reload), download a
signed ZIP or CLI distribution from the
[GitHub Release](https://github.com/xception-hash/compose-hot-reload/releases/tag/0.2.0), and
resolve the Gradle plugin/runtime AAR from [JitPack](https://jitpack.io/#xception-hash/compose-hot-reload/0.2.0).

See [`intellij-plugin/README.md`](intellij-plugin/README.md) for full details.

## 8. Repo layout
- `protocol/` — Shared message definitions and binary protocol.
- `runtime-client/` — Android AAR injected into apps: startup init, JVMTI agent `.so`, socket server, and Compose bridge shims.
- `engine/` — JVM library: orchestrator for watcher, Gradle compiler, class diffing, D8 dexing, and client protocol.
- `cli/` — Thin CLI wrapper around the engine.
- `bootstrap/` — Java 17, AGP-free Gradle bootstrap used by explicit zero-touch mode.
- `gradle-plugin/` — Gradle plugin that wires the runtime-client into debug builds and sets compiler flags.
- `intellij-plugin/` — IntelliJ IDEA / Android Studio plugin (status bar widget, CLI spawner).
- `samples/` — Example apps (single-module and multi-module).
- `e2e/` — Scripted emulator end-to-end tests.
- `docs/` — Project plan, findings, and architectural notes.
- `tasks/` — Task specifications and progress tracking.

## 9. Status
**0.2.0 — next unified release (in validation).** Its GitHub Release and JitPack artifacts will
align the CLI, Gradle plugin, runtime client, and IntelliJ/Android Studio plugin after authorized
publication. Until then, the published Marketplace plugin is
[0.1.8](https://plugins.jetbrains.com/plugin/32850-compose-hot-reload). The stable contract is
explicit configured-plugin integration and matching profiles; zero-touch and live literals remain
experimental.

**0.1.6 — security hardening + project-agnostic release.** Adds socket peer-uid authorization and debuggable-only enforcement in the runtime client, project-agnostic configuration with Gradle discovery and IDE profiles, zero-touch `hotreload start`, and IDE plugin 0.1.6 (pre-Start environment preflight with surfaced fatal errors, Android SDK auto-discovery, bundled CLI).

**0.1.0 — first public release.** Hot reload supports single-module and multi-module applications, resource edits, the AOSP LiveEdit interpreter for structural changes, and composable signature changes via lambda proxies. See the [Project Plan](docs/PLAN.md) for future milestones.

## 10. Troubleshooting
If hot reload fails to connect or experience issues on device, run `hotreload doctor` to verify your environment, SDK toolchain, device status, project configuration, and runtime handshake:
```bash
./gradlew -q :cli:run --args="doctor --project $PWD/samples/single-module --app-id dev.hotreload.sample"
```

* **APK Selection**: The swapper locates the active APK using AGP's `output-metadata.json` (logged as `apk: … (output-metadata.json)`). If the metadata is absent or unreadable, it logs a warning (`apk: no matching output-metadata.json — newest-APK fallback may pick a stale variant`) and falls back to recursive newest-mtime search, which can pick a stale variant APK.

* **`fingerprint: MISMATCH`**: `watch`/`start` refuses because the APK installed by `hotreload prepare` was built for a different configuration than the one you are now watching. The canonical cause is a live-literals mismatch — the APK was `prepare`d with `--literals` but you are running `watch` without it, or vice versa; swapping in that state silently corrupts the Compose slot table. The message names every differing field. Fix it by re-running matching `hotreload prepare`, relaunching, and restarting. `--ignore-fingerprint` is diagnostic only, not the normal recovery.

## 11. Live-literals fast path (experimental, opt-in)
Editing only a constant inside a composable — a plain string, number, boolean, or char — can skip Gradle + d8 + class redefinition entirely and push the new value straight into Compose's live-literals mechanism, for a sub-100ms update. It is **off by default** because the Compose compiler's live-literals instrumentation adds overhead to debug builds.

In configured mode, build the app with the property and watch with `--literals`:
```bash
./gradlew :app:installDebug -Photreload.liveLiterals=true
./gradlew -q :cli:run --args="watch --literals --project $PWD/samples/single-module --app-id dev.hotreload.sample"
```
In zero-touch mode the CLI supplies the matching compiler option itself, so use the same mode and
flag for preparation and watching (or let `start` do both):

```bash
hotreload start --zero-touch --literals --project /path/to/your/project
```

`watch --literals` fails fast if the installed app wasn't built with the property. Only single, contiguous literal edits take the fast path; anything else (template strings, `const val`, structural edits, multi-file saves) falls back to the normal compile-and-swap path automatically.
