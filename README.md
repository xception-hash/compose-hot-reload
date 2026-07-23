# Compose Hot Reload for Android

Save a Jetpack Compose file and see the UI update on a real Android device or emulator in about a
second, while the app process and most state stay alive. Compose Hot Reload requires Gradle
configuration, but no application-source or manual runtime-initialization changes.

> [!IMPORTANT]
> The stable setup is **configured mode**: apply the `dev.hotreload` Gradle plugin, review and save
> an explicit CLI profile, run matching `prepare` and `doctor`, then start one watcher. Zero-touch
> setup and live literals are experimental.

## 1. What it does

Compose Hot Reload uses a custom JVMTI agent in debuggable apps to redefine classes in place,
inject new classes, and ask Compose to recompose the affected groups. The CLI compiles and dexes
only the changed code, then sends the patch to the selected device. Use it directly from a terminal
or let the IntelliJ/Android Studio plugin run the watcher.

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

## 2. Choose a setup path

The integration mode and the interface are separate choices:

| Choice | Recommended use | What you must do |
|---|---|---|
| **Configured mode + CLI** | Stable setup, automation, and AI agents | Apply the Gradle plugin, create an explicit configured profile, then use CLI-owned `prepare`, `doctor`, and `watch`/`start`. |
| **Configured mode + IDE plugin** | Stable daily use from Android Studio or IntelliJ | Complete the same one-time configured CLI setup, then let the IDE plugin run `doctor` and `watch`. |
| **Zero-touch mode** | Experimental evaluation when target build-file edits are temporarily impossible | Use matching `--zero-touch` preparation and watching. Do not mix it with configured mode. |
| **AI-assisted setup** | Existing or unusual Gradle projects | Follow the [AI project setup and recovery guide](docs/ai-project-setup.md). |

The CLI owns the installed APK baseline. An ordinary Android Studio `installDebug` followed by a
profile is not the stable path.

### Capability tiers

| Tier | What it means |
|---|---|
| **Stable** | Configured Gradle-plugin integration; explicit app/module/variant configuration; profiles; `doctor`; `prepare`; `start`; `watch`; `config show`; target JDK/device/activity and explicit module mappings. |
| **Setup helper** | `inspect`, `configure`, automatic discovery and include/exclude filters suggest a setup. Review their result and pin explicit values if it is wrong. |
| **Advanced** | `--gradle-arg`, `--sdk`, `--build-tools`, and non-default variants are valid when deliberately pinned identically in preparation and watching. |
| **Experimental** | `--zero-touch` and `--literals` remain available and tested, but are not release-blocking onboarding paths. |
| **Diagnostic escape hatch** | `--ignore-fingerprint` can permit an APK/class baseline mismatch and Compose state corruption. Use matching `prepare` instead. |
| **Machine-readable** | `inspect --json` is schema-v1 output for tools and agents; it is not another integration mode. |

## 3. Requirements

- One API-30+ emulator or physical device selected in adb state `device`.
- A debuggable Android variant. The runtime is deliberately absent from non-debuggable variants.
- Android SDK build-tools 36.0.0, including `d8`; `dexdump` is needed for bitmap invalidation.
- JBR 21 for the CLI/source and IDE workflows.
- A full target-project JDK, selected independently with `--project-java-home` when needed.

### Tested lanes

| Android build | Kotlin | Target Gradle JDK |
|---|---|---|
| AGP 8.12.x | Standalone Kotlin 2.3.x | Full JDK 17 |
| AGP 9.2.x | Built-in Kotlin / Kotlin 2.4.x | JDK or JBR 21 |

Azul, Temurin, and other conforming full JDKs are acceptable when their major version matches the
target lane and both `bin/java` and `bin/javac` exist. The CLI/source and IDE workflows use Android
Studio's JBR 21; `--project-java-home` independently selects the target project's Gradle JDK.
Kotlin DSL is tested. Groovy scripts, multiple flavor dimensions, included-build application
modules, multiple devices, and unlisted toolchains are best effort rather than rejected. See
[Project configuration and compatibility](docs/project-configuration.md) for non-default layouts.

## 4. Stable Quickstart: configured Gradle plugin

The stable setup has four stages:

1. Get a CLI launcher.
2. Apply the Gradle plugin to the target project.
3. Review discovery and save an explicit profile.
4. Use that profile for `prepare`, `doctor`, and `watch`.

### 4.1 Get a CLI launcher

The examples below use `hotreload` as shorthand for your chosen launcher.

