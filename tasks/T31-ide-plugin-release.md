# T31: IDE plugin — release readiness (validation, CLI bundling, Marketplace)
Status: TODO
Assignee: Opus + the maintainer (device + accounts/tokens are the maintainer's)

## Goal
Take the IntelliJ/Android Studio plugin from "builds + attached to the v0.1.0 GitHub Release as a
from-disk zip" to "a real user can install it and hot-reload with no repo clone." Three parts,
**do them in this order** (each unblocks the next):
1. End-user validation of the shipped zip (from-disk install).
2. Close the CLI-availability gap (the plugin spawns `hotreload`, which today only exists after a
   repo clone + `:cli:installDist`).
3. Publish to the JetBrains Marketplace.

Context: T26 (plugin) is DONE and was verified live in Android Studio 2026.1 (build 261) — Start →
body edit → reload → Stop. v0.1.0 shipped (T29): Gradle plugin + runtime-client resolve from
JitPack. The plugin zip `intellij-plugin/build/distributions/hotreload-intellij-plugin-0.1.0.zip`
is attached to https://github.com/xception-hash/compose-hot-reload/releases/tag/0.1.0.

## Device-work preamble (EVERY session that touches the emulator — from status memory)
- Protocol is **v8**. Before any device run: reinstall BOTH device apps (`:app:installDebug` in the
  sample), rebuild the CLI launcher (`./gradlew :cli:installDist`), force-stop apps once (injected
  dex is immutable per process).
- `source scripts/env.sh` with an ABSOLUTE path first (relative source silently no-ops JAVA_HOME →
  gradlew exits 0 doing nothing). Check APK mtime after building.
- Kill watch loops with `pkill -9 -f dev.hotreload.cli.MainKt` (Gradle daemon spawns the CLI JVM).

## Part 1 — End-user validation of the shipped zip (Opus + device)
The IDE parallel to the CLI clean-clone test done in T29. Install the RELEASE zip fresh and drive it.
- **Launch Android Studio FROM A TERMINAL with env.sh sourced** (`source scripts/env.sh &&
  "/Applications/Android Studio.app/Contents/MacOS/studio"`). If launched normally, the plugin
  spawns the CLI without JAVA_HOME and the launcher dies "Unable to locate a Java Runtime" (T26
  gotcha).
- Install `hotreload-intellij-plugin-0.1.0.zip` via **Settings ▸ Plugins ▸ ⚙ ▸ Install Plugin from
  Disk…**, restart.
- Rebuild the CLI launcher (`./gradlew :cli:installDist`) — a stale launcher predating a protocol
  bump gives `device N != engine M` on Start (bit T26). Point the plugin's **Settings ▸ Tools ▸
  Compose Hot Reload ▸ CLI path** at `cli/build/install/cli/bin/cli` (verify the real launcher name).
- Configure project dir = `samples/single-module`, app id = `dev.hotreload.sample`. Reinstall the
  sample + force-stop first (preamble).
- Drive and assert (mirror the T26 smoke flow + the post-T28 cases):
  - Start → widget `starting…` → `ready (Nms)`; emulator app running.
  - Body edit (e.g. `Greeting()` text) → `reloading…` → `ready`; device text updates, counter state
    preserved.
  - **@Composable signature change → SUCCESS** (post-T28 this is NOT a rebuild balloon — status memory
    T26 gotcha #3). Assert widget returns to `ready`, not `rebuild needed`.
  - Broken edit (`error()` in a body) → `error(n)` balloon with source location; fix-and-save → `ready`.
  - Stop → widget `off`, NO leaked CLI (`pgrep -f dev.hotreload.cli.MainKt` empty — the `stopping`
    flag fix from commit 87cc160 makes a user Stop report Off, not `error(143)`).
- **Acceptance:** all of the above green on emulator-5554 with the from-disk 0.1.0 zip; no leaked CLI.

## Part 2 — CLI-availability gap (Opus design + impl; the meatiest item)
Problem: the plugin runs `hotreload watch …` by spawning the CLI. Today the CLI only exists as
`cli/build/install/cli/` after a clone + `:cli:installDist`. A user who installs ONLY the IDE plugin
has no CLI — so the current plugin is unusable without the repo. This MUST be solved before
Marketplace (a marketplace plugin that requires a repo clone is broken UX).
- **Decision to make (recommend option A):**
  - **A — Bundle the CLI inside the plugin zip.** `:cli:installDist` (or a shadow/fat distribution)
    packaged into the plugin distribution; the plugin defaults its CLI path to the bundled launcher,
    with the Settings field as an override. Self-contained; no clone. Verify size + that the bundled
    CLI carries the engine + interp.dex resource. Watch for: the CLI needs JAVA_HOME (JBR) at
    runtime — the plugin already inherits the IDE's env, but document/verify the bundled-CLI launch
    finds a JDK (the T26 "launch Studio from terminal" issue would bite bundled CLI too unless we
    resolve JAVA_HOME from the IDE's own JBR).
  - B — Ship the CLI as a separate GitHub Release asset (e.g. `hotreload-cli-0.1.0.zip` from
    `installDist`); plugin downloads/points to it. More moving parts.
  - C — Document clone + installDist + set CLI path. Rejected for Marketplace (bad UX) but fine as an
    interim for from-disk users.
- Also decide how the bundled/standalone CLI finds the pinned toolchain (build-tools/NDK/d8) on a
  user's machine — the engine shells out to `d8`/`dexdump` from `ANDROID_HOME/build-tools/36.0.0`.
  A real external user needs those SDK components. Document the prerequisite (or have `doctor` check
  it — `hotreload doctor` already validates SDK/toolchain/device; wire the plugin's first-run to it).
- **Acceptance:** from a machine with NO repo clone (simulate: fresh dir, only the plugin zip + the
  Android SDK), install the plugin, Start, and hot-reload the sample. If bundling (A), the plugin
  zip alone + SDK is sufficient.

## Part 3 — JetBrains Marketplace publish (the maintainer: account/token; Opus: build config)
NEW scope (was explicitly out of scope for T29). Gated on Part 2 (don't publish a clone-required plugin).
- **Opus/build:** verify `intellij-plugin` `plugin.xml` `<idea-version since-build/until-build>`
  covers the target IDEs (the maintainer tested AS 2026.1 = build 261; set a sensible range, e.g. since 233/243
  through 261.* — confirm against the IntelliJ Platform the plugin compiles against). Add the
  `signPlugin` + `publishPlugin` tasks (IntelliJ Platform Gradle Plugin) reading a `PUBLISH_TOKEN`
  and signing cert/private-key from env. Fill plugin description + change-notes (reuse README matrix).
- **the maintainer-only (do NOT attempt headless):** create/confirm a JetBrains Marketplace vendor account,
  generate the Marketplace permanent token, generate a signing cert/key (`openssl`), run
  `./gradlew publishPlugin` with the secrets, then wait for JetBrains' first-upload manual review
  (a few business days). Set the plugin's marketplace metadata (icon, tags, source URL).
- **Acceptance:** plugin appears on the Marketplace (or is in JetBrains' review queue) and installs
  in-IDE via **Settings ▸ Plugins ▸ Marketplace** search — no from-disk step.

## Out of scope
Engine/runtime/classifier changes; anything in T30 (robustness leftovers); embedding the engine in
the IDE process (v1 stays CLI-spawn).

## Notes for next session (read order)
1. Memory `compose-hot-reload-status.md` (this file's outcomes will be summarized there).
2. `intellij-plugin/README.md` + `tasks/T26-*` Outcome (real CLI log prefixes, plugin architecture,
   the `stopping`-flag Stop fix, the 3 API-drift risks that never bit).
3. `docs/OPUS-HANDOFF.md` for the standing device/delegation protocol.
