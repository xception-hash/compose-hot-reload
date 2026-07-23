# AGENTS.md — running Compose Hot Reload as an AI agent

The [README](README.md) and [AI project setup guide](docs/ai-project-setup.md) are the
authoritative install/usage guides. Default to their stable configured Gradle-plugin path:
review discovery, save an explicit profile, run matching `prepare` and `doctor`, then watch.
This file adds the non-interactive watcher pattern and machine-checkable success/failure signals.
Zero-touch is opt-in experimental recovery, never the default for an unconfigured project.
Adapt the integration to the target's existing JDK, Gradle structure/DSL, versions, variants,
module layout, and mixed Java/Kotlin sources. Do not normalize the target to a sample or add a
project-specific Compose Hot Reload workaround before proving a generic product gap.

> **Maintainer agents:** if an untracked `.agents/` directory exists in this checkout,
> you are working as the maintainer's agent — read `.agents/README.md` FIRST; it holds
> the live project handoff and session protocol. The rest of this file is for agents
> *using* the product.

## 1. Preflight (run before any Gradle step)

In a source checkout on macOS, first select the repository's CLI JBR:

```bash
unset JAVA_HOME
source scripts/env.sh
```

On Linux, export `JAVA_HOME` and `ANDROID_HOME` before sourcing `scripts/env.sh`. For the release
CLI, set equivalent paths yourself. The CLI JBR and the target project's
`--project-java-home` are independent.

```bash
"$JAVA_HOME/bin/java" -version 2>&1 | head -1   # must be a JBR (JetBrains Runtime), e.g. "openjdk version 21..."
ls "$ANDROID_HOME/build-tools/36.0.0/d8"        # must exist
ls "$ANDROID_HOME/build-tools/36.0.0/dexdump"   # should exist — if missing, bitmap edits overlay without invalidating
adb devices                                     # exactly one device/emulator, state "device"
adb shell getprop ro.build.version.sdk          # must print >= 30
```

Run `hotreload config show --profile <name>` before preparation and confirm that the profile pins
the intended target project, app ID, modules, variant, target JDK, device, and launch activity.

## 2. The watcher is a blocking daemon — run it in the background

`hotreload watch` runs until interrupted. Agents must start it in the background, keep its PID and
log path, and wait for readiness:

```bash
hotreload watch --profile <name> > /tmp/compose-hotreload-<name>.log 2>&1 &
WATCH_PID=$!
```

`hotreload` above means the released CLI launcher. If the source checkout's Gradle
`:cli:run` wrapper is used, verify during cleanup that `dev.hotreload.cli.MainKt` did not survive
the wrapper.

Poll the log. A healthy startup prints these stages in order:

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
| `resource-swapped: … in <N>ms` | success (resource overlay path) | same |
| `literal-pushed: <key> = <value> in <N>ms` | success (live-literal fast path) | same |
| `compile failed — fix and save again` | your edit doesn't compile | fix the code, save again — recoverable, watcher keeps running |
| `recomposition failed: …` | edit compiled but crashed at runtime | device keeps last-good frame; fix and save again |
| `cannot hot-swap: <reason>` + `run a full install …` | edit needs a rebuild | stop, run matching `prepare` with the same profile, then restart the watcher |
| *(nothing at all — not even `changed:`)* | edit is invisible to the watcher (fonts and other non-watched extensions; see README §7) | a full reinstall is the only path |

To confirm the UI actually changed, take a screenshot and read it:

```bash
adb exec-out screencap -p > /tmp/shot.png
```

Restore the test edit and require another success signal. Capture the app PID before and after when
state/process preservation is part of the acceptance check.

## 4. Failure → fix

| Error (exact text in log) | Cause | Fix |
|---|---|---|
| `protocol version mismatch (device X != engine Y) — reinstall the app (stale runtime-client)` | app was prepared with another CLI/runtime version | stop and rerun matching `prepare` with the reviewed profile |
| `device is not hot-swappable — is the app a debuggable build with the runtime-client?` | release build, or selected integration was not applied | use the configured plugin on a debuggable variant, then rerun matching `prepare` |
| `no classes found under …` | wrong `--project` dir, or app never built | check the path; prepare once using the same configured/zero-touch mode as the watcher |
| `app module '…' is not an AGP module (no built-in-kotlinc output)` | wrong `--module` (first entry must be the Android app module) | fix `--module` order/names |
| `fingerprint: MISMATCH` | APK-shaping profile values differ from preparation | stop and rerun matching `prepare`; do not start with `--ignore-fingerprint` |
| **No error, but edits behave wrongly / state corrupts** | stale APK vs `--literals` or integration-mode mismatch | rerun matching `prepare` with the same profile, relaunch, and restart |

## 5. Cleanup

For the released CLI launcher:

```bash
kill "$WATCH_PID"
wait "$WATCH_PID" || true
```

Verify that no second watcher remains. If a Gradle `:cli:run` wrapper was backgrounded, also check
for a surviving `dev.hotreload.cli.MainKt` child before declaring cleanup complete.

## 6. Don'ts

- Don't use `scripts/dev-install.sh`, `emulator-up.sh`, `taps.sh`, `ui-state.sh`,
  `stats.sh`, or other maintainer-internal scripts that hardcode the maintainer's toy app
  and AVD. `scripts/env.sh` and `scripts/delegate.sh` are explicitly allowed for their
  documented workflows.
- Don't edit source files while `initial build...` is still pending.
- Don't parse timing numbers as pass/fail — only the log lines in §3 are the contract.

## 7. Delegation and model cost

The project owner prefers bounded, independent work to be delegated through
Antigravity CLI (`agy`) when that reduces use of the primary agent's token budget.

- Before delegating, run `agy models` and select the least expensive model capable of
  the task. Prefer a lower-cost GPT model such as GPT-5.4 or GPT-5.5 if one is listed;
  otherwise prefer a Flash/Low/Medium or similar lower-cost tier for routine work.
- **Temporary quota constraint (recorded 2026-07-15): Claude Opus 4.6 quota is empty.**
  Do not select Opus 4.6 until the project owner explicitly confirms that its quota has
  reset. A model appearing in `agy models` does not prove that quota is available.
- Invoke `agy` non-interactively with `--print` and an explicit `--model`, preferably
  through `scripts/delegate.sh`. Give it a narrowly scoped prompt and require a concise
  result. Never rely on an implicit/default model.
- Use built-in generic sub-agents only when `agy` is unavailable, fails, or lacks a
  capability required by the task. Built-in delegation currently does not guarantee
  selection of a lower-cost model.
- Do not delegate trivial work when coordination and returned output would consume
  more tokens than completing it directly.
- Treat model availability as dynamic. Do not rely on a model list recorded in this
  file; query `agy models` in each future session.
- For a long-running task that the owner may execute later, write a decision-complete
  spec under `tasks/T<NN>-<name>.md` instead of leaving the plan only in chat. Include a
  recommended model, at least one fallback from a different model family when possible,
  the exact `scripts/delegate.sh` command, explicit out-of-scope boundaries, and exact
  acceptance commands. Do not dispatch it unless the owner asks for immediate execution.
- The coordinator remains responsible for reviewing the resulting diff and running the
  acceptance gates; delegated output is never accepted solely from the worker's report.
