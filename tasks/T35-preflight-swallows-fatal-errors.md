# T35: Preflight swallows fatal CLI errors + no SDK auto-discovery
Status: DONE (pending PR #21 merge) — 0.1.6 device-verified + PUBLISHED to Marketplace 2026-07-16;
merge PR #21 to close (2026-07-16)
Assignee: Sonnet subagent (mechanical) + Opus coordinator (design + re-verify)
Recommended model: Sonnet (localized plugin + engine changes, unit-tested) — coordinator re-verifies

## Outcome 2026-07-16 (host-side DONE) — all three bugs fixed, plugin 0.1.6
Implementation matched the design below; commit 9d329a8 (spec + settings doc in b54aec9).
- **Bug 1** — `PreflightResult` retains `rawOutput`/`exitCode`; new pure `HotReloadPreflight.notice()`
  renders the balloon (bullets for `[FAIL]`s, else the real doctor output + exit code, "could not
  run" title). `balloonPreflight` uses it and wires a **Report on GitHub** action (new `reportAction`
  overload takes explicit history — the watch ring buffer is empty pre-launch).
- **Bug 2** — new pure `AndroidSdkResolver.discover()` (local.properties `sdk.dir` → `ANDROID_HOME`
  → `ANDROID_SDK_ROOT` → platform default; first existing dir wins). `HotReloadService.sdkEnv()`
  injects it as `ANDROID_HOME` into the doctor + watch subprocess, ONLY when no explicit `--sdk`.
- **Bug 3** — `expandUserHome()` applied to `sdkPath`/`cliLauncherPath`/`targetJdk` in
  `HotReloadWatchConfig.from()` (added a `home` seam so tests are deterministic).
- **Gates (coordinator re-verified from artifacts, not the agent's word):** `test` **43/43**
  (forced `--rerun-tasks`; +5 AndroidSdkResolver, +3 Preflight, +1 Command), `verifyPlugin`
  **Compatible** on 2025.1 / 2026.1.4 / 262 EAP (only the 3 known StatusBarWidget deprecations),
  `buildPlugin` → `hotreload-intellij-plugin-0.1.6.zip`. Protocol unchanged (v8), no AAR reinstall.
- **DEVICE SMOKE PASSED (2026-07-16):** 0.1.6 installed in Android Studio, default config (no SDK
  path set, GUI-launched). Preflight on a broken env (no device) rendered specific `[FAIL]` bullets
  ("Device connected: no online devices found (fix: …)", app-installed + handshake skipped) **plus a
  Report on GitHub action** — proving SDK auto-discovery works (doctor reached the device check
  instead of the old `--sdk not given` fatal abort). Booting `Medium_Phone_API_36.0` cleared it and
  the happy-path Start→reload verified. Both headline fixes confirmed live.
- **PR #21** opened (https://github.com/xception-hash/compose-hot-reload/pull/21).
- **PUBLISHED 2026-07-16:** `./gradlew publishPlugin` signed + uploaded 0.1.6 to JetBrains
  Marketplace (BUILD SUCCESSFUL; existing key reused). In moderation (updates to an approved
  plugin usually clear fast). **0.1.6 shipped instead of 0.1.5.**
- **LEFT FOR JAY:** merge PR #21 (guard blocks main). That's the only open item.
- **Known cosmetic (non-blocking):** IntelliJ renders notification bodies as HTML and collapses the
  `\n\nFix these…` separator onto the last bullet's line. Switch body separators to `<br>` for
  cleaner wrapping in a follow-up if desired.

## Problem (observed live 2026-07-16, Android Studio 2026.1.1, 0.1.5 plugin)

Hitting Start showed **"The hot reload environment check found problems … Fix these, or click
'Start anyway'"** with a **generic body and NO bullet list**, identical on retry — no way to see
or report the actual cause.

Root-caused by reproducing the plugin's exact doctor invocation against the bundled CLI:

```
JAVA_HOME=<Studio JBR> <plugin>/cli/bin/cli doctor --project samples/multi-module --module app
→ hotreload: --sdk not given and ANDROID_HOME not set   (exit 1)
```

The CLI aborts **config resolution before any check runs** (above the `when(command)` dispatch in
`cli/src/main/kotlin/dev/hotreload/cli/Main.kt:359`) via `fail()`, which prints a plain
`hotreload: <msg>` line — **not** a `[FAIL]` line — and exits non-zero. Supplying `--sdk` advances
to `hotreload: --app-id is required` (passing `--module app` disables auto-discovery, making
app-id mandatory). With both `--sdk ~/Library/Android/sdk --app-id dev.hotreload.multisample`,
doctor runs clean: all 6 `[OK]`, exit 0.

## Two bugs

### Bug 1 — preflight swallows non-`[FAIL]` fatal errors (primary; matches user complaint)
`HotReloadPreflight.parse()` only collects lines starting with `[FAIL]`. A fatal `hotreload:`
abort exits non-zero with zero `[FAIL]` lines ⇒ `PreflightResult.failures` is empty ⇒
`HotReloadService.balloonPreflight()` renders only the heading + "Fix these…". The raw
stdout/stderr is discarded and the preflight balloon carries **no "Report on GitHub" action**
(that action exists only for the running watch process via `outputHistory`). Net: the user sees a
generic error they cannot diagnose or report — the exact failure the maintainer hit.

**Fix:** when the preflight fails, always give the user the real text and a report path:
- In `runDoctor`/`PreflightResult`, retain the raw combined output (and exit code).
- In `balloonPreflight`, when `failures.isEmpty()`, show the raw output (or its last N non-blank
  lines / the `hotreload:` line) instead of an empty bullet list.
- Add the existing "Report on GitHub" `NotificationAction` (see `reportAction`) to the preflight
  balloon, seeded with the raw doctor output + exit code.
- Optional: distinguish "doctor could not run" (config/fatal abort) from "environment has
  problems" ([FAIL]s present) in the title.

### Bug 2 — SDK path never reaches the doctor subprocess for GUI-launched IDEs (secondary)
The plugin already injects the IDE JBR as `JAVA_HOME` (`HotReloadService.kt:104`) but does **not**
supply the Android SDK. GUI-launched Studio has no `ANDROID_HOME`, and the plugin only adds `--sdk`
when `sdkPath` is set in settings (usually blank on first run) ⇒ every default-config macOS user
hits `--sdk not given and ANDROID_HOME not set` immediately.

**Fix (pick/combine):**
- Auto-discover the SDK and pass `--sdk`: `local.properties` `sdk.dir` in the project, else
  the platform default Android SDK dir / `$ANDROID_HOME` / the IDE's configured Android SDK path.
- And/or inject `ANDROID_HOME` into the subprocess env the same way JAVA_HOME is injected.
- Consider letting the plugin's default config trigger CLI auto-discovery (omit `--module`/`--app-id`
  so the CLI discovers the app module + applicationId) rather than always sending `--module app`,
  which forces `--app-id` to be required.

### Bug 3 — `~` not expanded in the SDK path (observed 2026-07-16)
Setting SDK path to a tilde path (`~/Library/Android/sdk`) yields
`[FAIL] Android SDK: root directory not found: ~/Library/Android/sdk`. The token is passed
verbatim to the CLI; neither the plugin, the CLI, nor `java.nio.Path` expands `~` (only a shell
does), so it resolves to a literal directory named `~`. Workaround: enter an absolute path.
**Fix:** expand a leading `~`/`~user` to the home dir when reading path-valued settings (SDK path,
CLI launcher, project-java-home) — do it plugin-side in `HotReloadCommand`/settings normalization,
or defensively in the CLI's `--sdk` handling. Validate the field in the Configurable UI too
(warn if the resolved path is not a directory).

## Immediate user workaround (documented, not a fix)
Settings › Tools › Compose Hot Reload → set **SDK path** = `~/Library/Android/sdk` and **App ID**
(+ module), or export `ANDROID_HOME` before launching Studio.

## Acceptance
- [ ] A fatal `hotreload:` abort (e.g. SDK/app-id missing) produces a preflight notification whose
      body contains the real CLI message + exit code, and a "Report on GitHub" action.
- [ ] With no SDK set in settings and no `ANDROID_HOME`, Start on a machine with a standard SDK at
      `~/Library/Android/sdk` no longer fails the SDK check (auto-discovery or env injection).
- [ ] `[FAIL]`-based failures still render as bullets exactly as today (regression guard).
- [ ] Unit tests: `HotReloadPreflightTest` for the empty-`failures` + non-zero-exit path;
      `HotReloadServiceTest`/wiring test for the report action on the preflight balloon.
- [ ] Manual: default-config Start in a GUI-launched Studio reaches real `[OK]`/`[FAIL]` output.

## Files
- `intellij-plugin/.../HotReloadService.kt` — `runDoctor` (100–111), `balloonPreflight` (113–128),
  `resolveLauncher`/env injection (~104, ~289), `reportAction` (~335).
- `intellij-plugin/.../HotReloadPreflight.kt` — retain raw output/exit code.
- `intellij-plugin/.../HotReloadCommand.kt` — SDK discovery for `doctorArguments()`/`watchArguments()`.
- `intellij-plugin/src/test/.../HotReloadPreflightTest.kt` — new cases.
- Engine `Doctor`/`Main` unchanged (the abort is correct CLI behavior; the plugin must surface it).

## Notes
- Plugin-only change is sufficient for Bug 1; Bug 2 is plugin-side too. Protocol unchanged (v8),
  no AAR reinstall. Separate release from the 0.1.5 in review; does not block T34 publish.
- Related: T34 (the preflight), T33i (IDE discovery/profiles — the SDK/app-id auto-fill path),
  T31 (plugin release, SDK-toolchain prereq notes).
