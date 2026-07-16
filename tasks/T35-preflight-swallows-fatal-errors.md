# T35: Preflight swallows fatal CLI errors + no SDK auto-discovery
Status: OPEN (follow-up from T34 device testing, 2026-07-16)
Assignee: unassigned
Recommended model: Sonnet (localized plugin + engine changes, unit-tested) — coordinator re-verifies

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
