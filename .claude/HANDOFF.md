# HANDOFF — Compose Hot Reload for Android (2026-07-15, T33g coordinator session)

**Authoritative handoff is the memory file** — read it FIRST:
`<claude-project-memory>/compose-hot-reload-status.md`
(This file is tracked: NO absolute home paths, no real names — "the maintainer".)

## PREVIOUS GOAL (T33f) — DONE ✅
DONE: T33f merged via **PR #16** (squash 9da19e0, e2e CI green, merged
2026-07-15T02:58Z). Evidence: `gh pr view 16` → MERGED; coordinator had re-run all
9 host markers + device gate (run.sh all-PASS 280s, run-multi 4/4 51s) before the
PR. Post-merge housekeeping done this session: local main reset to origin/main
(carried commits de-duped by the squash), `t33f/output-metadata` branch deleted,
T33 §Phase 5 flipped DONE.

## GOAL (this session)
Write `tasks/T33g-prepare-start.md` (T33 phase 6: `hotreload prepare` + `hotreload
start` orchestration + build fingerprints with refuse-to-watch on mismatch), commit
to main, update memory, then ask the maintainer IN-TERMINAL whether to dispatch via
`scripts/delegate.sh` on "Gemini 3.5 Flash (High)".

**Done-condition:** spec committed on main (confidentiality grep clean), T33
§Phase 6 flipped to spec-ready, memory status updated, maintainer asked about
dispatch.

## PREFLIGHT RESULTS (this session)
1. PR #16 MERGED ✅ (see above). Housekeeping commit pending on main.
2. Marketplace 0.1.4: STILL in JB moderation (`hasUnapprovedUpdate: true`,
   approved-updates list empty, downloads=2) — nothing actionable.
3. Telegram bridge debug — NOT yet done this session (next preflight step).

## NEXT STEPS (in order)
1. Commit housekeeping (T33 §Phase 5 DONE flip + this file) — grep gate first.
2. Debug Telegram bridge: `--notify` test, check credentials file + `--protocol`;
   fix or report exactly what's broken. Until fixed: ask maintainer in-terminal.
3. Write tasks/T33g-prepare-start.md. Design inputs (coordinator FIXES these in
   the spec, executing agent must not improvise):
   - prepare = build → install → launch instrumented APK; start = doctor →
     prepare-if-needed → watch. REUSE T33d ensureAppRunning + T33f
     metadata/ApkLocator — no re-implementation. Sacred watch path untouched.
   - Fingerprints: variant, modules, compiler flags, runtime version, JDK, gradle
     args; refuse-to-watch on installed-APK/config mismatch (closes the
     engine-baseline-dex-mismatch hole — stale APK silently corrupts slot table).
   - Open decisions to fix: fingerprint format + storage (device? profile dir?
     APK meta?); staleness detection vs installed APK; prepare sequencing vs
     auto-launch; start composition; profile interaction.
   - Template: tasks/T33f-output-metadata.md (host-only greppable markers,
     run.sh/run-multi.sh byte-identical, device gate = coordinator, force-stop
     apps between watch sessions).
4. Commit spec (grep gate), flip T33 §Phase 6 to spec-ready, update memory
   status, ask maintainer about dispatch IN-TERMINAL.
5. After any agy run: check `git log` on main for the SessionEnd wip-commit gotcha.

## GOTCHAS (standing)
- Confidentiality grep before EVERY commit (employer/name/home-path patterns per
  the privacy memory); tracked files say "the maintainer", never a first name.
- A stale stash `handoff-wip` (stash@{0}) holds an intermediate version of this
  file — superseded by this version; safe to drop, kept because the harness
  blocked `git stash drop`.
- export REPO_ROOT AFTER sourcing scripts/env.sh (source overwrites it).
- Readiness greps line-anchored `^watching `; never edit sources before the real
  watching line (initial build absorbs edits into baseline without applying).
