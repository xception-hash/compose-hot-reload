# AI-assisted project setup and recovery

Use this guide when an AI coding agent helps configure Compose Hot Reload for an Android project.
The stable path is the explicit configured Gradle-plugin integration. Treat discovery as a
suggestion, save a reviewed profile, and keep preparation and watching identical. Do not use
zero-touch, live literals, or `--ignore-fingerprint` unless the project owner opts in.

## Working contract

The tested lanes are AGP 8.12.x with standalone Kotlin 2.3.x on a full JDK 17, and AGP 9.2.x
with built-in Kotlin/Kotlin 2.4.x on JDK/JBR 21. Azul, Temurin, and other full JDK vendors are
fine when both `bin/java` and `bin/javac` exist. Other combinations are best effort, not an
invitation to change the target project's toolchain.

Hot reload requires one selected API-30+ device and a debuggable variant. Never place
`runtime-client` directly in a non-debuggable configuration. The Gradle plugin owns the debug
runtime wiring, Kotlin class-shape flags, Compose metadata, coverage handling, and release safety.

## Agent workflow

1. **Inventory without editing.** Locate the Gradle root and wrapper; AGP, Kotlin, and Compose
   versions; repository/plugin management; Android app modules; application IDs; debuggable
   variants; module dependency closure; physical module directories; coverage instrumentation;
   project Gradle JDK; Android SDK; devices; and existing hot-reload wiring. Report findings and
   the expected support lane before proposing a change.
2. **Choose configured mode.** Default to explicit modules and profile, no literals, no
   zero-touch, and no fingerprint override. If the project is outside the tested lanes, state it
   plainly without changing Gradle, AGP, Kotlin, or Compose just to fit the product.
3. **Propose the smallest target diff.** Add JitPack/plugin resolution while preserving existing
   repositories and plugins. Apply `dev.hotreload` to the Android app and every watched
   Android-library/Kotlin-JVM module only. Show the diff and wait for owner approval before
   editing their project.
4. **Resolve JDKs deliberately.** Keep the CLI on Android Studio JBR 21. Pass the target's full
   JDK via `--project-java-home`, and run both `java -version` and `javac -version` from that
   directory before Gradle.
5. **Pin the setup.** Run `inspect` or `configure` as a suggestion. Compare its app ID,
   module closure, variants, and directories with the build; correct them explicitly; save a
   profile; then show `config show`.
6. **Prepare before watching.** Run matching `prepare --profile <name>`, then
   `doctor --profile <name>` against the installed APK. Start one background watcher with that profile and wait for
   `watching …` before editing. See [AGENTS.md](../AGENTS.md) for exact signals.
7. **Prove one safe edit.** Make or request one reversible composable-body text/style edit.
   Require `changed:` plus `hot-swapped:`, `interpreted:`, or `literal-pushed:`; capture a
   stable PID when practical. Restore the source and confirm that restoration applies too.
8. **Clean up.** Stop the watcher, verify there is no duplicate process, list target files
   changed, preserve user-owned changes, and explain how to remove the integration if asked.

## Failure ladder

Classify before changing anything:

- **Java or Gradle failure:** correct `--project-java-home` or the reviewed Gradle arguments;
  do not casually replace the target's toolchain.
- **Discovery wrong or incomplete:** explicitly set app ID, app/module paths, variants, and
  physical directories; save the corrected profile.
- **Watched library/resource missing:** add its reachable owning module and run matching prepare.
- **Coverage/class-shape mismatch:** use the plugin-managed hot-reload debug build and matching
  prepare; do not guess ART workarounds.
- **Protocol, fingerprint, integration, or literals mismatch:** stop, run matching prepare,
  relaunch, and restart. Never make `--ignore-fingerprint` the first action.
- **Compile or recomposition error:** fix the target source and save again. A healthy watcher does
  not need restarting.
- **Rebuild-required edit:** perform the documented full install/prepare and restart once.
- **No watcher event:** check extension and watched-module scope. A full reinstall is appropriate
  only when the edit is not a watched resource/code change.
- **Suspected product bug:** preserve sanitized Doctor/watcher output, exact versions/options, and
  a minimal reproduction. Do not modify Compose Hot Reload internals as the first response.

## Copy/paste prompt

```text
Read AGENTS.md and docs/ai-project-setup.md from the Compose Hot Reload repository. Configure this
Android project using the stable configured-plugin path. First inspect and report the Gradle/AGP/
Kotlin/JDK/app-module/variant/module graph without editing. Then propose the smallest target-project
diff. Do not use zero-touch, live literals, or --ignore-fingerprint unless I explicitly opt in.
After I accept the target diff, run matching prepare, doctor, and one background watcher; wait for
the `watching` readiness line before a reversible test edit. Classify failures using the documented
log contract and adapt this target project before proposing changes to Compose Hot Reload itself.
```
