# Opus 4.8 handoff — post-2026-07-07 playbook

## Current continuation — T39 done; finish T38 cleanup (2026-07-18)

Read `.agents/STATUS.md` newest entries first, then T38 and T39. T39 is DONE: configured mode now
enables Compose FunctionKeyMeta in every Compose module, and both the Kotlin-2.3 two-save fixture
and the real Android Studio first/second/restoration gate pass with stable PID. Stop returned to Off
without a watcher leak. Core/distribution, Gradle-plugin, IntelliJ build/tests, and all three Plugin
Verifier baselines pass.

Next, complete only T38's recorded cleanup: surgically remove its temporary target composite/plugin
wiring, restore the two local runtime-client compatibility edits, prove the saved target source/diff
baseline, run a matching zero-touch prepare, and leave the plugin stopped with zero-touch selected.
Do not publish or push. The large-target discovery deadlock remains a separate product follow-up.

Fable access ended 2026-07-07. Everything Fable-class is done and written down; this file is the
operating manual for finishing the project with **Opus 4.8 + Gemini/agy + the maintainer**. The roadmap
table lives in `docs/PLAN.md` (status header) — ONE canonical copy, do not duplicate it here.

## Current status — T33 PR open, awaiting CI and maintainer merge (2026-07-15)

T33 phases 1–10 are complete and committed on `t33h/zero-touch`. PR #19 is open against
`main`: <https://github.com/xception-hash/compose-hot-reload/pull/19>. It is mergeable but
**not merged**. The lightweight compatibility-contract check passed; the e2e and compatibility
device jobs were still in progress at handoff. The canonical completion record is
`tasks/T33-project-agnostic.md` and the roadmap is `docs/PLAN.md`; generic configuration and
compatibility details are in `docs/project-configuration.md`.

Final evidence: core/distribution and runtime-client tests; configured debug/qa + multi-module
assembly; IntelliJ plugin test/build; configured `hotreload start` device regression; 4/4
configured multi-module regression; and offline zero-touch `start` on AGP 9 plus AGP 8/JDK 17.
The CI compatibility job enforces that the AGP 8 leg cannot be skipped.

The T33-specific continuation below is historical evidence only. At the next session, first ask
the maintainer for PR #19's current CI status and, if all required checks are green, ask the
maintainer to merge it. Do not merge the PR autonomously. Future implementation work should then
start from the newest `.agents/STATUS.md` entry and a newly scoped task rather than resuming T33.

## Historical handoff — T33 continuation for GPT-5.6 Tera (2026-07-15)

Branch: `t33h/zero-touch`, HEAD `391d13e`. The worktree is intentionally dirty and no
commit/push/PR was made. There are no live watcher or sample Gradle wrapper processes.
Preserve all pre-existing edits, especially the owner's changes in `AGENTS.md`,
`docs/WORKFLOW.md`, `scripts/delegate.sh`, `tasks/README.md`, and `.agents/`.

Read in this order:

1. `tasks/T33h-zero-touch-bootstrap.md`, especially the final GPT-5.6 handoff update.
2. The current diff in `WatchSession.kt`, `ComposeBridge.kt`, `PatchServer.kt`,
   `ComposeBridgeTest.kt`, `e2e/run-zero-touch.sh`, and the AGP 8 fixture property.
3. `tasks/T33-project-agnostic.md` phases 8–10 and `docs/PLAN.md`.

T33h's former failing gate is fixed. Load-bearing decisions:

- Successful literal pushes are terminal for that save and advance the source baseline;
  do not compile/swap the same literal edit again.
- Live-literal values are written to the generated helper's mutable static backing field
  while its `enabled` flag remains false.
- Keyless code changes require `forceRecomposeScopes` followed by the existing root
  content lambda. This forces stable scopes without replacing groups or losing slots.
- Resource overlays must continue using ordinary `ControlledComposition.invalidateAll`.
  Do not route resources through forced `setContent`.

Final verified gates:

- Runtime-client unit tests: PASS.
- Host core/distribution suite: PASS.
- Full offline `e2e/run-zero-touch.sh`: PASS on AGP 9/JBR 21 and AGP 8.12.3/JDK 17,
  including both device paths, state preservation, resource refresh, stable PIDs, and
  target-tree immutability.

Immediate pending work:

1. Rerun the three configured sample commands in T33h Acceptance. Their prior parallel
   invocation was interrupted before results were collected.
2. Rerun `./e2e/run.sh` and `./e2e/run-multi.sh`; these are mandatory regressions after
   the ComposeBridge/PatchServer routing change.
3. Review the entire dirty diff and run the maintainer confidentiality gates. If clean,
   mark T33h and T33 phase 8 DONE and update `docs/PLAN.md`.
4. T33 phases 9 and 10 remain unimplemented. Phase 9 needs an explicit design/spec before
   changing the IntelliJ plugin: discovery values, profiles, structured controls, and the
   resolved CLI command display. Phase 10 needs the CI compatibility matrix, generic
   advanced documentation, and final configured + zero-touch fixture coverage. Do not
   claim overall T33 DONE until the roadmap's compatibility/CI condition is actually met.

Continuation preflight and acceptance shell:

