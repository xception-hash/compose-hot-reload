# Compose Hot Reload — IDE plugin settings

Every field in **Settings › Tools › Compose Hot Reload** (IntelliJ IDEA / Android Studio). Each
field maps to one argument the plugin passes to the bundled `hotreload` CLI, except where noted.
The **Resolved command** box at the bottom of the settings panel always shows the exact token
command **Start** will run — use it to confirm what your settings produce.

## TL;DR — the minimum

For most projects you set **nothing** and press **Refresh discovery**: the plugin inspects your
Gradle build and fills in the app module, variant, application id, and watched-module closure. If
discovery can't run, the two fields you'll usually need are **Application id** and **SDK path**.

Two gotchas that bite first-run users on macOS:
- **Use absolute paths** in path fields — `~` is **not** expanded. Write `/Users/you/Library/Android/sdk`, not `~/Library/Android/sdk`.
- A GUI-launched IDE often has no `ANDROID_HOME`, so **SDK path** may need to be set explicitly.

## Discovery (the "Refresh discovery" button)

Runs `hotreload inspect` against your project (Gradle, off the UI thread) and populates the
combo boxes: **App module**, **Variant**, **Application id**, and **Watched modules**. The combos
stay editable — if discovery fails or your build is unusual, type values by hand. Run it once after
opening a project, and again whenever you add/rename a module or variant. It never modifies your
build.

**Known 0.1.8-candidate issue:** on a large build that emits enough Gradle diagnostics, the current
plugin can remain on `Discovering…` because it drains the inspection process's stdout before its
stderr. This is tracked as T40. Until that fix ships, do not press Refresh repeatedly. Run the
plugin's bundled `cli inspect --project <dir> --json` command (add `--zero-touch` when selected)
from a terminal and enter the returned app module, variant, application id, and module closure in
the editable fields. This note should be removed when T40 passes its large-target IDE gate.

## Fields

| Field | CLI flag | What it does | Leave blank / default |
|-------|----------|--------------|-----------------------|
| **CLI launcher** | *(none — process path)* | Path to a CLI launcher to run instead of the one bundled in the plugin. For contributors pointing at a local `:cli:installDist` build (`…/cli/build/install/cli/bin/cli`). | Blank = use the CLI bundled inside the plugin (normal case). |
| **Project dir** | `--project` | Gradle root of the app you want to hot-reload. | Blank = this IDE project's base path. |
| **Profile** | `--profile` | Loads defaults from a named `hotreload configure` profile (`~/.config/compose-hot-reload/projects/<name>.toml`). Any explicit field below **overrides** the profile. | Blank = no profile. |
| **App module** | `--app-module` | Which watched module is the Android **application** module, when it isn't the first one in *Watched modules*. Pick a discovered module or type a Gradle path (e.g. `:app`). | Blank = the first entry in *Watched modules*. |
| **Variant** | `--variant` | The Android build variant to compile and install (e.g. `debug`, `stagingDebug`). Discovery offers only **debuggable** variants; type a custom one if needed. | Blank = `debug`. |
| **Application id** | `--app-id` | The `applicationId` of the installed debug build on the device (e.g. `com.example.app`). Used to find the process and open the runtime socket. | Discovered automatically **only when both this and *Watched modules* are left at defaults**; otherwise required (see note below). |
| **Watched modules** | `--module` | Comma-separated Gradle modules to watch; the **first is the app module**. Map a Gradle path to a physical dir with `=`, e.g. `:app=applications/main,:core=libs/core`. Refresh proposes the discovered dependency closure. | Default `app`. **Setting this disables auto-discovery** of app-id (see note). |
| **Target project JDK** | `--project-java-home` | JDK used to run the **target project's** Gradle build. Set this if your project needs a different JDK than the one running the CLI. | Blank = the CLI's own JVM. |
| **Device serial** | `--device` | adb serial to target when more than one device/emulator is connected (e.g. `emulator-5554`). | Blank = the single connected device (fails preflight if 0 or >1). |
| **SDK path** | `--sdk` | Android SDK root (the dir containing `platform-tools/adb` and `build-tools/<v>/d8`). | Blank = `$ANDROID_HOME`. Often must be set for GUI-launched IDEs — use an **absolute** path. |
| **Gradle args** | `--gradle-arg` (repeatable) | One exact Gradle argument per line; each is forwarded to the target build (e.g. `-PsomeFlag=true`). | Empty. |
| **Advanced raw overrides** | *(appended verbatim)* | Escape hatch: one exact CLI token per line, appended to the command. Never shell-split. For flags with no dedicated field (e.g. `--build-tools`, `--launch-activity`, `--exclude-module`). | Empty. |

### Options (checkboxes)

| Checkbox | CLI flag | What it does |
|----------|----------|--------------|
| **Enable live-literal fast path** | `--literals` | Turns on the live-literals fast path (T24). Requires the app built with `-Photreload.liveLiterals=true`. Faster updates for literal changes; leave off unless you've enabled that build flag. |
| **Use zero-touch bootstrap** | `--zero-touch` | Instruments the app through the plugin's bundled init-script + runtime AAR **without editing your project's** `settings.gradle`, module build files, or sources. Use this when you don't want to (or can't) apply the `dev.hotreload` Gradle plugin manually. |
| **Skip environment preflight (advanced)** | *(plugin-side only)* | Skips the pre-Start `hotreload doctor` environment check and launches directly. Use only if the preflight misreports a working setup; you lose the actionable diagnostics. |

## Notes & common pitfalls

- **App-id auto-discovery is all-or-nothing.** The CLI only discovers the app module + application
  id when **both** `--app-id` **and** `--module` are omitted. Because the plugin always sends
  *Watched modules* (default `app`), you will typically also need to set **Application id** — or
  press **Refresh discovery**, which fills it for you.
- **Paths are literal.** `~`, `$ANDROID_HOME`, and other shell syntax are **not** expanded in the
  path fields (SDK path, CLI launcher, Project dir, Target project JDK). Use absolute paths.
- **The preflight is a soft gate.** If the environment check finds problems it shows a warning with
  a **"Start anyway"** button — it never hard-blocks. Fixing the listed items is preferred.
- **Resolved command** mirrors your settings live. When in doubt about what a field does, watch how
  that box changes as you edit — and you can copy it to run the same command in a terminal.

## Equivalent terminal command

Everything here maps to a `hotreload watch` / `start` invocation. The full CLI flag reference lives
in the root `README.md` ("CLI reference"). Example the settings above produce:

```
hotreload watch --project /path/to/project --module app \
  --app-id com.example.app --variant debug --sdk /path/to/Android/sdk
```

See also: `README.md` (quickstart + CLI table), `docs/project-configuration.md`,
`docs/WORKFLOW.md`.
