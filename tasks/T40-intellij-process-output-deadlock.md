# T40: Eliminate IntelliJ process-output deadlocks

Status: IN-REVIEW — host implementation and acceptance gates pass (2026-07-18); Android Studio large-target gate pending
Assignee: unassigned; coordinator reviews the diff and runs all acceptance gates
Recommended model: Gemini 3.1 Pro (Low)
Fallback model: GPT-OSS 120B (Medium)

## Dispatch

Model availability is dynamic. Before dispatch, run `agy models`, select the least expensive
capable model, and do not use Claude Opus 4.6 until the maintainer confirms its quota has reset.
The recommended command from the 2026-07-18 model list is:

```bash
scripts/delegate.sh tasks/T40-intellij-process-output-deadlock.md "Gemini 3.1 Pro (Low)"
```

If external delegation rejects the checkout because it can see private untracked maintainer
material, do not weaken the sandbox or copy that material elsewhere. Execute locally or use a
built-in worker for a bounded mechanical subtask. Delegated output is not accepted without the
coordinator's diff review and acceptance runs.

## Goal

Make Android Studio's **Refresh discovery** finish reliably on large/noisy Gradle builds. The
production trial showed `Discovering…` indefinitely even though the exact bundled
`inspect --json --zero-touch` command completed in a terminal. The discovery service reads the
child's stdout to EOF before it reads stderr, so a child that fills the stderr pipe can block while
the parent is still waiting for stdout EOF. The Doctor/preflight path uses the same sequential
drain pattern and must be fixed by the same shared collector.

## Fixed design

1. Add one internal, pure-JVM process-output collector under
   `intellij-plugin/src/main/kotlin/dev/hotreload/idea/`. It returns the exit code, stdout bytes,
   and stderr bytes as separate values.
2. Use two dedicated reader tasks and start both stream drains before waiting for the child.
   After the child exits, join both readers before returning. Do not use the common fork-join pool.
3. Preserve stream separation. In particular, do not use `redirectErrorStream(true)`: discovery's
   stdout is a clean JSON document, while stderr can contain arbitrary Gradle diagnostics.
4. Do not introduce a production timeout. A legitimate first Gradle configuration on a large
   project may take minutes. Existing background-thread and `invokeLater` ownership must remain
   unchanged.
5. Each reader task must destroy the child before rethrowing a stream-read failure so the parent's
   `waitFor()` cannot remain blocked. On caller interruption, cancel both reader tasks and terminate
   the child when it is still alive. Restore the calling thread's interrupt flag when applicable,
   shut down the executor in `finally`, and propagate a useful failure. Do not leave reader threads
   or child processes behind.
6. Replace the sequential `readBytes()` / `readBytes()` / `waitFor()` code in both:
   - `HotReloadDiscoveryService.refresh`
   - `HotReloadService.runDoctor`
7. Preserve current call-site semantics exactly:
   - discovery: on nonzero exit, report stderr when nonblank, otherwise stdout; on zero, parse only
     stdout as the inspection JSON;
   - doctor: pass the exit code and the same stdout-plus-newline-plus-stderr text to
     `HotReloadPreflight.parse`.

## Regression coverage

Add focused pure-JVM tests for the shared collector. Use a real child JVM rather than only a mock:

1. Launch a test helper `main` with the JDK executable under `java.home/bin` (`java.exe` on
   Windows), the current test classpath, and no shell-specific syntax.
2. The helper writes and flushes more than an OS pipe's capacity to stderr (at least 2 MiB) before
   writing a small unique stdout token, then exits successfully. Assert under a test-only timeout
   that collection completes and preserves the exact stdout, complete stderr payload, and exit
   code. This ordering deterministically wedges the old sequential implementation.
3. Add a nonzero-exit case with distinct content on both streams and assert they remain separate.
4. Ensure every spawned process is destroyed in test cleanup if an assertion or timeout fires.
5. Keep tests platform-neutral and deterministic; do not depend on Gradle network access, an IDE,
   Android SDK, adb, or timing-only sleeps.

## Manual Android Studio gate

After host acceptance passes, rebuild and install the local plugin ZIP and exercise one clean
large-target discovery attempt:

1. Start from the target's clean zero-touch state with the plugin widget Off and no existing
   watcher or inspection child.
2. Press **Refresh discovery** once. Require `Discovering…` to transition to
   `Discovered N app module(s)` and require app module, debuggable variant, application id, and
   watched-module closure to populate.
3. Run the equivalent bundled `inspect --json --zero-touch` command only as a control if needed;
   its result must agree with the UI fields.
4. Confirm no inspection child remains after completion. Then run the normal preflight/Start path
   far enough to establish that Doctor also returns and the plugin reaches its normal next state.
5. Make no target source or Gradle edits. Stop any watcher started by the gate and leave the widget
   Off.

## Out of scope

- Changing CLI inspection schema, discovery selection rules, profiles, settings layout, or command
  construction.
- Adding cancellation UI, a discovery timeout, caching, progress streaming, or retry behavior.
- Merging stderr into stdout, suppressing Gradle diagnostics, or weakening parse/error handling.
- T36 notification cosmetics, target-project edits, Marketplace publication, pushing, tagging, or
  opening a PR.

## Acceptance

Run from the repository root:

```bash
git diff --check
unset JAVA_HOME
source scripts/env.sh
./gradlew :cli:installDist
(cd intellij-plugin && unset JAVA_HOME && source ../scripts/env.sh && \
  ./gradlew test --rerun-tasks buildPlugin verifyPlugin)
```

The collector regression must complete with the real child that emits at least 2 MiB to stderr
before stdout. `verifyPlugin` must report Compatible for all three pinned IDE baselines, zero
internal-API usages, and no findings beyond the three already-known status-widget deprecations.
The Android Studio gate above must then pass on the large target before T40 is marked DONE. Review
the complete diff and run the standing privacy checks before committing. Do not publish or push.
