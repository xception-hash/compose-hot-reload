# AI-assisted project setup and recovery

Use this guide when an AI coding agent helps configure Compose Hot Reload for an Android project.
The stable path is explicit configured Gradle-plugin integration. Treat discovery as a suggestion,
save a reviewed profile, and keep preparation and watching identical. Do not use zero-touch, live
literals, or `--ignore-fingerprint` unless the project owner opts in.

## Definition of done

Setup is complete only when the agent has verified all of the following:

- The target applies `dev.hotreload` only to the debuggable app and watched code modules.
- `config show` matches the target's real app ID, modules, variants, directories, JDK, device, and
  launch activity.
- `prepare` installs and launches the APK, and `doctor` passes its runtime handshake.
- One watcher reaches `watching …`.
- A reversible Compose-body edit and its restoration each produce `changed:` plus a success line.
- The app process stays alive when that is part of the test, the watcher is stopped, and no
  duplicate watcher remains.

Report any unverified item explicitly. A successful build alone does not prove setup.

## Working contract

The tested lanes are AGP 8.12.x with standalone Kotlin 2.3.x on a full JDK 17, and AGP 9.2.x with
built-in Kotlin/Kotlin 2.4.x on JDK/JBR 21. Azul, Temurin, and other full JDK vendors are fine when
both `bin/java` and `bin/javac` exist. Other combinations are best effort, not an invitation to
change the target project's toolchain.

Hot reload requires one selected API-30+ device and a debuggable variant. Never place
`runtime-client` directly in a non-debuggable configuration. The Gradle plugin owns debug runtime
wiring, Kotlin class-shape flags, Compose metadata, coverage handling, and release safety.

The agent also needs a CLI launcher. Prefer the 0.2.0 release distribution (`cli/bin/cli` on
macOS/Linux or `cli\bin\cli.bat` on Windows) for an external target. In a source checkout, use
`./gradlew -q :cli:run --args="<command and options>"` after the repository preflight. The
Marketplace plugin's CLI is bundled for IDE actions and is not installed as a global shell
command. Examples below use `hotreload` as shorthand for the selected release/source launcher.

## Adaptation principles

The agent's job is to adapt the integration to the project, not normalize the project to a sample:

- Preserve the target's Gradle wrapper, AGP/Kotlin/Compose versions, Java toolchain, repository
  policy, build-script DSL, module layout, source layout, flavors, and existing debuggable
  variants. Do not upgrade, convert, relocate, or rename them merely to match an example.
- Prefer configuration over code changes. Express real differences with profiles,
  `--project-java-home`, app/module mappings, per-module variants, Gradle arguments, device
  selection, SDK selection, and launch activity.
- Make the smallest target diff: add the required repository/plugin resolution and apply
  `dev.hotreload` only where needed. Do not add application-source initialization, copy the
  runtime AAR manually, or restructure unrelated build logic.
- Treat discovery as a draft. When it is wrong, pin explicit values from the target's Gradle model
  instead of adding a project-specific heuristic to Compose Hot Reload.
- Mixed Java/Kotlin modules do not need conversion. Apply the plugin to a watched mixed-language
  module when it owns Compose Kotlin code. The watcher currently reacts to Kotlin and supported
  resource edits; Java-source edits remain on the project's normal build/install path.
- For an unlisted toolchain, try the stable contract without changing versions, label the result
  best effort, and preserve exact diagnostics. Only propose a product change after a sanitized
  minimal reproduction demonstrates a generic gap.
- Never weaken release/debug safety or fingerprint checks to make an unusual project appear
  supported.

## Agent workflow

1. **Inventory without editing.** Locate the Gradle root and wrapper; AGP, Kotlin, and Compose
   versions; Java and Kotlin source ownership; build-script DSL; repository/plugin management;
   Android app modules; application IDs; debuggable variants; module dependency closure; physical
   module directories; coverage instrumentation; project Gradle JDK; Android SDK; devices; and
   existing hot-reload wiring. Report the expected support lane before proposing a change.
2. **Choose configured mode.** Default to explicit modules and a profile, with no literals,
   zero-touch, or fingerprint override. If the project is outside the tested lanes, state that
   plainly without changing Gradle, AGP, Kotlin, or Compose just to fit the product.
3. **Propose the smallest target diff.** Add JitPack/plugin resolution while preserving existing
   repositories and plugins. Apply `dev.hotreload` to the Android app and each watched
   Android-library/Kotlin-JVM module only, using the target's existing Kotlin or Groovy DSL. Do not
   convert build files. Show the diff and wait for owner approval before editing their project.
4. **Resolve JDKs deliberately.** Keep the CLI on Android Studio JBR 21. Pass the target's full
   JDK via `--project-java-home`, and run both `java -version` and `javac -version` from that
   directory before Gradle.
5. **Check the host and device.** Verify adb, one selected online API-30+ device, Android SDK
   build-tools 36.0.0, `d8`, and `dexdump`. If several devices are online, pin `--device`.
6. **Pin the setup.** Run `inspect` or `configure` as a suggestion. Compare its app ID, app module,
   module closure, variants, directories, target JDK, Gradle arguments, device, and launch activity
   with the build. Correct them explicitly, save a profile, and show `config show`.
