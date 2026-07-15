# AGENTS.md — running Compose Hot Reload as an AI agent

The [README](README.md) is the authoritative install/usage guide — follow its Quickstart
(§3) for the actual steps. This file only adds what an automated agent needs to execute
those steps non-interactively: preflight checks, the background-run pattern for the
watcher, and the exact log lines that mean success or failure.

## 1. Preflight (run before any Gradle step)

```bash
"$JAVA_HOME/bin/java" -version 2>&1 | head -1   # must be a JBR (JetBrains Runtime), e.g. "openjdk version 21..."
ls "$ANDROID_HOME/build-tools/36.0.0/d8"        # must exist
ls "$ANDROID_HOME/build-tools/36.0.0/dexdump"   # should exist — if missing, bitmap edits overlay without invalidating
adb devices                                     # exactly one device/emulator, state "device"
adb shell getprop ro.build.version.sdk          # must print >= 30
```

On macOS, `source scripts/env.sh` sets sane defaults (`JAVA_HOME` = Android Studio's
bundled JBR, `ANDROID_HOME` = `~/Library/Android/sdk`). On Linux, export both yourself
before sourcing it; the pinned toolchain versions are in README §2.

## 2. The watcher is a blocking daemon — run it in the background

`./gradlew -q :cli:run --args="watch …"` **never returns** (it runs until Ctrl-C).
Following the README literally will hang your session. Instead:

```bash
./gradlew -q :cli:run --args="watch --project <dir> --app-id <id>" > /tmp/hotreload.log 2>&1 &
# For a target that does not apply dev.hotreload, add --zero-touch and first run
# a matching: hotreload prepare --zero-touch --project <dir> [...same options]
```

Then poll the log. Healthy startup prints, in order:

```
device: api=34 protocol=N Compose runtime version: ... redefine=true structural=true inject=true compose=true
initial build... ok (12345ms)
modules: :app: AGP (...)
watching <source roots> (<N> classes)
```

**`watching …` is the readiness gate.** Do not edit files before it appears (the initial
build can take minutes on first run). The process staying alive is normal — do not kill
and retry it, and never run two watchers against the same device.

## 3. Machine-checkable signals after each save

Every save first prints `changed: <files>`. Then grep the log for one of:

| Log line | Meaning | What to do |
|---|---|---|
| `hot-swapped: … in <N>ms` | success (compiled path) | verify visually if needed |
| `interpreted: … (<N>ms)` | success (interpreter path) | same |
| `literal-pushed: <key> = <value> in <N>ms` | success (live-literal fast path) | same |
| `compile failed — fix and save again` | your edit doesn't compile | fix the code, save again — recoverable, watcher keeps running |
| `recomposition failed: …` | edit compiled but crashed at runtime | device keeps last-good frame; fix and save again |
| `cannot hot-swap: <reason>` + `run a full install …` | edit needs a rebuild | configured mode: run the target's install task; zero-touch mode: rerun `hotreload prepare --zero-touch` with the same options; then restart the watcher |
| *(nothing at all — not even `changed:`)* | edit is invisible to the watcher (fonts and other non-watched extensions; see README §5) | full reinstall is the only path |

To confirm the UI actually changed, take a screenshot and read it:

```bash
adb exec-out screencap -p > /tmp/shot.png
```

## 4. Failure → fix

| Error (exact text in log) | Cause | Fix |
|---|---|---|
| `protocol version mismatch (device X != engine Y) — reinstall the app (stale runtime-client)` | app was installed from an older checkout | configured mode: reinstall through the target build; zero-touch mode: rerun matching `hotreload prepare --zero-touch` |
| `device is not hot-swappable — is the app a debuggable build with the runtime-client?` | release build, or selected integration was not applied | install a debuggable variant through the configured plugin or `hotreload prepare --zero-touch` |
| `no classes found under …` | wrong `--project` dir, or app never built | check the path; prepare once using the same configured/zero-touch mode as the watcher |
| `app module '…' is not an AGP module (no built-in-kotlinc output)` | wrong `--module` (first entry must be the Android app module) | fix `--module` order/names |
| **No error, but edits behave wrongly / state corrupts** | stale APK vs `--literals` or integration-mode mismatch | rerun a matching prepare/install with the same `--literals` and `--zero-touch` choices, relaunch, and restart the watcher |

## 5. Don'ts

- Don't use `scripts/dev-install.sh`, `emulator-up.sh`, `taps.sh`, `ui-state.sh`,
  `stats.sh`, or other `scripts/*.sh` beyond `env.sh` — they are maintainer-internal and
  hardcode the maintainer's toy app and AVD.
- Don't edit source files while `initial build...` is still pending.
- Don't parse timing numbers as pass/fail — only the log lines in §3 are the contract.
