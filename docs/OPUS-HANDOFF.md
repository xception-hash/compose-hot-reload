# Opus 4.8 handoff — post-2026-07-07 playbook

Fable access ended 2026-07-07. Everything Fable-class is done and written down; this file is the
operating manual for finishing the project with **Opus 4.8 + Gemini/agy + the maintainer**. The roadmap
table lives in `docs/PLAN.md` (status header) — ONE canonical copy, do not duplicate it here.

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

Dispatch: `scripts/delegate.sh tasks/T<NN>-*.md` (background, sandboxed) or the maintainer interactively
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
