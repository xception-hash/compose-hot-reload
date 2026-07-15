# HANDOFF — Compose Hot Reload for Android (2026-07-15, T33g review session)

**Authoritative handoff is the memory file** — read it FIRST:
`<claude-project-memory>/compose-hot-reload-status.md`
(This file is tracked: NO absolute home paths, no real names — "the maintainer".)

## GOAL (this session) — DONE ✅
Review T33g (prepare/start orchestration + build fingerprints, implemented by the
previous coordinator session on branch `t33g/prepare-start`), run the device gate,
update docs + memory, land it.

DONE: **PR #17 open with CI GREEN** (e2e pass 9m30s, run 29387565544) —
https://github.com/xception-hash/compose-hot-reload/pull/17
Evidence: all 8 host acceptance markers re-run green; device gate all-PASS on the
pinned emulator (e2e run.sh all-PASS 271s + run-multi.sh 4/4 50s UNMODIFIED;
prepare/match/mismatch/outside-install/start smokes — the literals-mismatch refuse
closes the baseline/dex-mismatch incident). Task file flipped DONE with the gate
record; T33 roadmap + PLAN.md updated (phases 1–7 DONE); memory status +
baseline-dex-mismatch memory + memory index updated; maintainer pinged on
Telegram. Cleanup done: smoke fingerprints removed, pristine sample reinstalled,
watchers pkilled.

## PENDING (nothing blocks; next session picks from here)
1. **Maintainer merges PR #17** (branch `t33g/prepare-start`, 2 commits on top of
   the wip auto-commit; squash-merge like prior T33 PRs). After merge: sync local
   main, delete the branch.
2. **T33 phases 8–10** — maintainer-led, need a coordinator DESIGN session before
   any delegation (8 zero-touch init-script bootstrap, 9 IDE-plugin
   discovery/profiles consumption, 10 compat matrix + docs). No dispatch-ready
   spec remains.
3. **tasks/T31 header is STALE on main** (says 0.1.2/awaiting-moderation; reality:
   0.1.4 published, in JB moderation). Update it on the next main-touching session.
4. External wait: Marketplace 0.1.4 moderation. Queued someday: tag 0.2.0 release
   wrap; PatchServer wedge robustness fix (re-arm soTimeout between sessions).

## GOTCHAS (standing)
- Confidentiality grep before EVERY commit (employer/name/home-path patterns per
  the privacy memory); tracked files say "the maintainer", never a first name.
- A stale stash `handoff-wip` (stash@{0}) holds an old version of this file —
  superseded; safe to drop, kept because the harness blocked `git stash drop`.
- export REPO_ROOT AFTER sourcing scripts/env.sh (source overwrites it).
- Readiness greps line-anchored `^watching `; never edit sources before the real
  watching line (initial build absorbs edits into baseline without applying).
- Fingerprint files live under the config dir `fingerprints/`; e2e relies on NONE
  existing (absent = byte-silent freeze). Remove smoke fingerprints after device
  work.