```bash
export REPO_ROOT="$(git rev-parse --show-toplevel)"
export JAVA17_HOME="/path/to/jdk-17"
source "$REPO_ROOT/scripts/env.sh"

"$JAVA_HOME/bin/java" -version 2>&1 | head -1
ls "$ANDROID_HOME/build-tools/36.0.0/d8"
ls "$ANDROID_HOME/build-tools/36.0.0/dexdump"
adb devices
adb shell getprop ro.build.version.sdk

(cd runtime-client && ./gradlew :runtime-client:testDebugUnitTest)
./gradlew :bootstrap:test :engine:test :protocol:test :cli:installDist :cli:verifyZeroTouchDistribution
(cd samples/single-module && ./gradlew --offline :app:assembleDebug :app:assembleQa)
(cd samples/single-module && ./gradlew --offline -q :app:dependencies --configuration qaRuntimeClasspath | grep -q runtime-client)
(cd samples/multi-module && ./gradlew --offline :app:assembleDebug)
./e2e/run.sh
./e2e/run-multi.sh
ZERO_TOUCH_OFFLINE=1 ./e2e/run-zero-touch.sh
```

## Read order — every session, no exceptions
1. Memory status file (`compose-hot-reload-status.md`) — state, next action, problems.
2. Memory `code-map` — every load-bearing file + gotchas. Read this INSTEAD of exploring.
3. The ONE doc the task names (e.g. T28 names research §7). Nothing else.

**Never re-explore the repo or re-derive settled facts.** These findings docs are authoritative;
if a doc and your intuition disagree, the doc wins until a live repro says otherwise:
- `docs/phase0-findings.md` — every flag/signature/pin (attach path, redefine APIs, key extraction)
- `docs/phase2-findings.md`, `docs/multi-module-design.md`, `docs/multi-module-ground-truth.md`
- `docs/resource-edits-v1.md` — resources incl. T23 bitmap mechanism
- `docs/phase6-interpreter-research.md` — interpreter: §4 fit + addendum, §5 limitations,
  §7 Proxies (T28's entire research)
- `tasks/T*.md` Outcome sections — what actually happened, incl. every spec-vs-reality delta

## Issue-tackling protocol
1. **Reproduce first, cheaply:** `scripts/` + a SINGLE e2e case, not the full suite, not ad-hoc
   adb. (`./e2e/run.sh <case>` if supported, else the case's steps by hand via scripts.)
2. **Check the known-problems list BEFORE debugging** — most "bugs" are one of these:
   - `scripts/env.sh` sourced with a relative path → JAVA_HOME unset → gradlew no-ops exit 0 →
     stale APK "ignores" edits. Always source with ABSOLUTE path; check apk mtime after builds.
   - Stale APK / baseline-dex mismatch → slot-table corruption, `ClassCastException` in
     UNEDITED composables (memory: engine-baseline-dex-mismatch). Recovery: fresh
     `installDebug` matching the watch's `--literals` mode.
   - Watch CLI must be killed with `pkill -9 -f dev.hotreload.cli.MainKt` (killing the gradle
     wrapper leaks the JVM; SIGTERM flakes pgrep for ~2 s).
   - Protocol bump → reinstall BOTH device apps + rebuild `:cli:installDist` (Ping line prints
     client protocol; doctor/WatchSession fail loud on mismatch). Currently **v7**; T28 step 3
     makes it v8.
   - Injected dex (interp.dex, lambda dexes) is IMMUTABLE per process → force-stop + relaunch
     before re-injecting a new build.
   - First watch build absorbs unseen edits into baseline WITHOUT applying; multi-part edits
     must land in ONE save; engine change → restart watch.
3. **When genuinely stuck:** write the failing observation (exact command, exact output) into
   the task file, then SPLIT: Gemini/agy reproduces and collects logs/evidence per an explicit
   spec; Opus reasons on the collected evidence — that's the token-cheap division.
4. **Escalation is a spike, not exploratory edits:** pattern `spike/` (own build.sh/run.sh,
   toy app, logcat asserts). Never edit product code to "see what happens".

## Delegation to Gemini/agy
A good spec (see `tasks/README.md` template; **T27/T28 are the exemplars**) has: exact commands,
exact file paths, exact acceptance commands, and the gotchas INLINE at the step where they bite.
Gemini executes well given that; it fails on open design questions — those go back to Opus first.

Dispatch: `scripts/delegate.sh tasks/T<NN>-*.md "<Model Name>"` (background, sandboxed) or the maintainer interactively. Run `agy models` first; Opus 4.6 is unavailable until the maintainer confirms its quota reset.
for network-heavy gradle work. Review cheaply: `git diff --stat` + spot-check load-bearing files
+ run the acceptance. Fix-ups by agy with review notes, not by Opus, unless small.

**Do-NOT-delegate list (unchanged):** `hotreload_agent.cpp` / `interpreter_jni.cpp` semantics,
ComposeBridge reflection, classifier rules, protocol message design. Review costs more than the
delegation saves.

## Standing reminders
- Protocol bump → reinstall apps + `./gradlew :cli:installDist` (the IDE plugin spawns that
  launcher — rebuild it whenever engine/protocol changes).
- Emulator: `scripts/emulator-up.sh` (pinned AVD `Medium_Phone_API_36.0`).
- Every session end: commit (incl. WIP), update the memory status file (state / next action /
  problems). Findings go in `docs/`, never only in chat.
- CI runs the full e2e gate (`e2e.yml`, 14 cases — 15 after T28). Keep main green.
