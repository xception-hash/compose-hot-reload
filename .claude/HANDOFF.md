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

## PREFLIGHT RESULTS (this session — ALL DONE)
1. PR #16 MERGED ✅; housekeeping committed (T33 §Phase 5 DONE flip, 6c59e74).
2. Marketplace 0.1.4: STILL in JB moderation (`hasUnapprovedUpdate: true`,
   approved-updates list empty, downloads=2) — nothing actionable.
3. Telegram bridge FIXED ✅ — root cause: pingjay auto-switched to the pingme
   backend on 2026-07-12 (its CLI + venv appeared); pingme reports "sent"
   (exit 0) but FCM delivery never reaches the phone. Telegram itself works.
   Fix: pingme route in `~/.claude/bin/pingjay` now gated behind
   `PINGJAY_USE_PINGME=1`; verified pingjay → Telegram (message_id 47).

## MAIN TASK STATE
- `tasks/T33g-prepare-start.md` WRITTEN (all design decisions fixed: host-side
  JSON fingerprint bound to device base.apk sha256; refuse only on
  positively-known mismatch, warn on unknown provenance, silent when absent;
  prepare pipeline + start composition; 8 host markers).
- T33 §Phase 6 flipped to spec-ready + agent-guide order paragraph updated.
- NEXT: commit spec (grep gate first), update memory status, ask the maintainer
  IN-TERMINAL whether to dispatch
  `scripts/delegate.sh tasks/T33g-prepare-start.md` on "Gemini 3.5 Flash (High)".
- After any agy run: check `git log` on main for the SessionEnd wip-commit gotcha.

## GOTCHAS (standing)
- Confidentiality grep before EVERY commit (employer/name/home-path patterns per
  the privacy memory); tracked files say "the maintainer", never a first name.
- A stale stash `handoff-wip` (stash@{0}) holds an intermediate version of this
  file — superseded by this version; safe to drop, kept because the harness
  blocked `git stash drop`.
- export REPO_ROOT AFTER sourcing scripts/env.sh (source overwrites it).
- Readiness greps line-anchored `^watching `; never edit sources before the real
  watching line (initial build absorbs edits into baseline without applying).