| Installation | Launcher |
|---|---|
| [GitHub Release 0.2.0](https://github.com/xception-hash/compose-hot-reload/releases/tag/0.2.0) | Unzip `cli.zip`; run `cli/bin/cli` on macOS/Linux or `cli\bin\cli.bat` on Windows. |
| Source checkout | Run `./gradlew -q :cli:run --args="<command and options>"`. |
| Marketplace IDE plugin | Includes a private bundled CLI for IDE actions, but does not add a global `hotreload` command. Use the release CLI for one-time `configure`/`prepare`, then the IDE plugin for daily watching. |

For a source checkout on macOS:

```bash
git clone https://github.com/xception-hash/compose-hot-reload.git
cd compose-hot-reload
unset JAVA_HOME
source scripts/env.sh
./gradlew -q :cli:run --args="--help"
```

### 4.2 Integrate the target project

Add JitPack and plugin resolution to the target root's `settings.gradle.kts`. Preserve its existing
repositories and plugins:

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

Apply the plugin in the Android app and every Android-library or Kotlin/JVM module whose code you
want to watch:

```kotlin
plugins {
    id("dev.hotreload") version "0.2.0"
}
```

The plugin adds the matching runtime-client AAR to debuggable builds; do not add that AAR directly
or to a release/non-debuggable configuration.

### 4.3 Create and review a profile

Start with discovery, then treat its result as a suggestion:

```bash
hotreload configure --project /path/to/your/project --save-as my-app
hotreload config show --profile my-app
```

#### How profiles work

- `configure --save-as <name>` resolves the project and writes a schema-versioned TOML profile
  outside the target repository. By default it lives at
  `~/.config/compose-hot-reload/projects/<name>.toml`; `XDG_CONFIG_HOME` or
  `HOTRELOAD_CONFIG_DIR` can relocate the configuration root.
- A profile records the absolute project path, application ID, variant, ordered module-directory
  mappings, per-module variants, Gradle arguments, target-project JDK, device, launch activity,
  live-literals choice, and configured versus zero-touch integration mode. When discovery
  metadata is available, `configure` also writes a `<name>.discovery.json` sidecar used to recover
  module metadata on later commands.
- `--profile <name>` supplies defaults to `inspect`, `prepare`, `doctor`, `watch`, and `start`.
  Explicit scalar or repeatable command-line options replace the corresponding profile values for
  that invocation. `--literals` and `--zero-touch` are enable-only flags: they can enable a mode
  for an invocation but cannot disable a mode already stored in the profile.
- `config show --profile <name>` prints the stored TOML and an expanded `watch` command. Running
  `configure` again with the same `--save-as` name replaces the profile and refreshes its discovery
  sidecar. Prefer separate named profiles for different variants or experimental modes rather
  than repeatedly overriding APK-shaping values.

Verify the application ID, app module, variant, watched-module closure, physical directories,
target JDK, device, and launch activity. If discovery is wrong, run `configure` again with explicit
options. For example:

```bash
hotreload configure \
  --project /path/to/your/project \
  --save-as my-app \
  --app-id com.example.app.debug \
  --app-module :app \
  --module :app=applications/main,:feature=features/feature \
  --variant debug \
  --project-java-home /path/to/jdk-17 \
  --device emulator-5554
```

Profiles do not store `--sdk` or `--build-tools`; if you override either, repeat it identically for
`prepare`, `doctor`, and `watch`/`start`. See
[Project configuration and compatibility](docs/project-configuration.md) for flavors, module
mappings, Gradle arguments, and other non-default layouts.

### 4.4 Prepare, verify, and watch

Use the same profile for every command:

```bash
hotreload prepare --profile my-app
hotreload doctor --profile my-app
hotreload watch --profile my-app
```

Wait for `watching …` before editing. A successful save prints `changed:` followed by one of
`hot-swapped:`, `interpreted:`, `resource-swapped:`, or `literal-pushed:`. Press Ctrl-C to stop the
watcher.

After the profile has been reviewed, `hotreload start --profile my-app` is the shorter daily
command: it runs Doctor, prepares when necessary, and then watches.

### 4.5 Use the IDE plugin after setup

Install [Compose Hot Reload from JetBrains Marketplace](https://plugins.jetbrains.com/plugin/32850-compose-hot-reload),
then open **Settings › Tools › Compose Hot Reload**:

1. Complete sections 4.2–4.4 once with the release CLI. The IDE settings page cannot create a CLI
   profile or run `prepare` in version 0.2.0.
2. Enter the profile name, or pin the same explicit app/module/variant/JDK/device values shown by
   `config show`. Explicit IDE fields override profile values.
3. Click **Refresh discovery**, review the suggestions, and click **Apply**.
4. Click the status-bar widget or **Tools › Start Hot Reload**. Start runs a Doctor preflight and
   then `watch`; it does not install or prepare the APK.
5. Wait for **Ready** before saving and click **Stop** when finished.

See the [IDE settings guide](docs/ide-plugin-settings.md) for every field and the
[IDE plugin README](intellij-plugin/README.md) for contributor instructions.

## 5. CLI reference

`help` and `--help` print this reference. `watch`, `prepare`, `start`, `doctor`, `inspect`, and
`configure` require a project directly or through a profile; `config show` requires a profile.
`prepare` and `watch` must use the same APK-shaping inputs (integration mode, modules, variant,
JDK, Gradle arguments, and literals choice); `start` maintains that parity for one profile.

### Commands

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

### Stable configuration

| Command or option | Applies to | Tier | Changes / when needed | Common failure and safe recovery |
|---|---|---|---|---|
| `--project <dir>` | all except `config show` | Stable | Target Gradle root. | Wrong root/no classes: select the wrapper/settings root. |
| `--profile <name>` | inspect/doctor/prepare/start/watch/config show | Stable | Loads a reviewed saved profile; explicit flags override it. | Parity mismatch: use one profile or rerun matching prepare. |
| `--save-as <name>` | configure | Stable | Names the saved profile. | Invalid/missing name: use letters, digits, `.`, `_`, `-`, with no `..`. |
| `--app-id <id>` | configure/doctor/prepare/start/watch | Stable | Installed debuggable application ID. | Socket/app not found: verify selected variant's application ID and prepare again. |
| `--app-module <path>` | configure/doctor/prepare/start/watch | Stable | Declares which watched module is the Android app. | Non-AGP module: correct the app module or module ordering. |
| `--app-module-dir <dir>` | configure/doctor/prepare/start/watch | Stable | Physical directory override for the app module. | Metadata/classes absent: use the actual module directory. |
| `--module <names>` | configure/doctor/prepare/start/watch | Stable | Ordered watched modules; first is app unless overridden; supports `:path=dir`. | Missing library edits: include the reachable owner and run matching prepare. |
| `--module-variant <path=variant>` | configure/doctor/prepare/start/watch | Stable | Per-module variant override. | Variant task missing: inspect Gradle's real variant and correct mapping. |
| `--project-java-home <dir>` | inspect/configure/doctor/prepare/start/watch | Stable | Full JDK for target Gradle, separate from CLI JBR. | Java/Gradle error: verify both `bin/java` and `bin/javac`, then correct path. |
| `--device <serial>` | configure/doctor/prepare/start/watch | Stable | Selects one adb device. | Zero/multiple devices: connect one or pass the correct serial. |
| `--launch-activity <name>` | configure/doctor/prepare/start/watch | Stable | Activity to launch if app is stopped. | Launch fails: supply the target launcher activity and prepare again. |

### Discovery, advanced, and experimental options

| Command or option | Applies to | Tier | Changes / when needed | Common failure and safe recovery |
|---|---|---|---|---|
| `--include-module <path>` | configure/doctor/prepare/start/watch | Setup helper | Restricts discovered closure while retaining the app; requires implicit discovery. | Library/resource unseen: remove restriction or include its owner, then prepare. |
| `--exclude-module <path>` | configure/doctor/prepare/start/watch | Setup helper | Drops a discovered module; requires implicit discovery. | Needed edits disappear: remove exclusion and prepare. |
| `--json` | inspect | Machine-readable | Emits schema-v1 discovery JSON for tools. | Parse/schema issue: consume only documented JSON, or use human inspect output. |
| `--variant <name>` | configure/doctor/prepare/start/watch | Advanced | Global debuggable build variant. | Release/non-debuggable build: select a debuggable variant. |
| `--gradle-arg <arg>` | inspect/configure/doctor/prepare/start/watch | Advanced | Repeats an exact target Gradle argument. | APK differs: pin it in the profile and run matching prepare/watch. |
| `--sdk <dir>` | doctor/prepare/start/watch | Advanced | Android SDK root; not stored in profiles. | Missing d8/dexdump: correct the path and repeat it across session commands. |
| `--build-tools <v>` | doctor/prepare/start/watch | Advanced | Chooses installed build-tools; not stored in profiles. | Tool absent: install/select that version and repeat it across session commands. |
| `--zero-touch` | inspect/configure/doctor/prepare/watch/start | Experimental | Bundled init-script integration without target edits. | Composite/lifecycle/parity issue: return to configured mode or use matching zero-touch prepare/watch. |
| `--literals` | configure/prepare/watch/start/doctor | Experimental | Enables live-literal compiler/runtime path. | Slot-table/fingerprint mismatch: stop and prepare/watch with the same literals choice. |
| `--ignore-fingerprint` | watch/start | Diagnostic escape hatch | Skips a known APK fingerprint mismatch for diagnosis only. | Possible state corruption: stop, matching prepare, relaunch, restart; do not keep using the override. |

## 6. What hot-reloads today

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
device (see [Phase 6 research](docs/phase6-interpreter-research.md), section 7).
**Remaining rebuild cases:** `<clinit>` changes, constructor changes on non-lambda classes, field
add/remove/type-change, Compose group-key renumbering, and suspend-lambda constructor changes.

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

The state-preservation semantics match Android Studio's Live Edit:
`invalidateGroupsWithKey` discards `remember` and `rememberSaveable` state in the **edited
function's subtree** because the runtime must re-run initializers that may capture new code.
Editing a leaf preserves everything else; editing a parent resets its children's counters.

### Broken-edit behavior

If an edit does not compile, fix it and save again; the watcher remains alive. If edited Compose
code throws during recomposition, the watcher reports the source location and the UI keeps its
last-good frame. Fixing and saving recovers in place without a reinstall.

## 7. Limitations

- **`<clinit>` / static-initializer edits** cause a full rebuild (ART cannot re-run class initializers).
- **Constructor edits** on non-lambda classes cause a full rebuild (existing object instances cannot be re-constructed).
- **Compose group-key renumbering** (reordering composable calls) causes a full rebuild because the
  invalidation key map becomes stale.
- **Field removal / field type change** causes a full rebuild; ART's structural redefinition can
  only add fields.
- **`ImageBitmap.imageResource()` call sites are not covered** by bitmap drawable hot reload.
  Unlike `painterResource`, they have no recomposition group for the engine to target, so edits
  remain stale until a full reinstall.
- **Nine-patch (`.9.png`) drawables are not supported.** Compose's `painterResource()` bitmap path
  expects `BitmapDrawable`, but aapt2 produces `NinePatchDrawable`, causing a `ClassCastException`
  even on a plain install.
- **Font resources (`res/font/*.ttf`/`.otf`) are not hot-reloaded.** The watcher does not include
  font extensions; use a full reinstall.
- **Debuggable builds only.** ART's JVMTI agent attachment and class redefinition require
  `android:debuggable="true"`.
- **Minimum API 30** (Android 11). Structural class redefinition is unavailable below API 30.
- **Compiled-callee exceptions skip interpreted try/catch.** When interpreted code calls a
  compiled method that throws, the exception crosses a native boundary and cannot be caught by the
  interpreter's try/catch handler. See the
  [interpreter limitations](docs/phase6-interpreter-research.md), section 5.
- **`@Composable` signature changes may reset the enclosing subtree's state.** A signature change
  also changes and invalidates its caller, so that parent's `remember` and `rememberSaveable`
  subtree state resets.
- **Suspend-lambda constructor changes** cause a full rebuild because suspend continuations have
  structural constraints the proxy path cannot satisfy.

## 8. Experimental zero-touch and live literals

### Zero-touch mode

`--zero-touch` instruments a selected debuggable build through a bundled init script without
editing target project files. Init-script/composite-build behavior, reflective AGP/Kotlin
lifecycle handling, exact preparation/watch parity, and included-build boundaries make it an
experimental evaluation path rather than normal setup or recovery.

Create a separate profile so modes cannot be mixed accidentally:

```bash
hotreload configure \
  --zero-touch \
  --project /path/to/your/project \
  --save-as my-app-zero-touch
hotreload config show --profile my-app-zero-touch
hotreload start --profile my-app-zero-touch
```

### Live-literals fast path

`--literals` can push a single edited string, number, boolean, or character in under 100 ms. It is
off by default because the Compose compiler instrumentation adds debug-build overhead.

Enable it in a **separate profile** and use that profile for the entire APK lifecycle:

```bash
hotreload configure \
  --project /path/to/your/project \
  --literals \
  --save-as my-app-literals
hotreload prepare --profile my-app-literals
hotreload doctor --profile my-app-literals
hotreload watch --profile my-app-literals
```

The same profile and `--literals` choice must build, install, and watch the APK. A mismatch can
corrupt Compose's slot table. Stop and run matching `prepare` if the fingerprint, protocol,
integration mode, or literals choice differs; do not lead with `--ignore-fingerprint`.

Only a single contiguous literal edit takes the fast path. Template strings, `const val`,
structural edits, and multi-file saves automatically use the normal compile-and-swap path.

## 9. Troubleshooting

Start with the exact profile that prepared the app:

```bash
hotreload doctor --profile my-app
```

| Symptom | Safe recovery |
|---|---|
| `fingerprint: MISMATCH` | Stop, run `prepare` with the same profile, relaunch, and restart the watcher. |
| Protocol or runtime integration mismatch | Re-run matching `prepare`; do not mix configured and zero-touch APKs. |
| No `watching …` line | Fix Doctor/prepare failures. Do not edit during the initial build or start a second watcher. |
| `compile failed — fix and save again` | Fix the source and save; keep the watcher running. |
| `recomposition failed:` | Fix the runtime error and save; the last-good frame remains on screen. |
| `cannot hot-swap:` or `run a full install …` | Stop and run matching `prepare`, then restart the watcher. |
| No `changed:` line after a save | Confirm the file belongs to a watched module and uses a supported extension. Fonts require a full reinstall. |
| App does not launch after prepare | Pin the real launcher component with `--launch-activity`, reconfigure, and prepare again. |
| Zero or multiple devices | Connect one device or pin `--device <serial>` in the profile. |

The swapper normally selects the active APK through AGP's `output-metadata.json`, logged as
`apk: … (output-metadata.json)`. If metadata is absent or unreadable, it warns that newest-file
fallback may pick a stale variant. Pin the correct variant and remove stale ambiguity before
preparing again.

For machine-checkable watcher signals and exact failure text, see [`AGENTS.md`](AGENTS.md).

## 10. Use an AI agent

Read the [AI project setup and recovery guide](docs/ai-project-setup.md) and give your agent the
copy/paste prompt there. It tells the agent to inventory the target before editing, preserve the
project's JDK, Gradle structure/DSL, versions, variants, module layout, and mixed Java/Kotlin
sources, create a reviewed configured profile, and make only the smallest required integration
diff. It is intentionally a project-adaptation workflow rather than a catalog of hard-coded setup
recipes. [`AGENTS.md`](AGENTS.md) supplies the non-interactive readiness and log contract.

## 11. IDE plugin

The [JetBrains Marketplace plugin](https://plugins.jetbrains.com/plugin/32850-compose-hot-reload)
bundles the watcher CLI and provides:

- A status-bar Start/Stop widget with Off, Starting, Ready, Reloading, Error, and Rebuild-needed
  states.
- **Tools › Start/Stop Hot Reload**.
- **Settings › Tools › Compose Hot Reload** with discovery, profile/configuration fields, target
  JDK, device, SDK, experimental options, Gradle arguments, and the resolved command.
- Failure and rebuild-needed notifications.

The IDE plugin does not prepare or install the app. Complete the stable CLI setup first, then use
the IDE for daily watching. See [IDE plugin settings](docs/ide-plugin-settings.md) for end-user
instructions and the [plugin README](intellij-plugin/README.md) for build/development details. The
signed 0.2.0 ZIP from the GitHub Release is also available for installation from disk.

## 12. Repository layout

- `protocol/` — Shared message definitions and binary protocol.
- `runtime-client/` — Android AAR with startup initialization, JVMTI agent, socket server, and
  Compose bridge.
- `engine/` — Watcher orchestration, Gradle compilation, class diffing, D8, and device protocol.
- `cli/` — Thin command-line interface over the engine.
- `bootstrap/` — AGP-free bootstrap used by experimental zero-touch mode.
- `gradle-plugin/` — Debug runtime wiring and required compiler flags.
- `intellij-plugin/` — IntelliJ/Android Studio status and CLI launcher.
- `samples/` — Single-module and multi-module example apps.
- `e2e/` — Scripted emulator end-to-end tests and documentation contracts.
- `docs/` — Setup, compatibility, design, and implementation notes.
- `tasks/` — Decision-complete task specifications and outcomes.

## 13. Status

**0.2.0 — published on GitHub, JitPack, and JetBrains Marketplace.** The
[GitHub Release](https://github.com/xception-hash/compose-hot-reload/releases/tag/0.2.0) provides
the CLI and signed IDE ZIP. The Gradle plugin and runtime AAR resolve from
[JitPack](https://jitpack.io/#xception-hash/compose-hot-reload/0.2.0), and the matching IDE plugin
is on [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/32850-compose-hot-reload).

The stable contract remains explicit configured-plugin integration with matching profiles.
Zero-touch and live literals remain experimental. See the [Project Plan](docs/PLAN.md) for release
evidence and future work.
