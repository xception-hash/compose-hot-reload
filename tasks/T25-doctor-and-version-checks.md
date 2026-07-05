# T25: `hotreload doctor` + loud version checks (Phase 4 slice)
Status: DONE
Assignee: agy
Priority: 4 of T21–T27

## Goal
Phase 4 hardening, the diagnostics half (Maven publish + docs site are deliberately split out
and NOT this task): a `hotreload doctor` subcommand that checks the whole toolchain in one shot
with actionable messages, and fail-loud version checks at `watch` startup instead of cryptic
mid-session errors.

## Spec
1. `cli/.../Main.kt`: new subcommand `hotreload doctor --project <dir> --app-id <id>
   [--module ...] [--sdk ...]` (same option parsing as watch). Runs checks in order, prints one
   `OK`/`FAIL`/`WARN` line each (with a fix hint on FAIL), exits 1 on any FAIL:
   a. JAVA_HOME set + `javac`/java version ≥ 17.
   b. SDK root exists; `platform-tools/adb` and `build-tools/36.0.0/d8` present.
   c. Exactly one device/emulator online (`adb devices`); device API ≥ 30
      (`getprop ro.build.version.sdk`).
   d. Project checks: settings.gradle.kts applies the `dev.hotreload` plugin's
      pluginManagement includeBuild; each watched module's build file applies `dev.hotreload`
      (string-scan is fine; say WARN not FAIL if unsure).
   e. App installed + debuggable: `adb shell pm path <appId>` non-empty and
      `run-as <appId> id` succeeds.
   f. Runtime handshake: if the app process is running, `adb forward` + `Ping` → print
      the Capabilities line incl. protocol version; FAIL with "reinstall the app (stale
      runtime-client)" if the protocol version != the engine's `Protocol.VERSION`. If the
      process is NOT running, print WARN "app not running — start it and re-run for the
      handshake check" (do not auto-launch).
   Implementation: reuse `Adb`/`DeviceClient` from engine — no new duplication in cli.
2. Engine `WatchSession.run()` startup: after the Ping/Capabilities exchange, compare protocol
   versions and fail with the same actionable message (today a mismatch surfaces as opcode
   failures later). Also print the resolved module layouts (already probed) as one info line.
3. Compose runtime version visibility: runtime-client `Capabilities` response gains an optional
   trailing `composeVersion: Int` read from `androidx.compose.runtime.ComposeVersion.version`
   via reflection (0 when absent). Wire codec note in `protocol/Wire.kt` applies (add in BOTH
   write and read). This is a protocol *addition*: bump `Protocol.VERSION` to 5 — **coordinate
   with T27 which also targets v5**; whichever lands second rebases and keeps 5 (the version
   moves once). doctor + watch print it ("Compose runtime version: NNNN").
4. README: short "Troubleshooting" section pointing at `hotreload doctor`.

## Out of scope
- Maven Central publishing, docs site, CI changes.
- Any Compose-version *shim* work (N-1 runtime support) — checks only report, never branch.
- Auto-fixing anything.

## Acceptance
From repo root, emulator booted, sample installed and launched:
1. `./gradlew :cli:installDist` (or the repo's equivalent run path) then
   `hotreload doctor --project samples/single-module --app-id dev.hotreload.sample`
   → all checks OK, exit 0, prints protocol 5 + a plausible composeVersion.
2. Negative: `adb shell am force-stop dev.hotreload.sample` → doctor exits 0 with the WARN
   handshake line; `--app-id not.installed.app` → FAIL on check (e), exit 1.
3. `./e2e/run.sh` 11/11 (or current count) + `./e2e/run-multi.sh` 4/4 — the protocol bump means
   the device app must be reinstalled first (`installDebug`); suites handle that themselves.
4. `pgrep -f dev.hotreload.cli.MainKt` empty after runs.
