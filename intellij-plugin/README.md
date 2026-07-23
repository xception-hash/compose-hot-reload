# Compose Hot Reload — IntelliJ / Android Studio plugin

Drive a prepared Compose Hot Reload session without leaving the IDE. The plugin spawns the bundled
CLI's `watch` command and reflects its state in the status bar. It does not embed the engine; CLI
log lines remain the machine-readable integration surface.

It targets IntelliJ IDEA Community but uses only core platform APIs, so it also loads in
Android Studio.

Install the currently published plugin from
[JetBrains Marketplace](https://plugins.jetbrains.com/plugin/32850-compose-hot-reload) through
**Settings › Plugins**. Marketplace version 0.2.0 includes the CLI, so IDE discovery, Doctor, and
watch need no repository clone or manual `installDist`. The signed 0.2.0 ZIP from the
[GitHub Release](https://github.com/xception-hash/compose-hot-reload/releases/tag/0.2.0) is an
alternative for installation from disk. During local development, install the built ZIP from disk.

Marketplace installation includes the CLI for IDE actions, but does not add a global `hotreload`
command. Version 0.2.0 does not expose `configure` or `prepare` as IDE actions.

## First use after Marketplace installation

1. Open the Android project and apply the `dev.hotreload` Gradle plugin to the app and every
   watched code module.
2. Use the 0.2.0 release CLI to run `configure`, review `config show`, and save an explicit
   configured profile.
3. Run matching `prepare` and `doctor` with that profile. This installs, launches, fingerprints,
   and verifies the debug APK on one API-30+ device.
4. Open **Settings › Tools › Compose Hot Reload**, enter the existing profile name, click
   **Refresh discovery**, and review every explicit field. Click **Apply**.
5. Click the status-bar widget or **Tools › Start Hot Reload** and wait for **Ready** before
   saving. Click **Stop** before changing configuration or ending the session.

**Start launches `watch`, not `start`.** It runs a Doctor preflight but does not prepare or install
the app. Re-run matching CLI `prepare` after changing integration mode, modules, variant, target
JDK, Gradle arguments, or live-literals choice.

For field-by-field help, see the public [IDE settings guide](../docs/ide-plugin-settings.md) and
[AI-assisted setup guide](../docs/ai-project-setup.md).

## What it gives you

- **Status-bar widget** — `Hot Reload: off / starting… / ready (Nms) / reloading… / error(n) /
  rebuild needed`. Click it to Start/Stop.
- **Tools ▸ Start/Stop Hot Reload** — same toggle from the menu.
- **Settings ▸ Tools ▸ Compose Hot Reload** — structured, persisted watch settings: project,
  existing profile, app module/variant/application ID, watched modules, target JDK, device, SDK,
  literals, zero-touch, and repeatable Gradle arguments. **Refresh discovery** runs the CLI's
  non-mutating `inspect --json` and fills editable choices for debuggable app variants and their
  module closure. The page also shows the read-only resolved command that Start will execute.
- **Balloons** on failure: a reload error (tooltip = first compiler/recompose error) and a
  rebuild-required notice (with the reinstall hint).

The widget state is derived purely by parsing CLI stdout/stderr in
[`CliProtocol.kt`](src/main/kotlin/dev/hotreload/idea/CliProtocol.kt) — the only coupling to the
engine. Line prefixes there are copied verbatim from `engine/.../WatchSession.kt` and
`ResourceSwapper.kt`.

## Prerequisites

- Complete the root [stable configured Quickstart](../README.md#4-stable-quickstart-configured-gradle-plugin).
- **Android SDK build-tools 36.0.0** — the CLI shells out to `d8`/`dexdump` from the Android
  SDK. Set `ANDROID_HOME` or configure the **SDK path** field in
  Settings ▸ Tools ▸ Compose Hot Reload.

## Bundled CLI

The plugin zip includes the full CLI distribution (`cli/bin/cli`, `cli/lib/*.jar`). When
installed from disk or from the Marketplace, the plugin resolves the bundled launcher
automatically for discovery, Doctor, and watch — no repo clone or manual `installDist` is required
for those IDE actions.

To override the bundled CLI (e.g. for local development), set the **CLI launcher** path in
Settings ▸ Tools ▸ Compose Hot Reload to point at your own build.

## Build & test

```bash
cd intellij-plugin
./gradlew test          # runs the CliProtocol unit tests (fixtures under src/test/resources)
./gradlew buildPlugin   # produces build/distributions/hotreload-intellij-plugin-<v>.zip
```

`buildPlugin` automatically runs `:cli:installDist` from the parent project via a Gradle
composite build — no separate step is needed. The CLI distribution is copied into the plugin
zip under `cli/`.

`test`/`buildPlugin` download the IntelliJ IDEA Community distribution the first time
(unavoidable for a platform plugin) — needs network.

## Run it (dev / manual)

```bash
# 1. Produce the CLI launcher the plugin will spawn:
cd /path/to/compose-hot-reload
./gradlew :cli:installDist        # → cli/build/install/cli/bin/cli

# 2. Launch a sandbox IDE with the plugin loaded:
cd intellij-plugin
./gradlew runIde
```

In the sandbox IDE:

1. Open a sample project (e.g. `samples/single-module`).
2. In **Settings ▸ Tools ▸ Compose Hot Reload**, set a project dir (or leave it blank for the open
   project), then click **Refresh discovery**. Select the discovered app module and debuggable
   variant; the app id and watched-module closure are suggestions and remain editable. Review and
   select an existing configured profile before Start. The settings page does not create a CLI
   profile. The CLI launcher defaults to the bundled one; override it only to test a different
   build.
3. **Advanced raw overrides** are an escape hatch for unsupported flags. Enter one exact token per line;
   they are appended after structured arguments and are never parsed as a shell string. Likewise,
   enter one target Gradle argument per line.
4. Run matching CLI `prepare` and `doctor`; the plugin does not install the app.
5. Click the status-bar widget (or **Tools ▸ Start Hot Reload**) → it goes
   `starting… → ready`.
6. Edit a composable body and save → `reloading… → ready (Nms)` and the emulator updates.
7. Make an edit that requires a full rebuild → **rebuild-needed** balloon.
8. Stop from the widget/menu → the process is destroyed; `pgrep -f dev.hotreload.cli.MainKt`
   should be empty.

## Install from disk (release IDE)

`Settings ▸ Plugins ▸ ⚙ ▸ Install Plugin from Disk…` → pick the zip from
`build/distributions/`.

## Known limits

- **No auto-save hook.** The CLI's own file watcher handles saves. Enable the IDE's explicit
  auto-save (Settings ▸ Appearance & Behavior ▸ System Settings ▸ *Save files automatically…*)
  or save manually; relying on save-on-frame-deactivation adds latency.
- **No editor gutter icons, no in-editor error banners, no auto-start, no run-configuration
  integration.** Status is status-bar + balloons only.
- **One session per project.** Start is a no-op while a session is running.
- **No configure or prepare action.** The IDE can discover values, run Doctor, and watch, but the
  stable first-time profile and APK baseline are CLI-owned.
- **Explicit fields override profiles.** The default watched-modules value `app` can override a
  wider multi-module profile; match the `config show` output before Start.
