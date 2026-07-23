# Compose Hot Reload — IntelliJ / Android Studio plugin (MVP)

Phase 5 MVP: drive hot reload without leaving the IDE. The plugin **spawns the `hotreload` CLI**
(`hotreload watch …`) and reflects its state in the status bar. It does **not** embed the engine —
the CLI's log lines are the stable machine-readable surface, and the CLI stays the single source
of truth (see `tasks/T26-ide-plugin-mvp.md`).

It targets IntelliJ IDEA Community but uses only core platform APIs, so it also loads in
Android Studio.

Install the currently published plugin from [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/32850-compose-hot-reload)
through **Settings | Plugins**. The published Marketplace version is 0.1.8 and includes the CLI;
you do not need a repository clone or a separate CLI installation. Version 0.2.0 is under release
validation and will update this listing only after an authorized signed upload. During local
development, install the built ZIP from disk when testing an unreleased change.

The stable IDE workflow is an explicit configured Gradle-plugin profile: use **Refresh discovery**
as a suggestion, review the app/module/variant closure, save or select a profile, then run a
matching CLI-owned prepare before Start. Zero-touch and live literals remain experimental; do not
use a raw override or fingerprint bypass as normal setup recovery.

## First use after Marketplace installation

1. Open the Android project and apply the `dev.hotreload` Gradle plugin to the app and every
   watched code module.
2. Open **Settings | Tools | Compose Hot Reload**, click **Refresh discovery**, review its values,
   and save an explicit configured profile.
3. Run the matching **Doctor** and **prepare** flow to install the debug APK baseline, then launch
   the app on one API-30+ device.
4. Click the status-bar widget or **Tools | Start Hot Reload** and wait for **Ready** before saving
   a Compose-body edit. Click **Stop** before changing configuration or ending the session.

For field-by-field help, see the public [IDE settings guide](../docs/ide-plugin-settings.md) and
[AI-assisted setup guide](../docs/ai-project-setup.md).

## What it gives you

- **Status-bar widget** — `Hot Reload: off / starting… / ready (Nms) / reloading… / error(n) /
  rebuild needed`. Click it to Start/Stop.
- **Tools ▸ Start/Stop Hot Reload** — same toggle from the menu.
- **Settings ▸ Tools ▸ Compose Hot Reload** — structured, persisted watch settings: project,
  profile, app module/variant/application id, watched modules, target JDK, device, SDK,
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

- **Android SDK build-tools 36.0.0** — the CLI shells out to `d8`/`dexdump` from the Android
  SDK. Set `ANDROID_HOME` or configure the **SDK path** field in
  Settings ▸ Tools ▸ Compose Hot Reload.

## Bundled CLI

The plugin zip includes the full CLI distribution (`cli/bin/cli`, `cli/lib/*.jar`). When
installed from disk or from the Marketplace, the plugin resolves the bundled launcher
automatically — **no repo clone or manual `installDist` required**.

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
   pin an explicit configured profile before Start. The CLI launcher defaults to the bundled one;
   override it only to test a different build.
3. **Advanced raw overrides** are an escape hatch for unsupported flags. Enter one exact token per line;
   they are appended after structured arguments and are never parsed as a shell string. Likewise,
   enter one target Gradle argument per line.
4. Make sure the app is installed and running on a device/emulator (the plugin does not install
   it — same prerequisites as running `hotreload watch` by hand).
5. Click the status-bar widget (or **Tools ▸ Start Hot Reload**) → it goes
   `starting… → ready`.
6. Edit a composable body and save → `reloading… → ready (Nms)` and the emulator updates.
7. Make a signature change and save → **rebuild-needed** balloon.
8. Stop from the widget/menu → the process is destroyed; `pgrep -f dev.hotreload.cli.MainKt`
   should be empty.

## Install from disk (release IDE)

`Settings ▸ Plugins ▸ ⚙ ▸ Install Plugin from Disk…` → pick the zip from
`build/distributions/`.

## Known limits (MVP)

- **No auto-save hook.** The CLI's own file watcher handles saves. Enable the IDE's explicit
  auto-save (Settings ▸ Appearance & Behavior ▸ System Settings ▸ *Save files automatically…*)
  or save manually; relying on save-on-frame-deactivation adds latency.
- **No editor gutter icons, no in-editor error banners, no auto-start, no run-configuration
  integration.** Status is status-bar + balloons only.
- **One session per project.** Start is a no-op while a session is running.
- Does not build or install the app — it only spawns `hotreload watch` against an
  already-installed debuggable build.