7. **Prepare before watching.** Run matching `prepare --profile <name>`, then
   `doctor --profile <name>` against the installed APK. Do not replace this with an ordinary
   Android Studio install.
8. **Start one background watcher.** Use the exact same profile and wait for `watching …` before
   editing. See [AGENTS.md](../AGENTS.md) for exact signals and safe failure handling.
9. **Prove one safe edit.** Make one reversible composable-body text or style edit. Require
   `changed:` plus `hot-swapped:`, `interpreted:`, `resource-swapped:`, or `literal-pushed:`.
   Capture the app PID when practical, restore the source, and confirm that restoration applies.
10. **Clean up.** Stop the watcher by its recorded process handle, verify no duplicate process
    remains, list target files changed, preserve user-owned changes, and explain removal only if
    asked.

### Non-interactive watcher pattern

Run the released CLI launcher directly so the recorded PID owns the watcher process:

```bash
hotreload prepare --profile my-app
hotreload doctor --profile my-app

hotreload watch --profile my-app > /tmp/compose-hotreload-my-app.log 2>&1 &
WATCH_PID=$!
```

Poll the log until it contains `watching …`; do not treat an alive process or elapsed time as
readiness. Keep the PID and log path in the agent's working state. When finished:

```bash
kill "$WATCH_PID"
wait "$WATCH_PID" || true
```

If the source checkout's Gradle `:cli:run` wrapper is used instead of the release launcher, confirm
that stopping the wrapper also stopped `dev.hotreload.cli.MainKt`; Gradle can otherwise leave the
watcher child running.

## Mapping examples (not an exhaustive compatibility list)

| Target situation | Profile choice |
|---|---|
| Conventional single-app project | Begin with `configure --project … --save-as …`, then verify every discovered value. |
| Multi-module app | Pin `--app-module`, ordered `--module` mappings, and per-module variants when they differ. |
| Gradle path differs from directory | Use `:gradlePath=physical/directory` or `--app-module-dir`. |
| Flavored/custom debuggable build | Pin `--variant`, application ID, and launcher activity. |
| Target needs a different Gradle JDK | Pin `--project-java-home`; do not change the CLI JBR. |
| Target needs build properties | Add one exact `--gradle-arg` per value and keep it in the profile. |
| Multiple adb devices | Pin `--device`; one watcher still targets one device. |
| Automation consumes discovery | Use `inspect --json` schema v1, then validate it against the Gradle model. |
| Mixed Java/Kotlin module | Keep both languages; watch the module's Compose Kotlin/resources and use the normal build path for Java edits. |
| Groovy build scripts | Preserve Groovy syntax; the lane is best effort because CI covers Kotlin DSL. |
| Owner requests zero-touch or live literals | Create a separate experimental profile; never reuse the configured baseline. |

The complete command/option matrix is in the root
[README CLI reference](../README.md#5-cli-reference). Non-default examples are in
[Project configuration and compatibility](project-configuration.md).

## Failure ladder

Classify before changing anything:

- **Java or Gradle failure:** correct `--project-java-home` or the reviewed Gradle arguments; do
  not casually replace the target's toolchain.
- **Discovery wrong or incomplete:** explicitly set app ID, app/module paths, variants, and
  physical directories; save the corrected profile.
- **Watched library/resource missing:** add its reachable owning module and run matching prepare.
- **Coverage/class-shape mismatch:** use the plugin-managed hot-reload debug build and matching
  prepare; do not guess ART workarounds.
- **Protocol, fingerprint, integration, or literals mismatch:** stop, run matching prepare,
  relaunch, and restart. Never make `--ignore-fingerprint` the first action.
- **Compile or recomposition error:** fix the target source and save again. A healthy watcher does
  not need restarting.
- **Rebuild-required edit:** perform the documented matching prepare and restart once.
- **No watcher event:** check the extension and watched-module scope. A full reinstall is
  appropriate only when the edit is not a watched resource/code change.
- **Suspected product bug:** preserve sanitized Doctor/watcher output, exact versions/options, and
  a minimal reproduction. Do not modify Compose Hot Reload internals as the first response.

## Copy/paste prompt

```text
Read AGENTS.md and docs/ai-project-setup.md from the Compose Hot Reload repository. Configure this
Android project using the stable configured-plugin path. First inspect and report the Gradle/AGP/
Kotlin/Compose/JDK/build-DSL/app-module/variant/module graph without editing. Adapt to the project's
existing versions, Gradle structure, DSL, mixed Java/Kotlin sources, and debuggable variants; do not
upgrade, convert, or restructure them just to match an example. Then propose the smallest
target-project diff. Do not use zero-touch, live literals, or --ignore-fingerprint unless I
explicitly opt in. After I accept the target diff, save and show an explicit profile, run matching
prepare and doctor, then start one background watcher. Wait for the `watching` readiness line before
a reversible test edit, verify the edit and restoration, and stop the watcher. Classify failures
using the documented log contract and adapt this target project before proposing changes to Compose
Hot Reload itself. Report verified, unverified, and out-of-tested-lane items separately.
```
