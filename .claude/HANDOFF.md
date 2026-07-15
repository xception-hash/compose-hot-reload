# HANDOFF — Compose Hot Reload for Android (2026-07-15, agent-handoff session)

**Authoritative live status is `.agents/STATUS.md`** (untracked, in this checkout) —
read it FIRST. It is the shared status file for ALL agents (any vendor); the
Claude project memory file is a private mirror of it and may lag.
(This file is tracked: NO absolute home paths, no real names — "the maintainer".)

## GOAL (this session) — Codex handover setup
Make the project agent-neutral so a different CLI agent (Codex) can pick up work,
while the existing Claude memory stays intact for a possible switch-back.
Done-condition (checkable):
1. `.agents/{README,STATUS,CODE-MAP,GOTCHAS}.md` exist, gitignored
   (`git check-ignore .agents/STATUS.md` passes).
2. Tracked pointer changes committed on branch `docs/agent-handoff` → PR:
   `.gitignore` (+`.agents/`), `AGENTS.md` (maintainer-agents pointer section),
   this file's header (authoritative → `.agents/STATUS.md`).
3. Claude memory status + index note the handover and the switch-back procedure.

## STATE
- **Verified:** `.agents/STATUS.md` (verbatim memory status export + canonical
  header), `.agents/CODE-MAP.md`, `.agents/GOTCHAS.md` (3 gotcha memories) created.
  This file's header repointed (this edit).
- **TODO next (in order):** write `.agents/README.md` (session protocol + first-
  session prompt for the new agent); add `.agents/` to `.gitignore`; add the
  maintainer-agents pointer section to `AGENTS.md`; confidentiality-grep the diff;
  branch `docs/agent-handoff` off origin/main (do NOT commit on `t33g/prepare-start`
  — PR #17 open) and open a PR; append handover+switch-back section to the Claude
  memory status file + index note.

## PENDING (project queue)
1. **PR #17 MERGED** (main = 5db0e43, 2026-07-15). Remaining cleanup: sync local
   main, delete local branch `t33g/prepare-start`.
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
- NEVER commit `.agents/` — it holds unsanitized local handoff content.
- A stale stash `handoff-wip` (stash@{0}) holds an old version of this file —
  superseded; safe to drop, kept because the harness blocked `git stash drop`.
- export REPO_ROOT AFTER sourcing scripts/env.sh (source overwrites it).
- Readiness greps line-anchored `^watching `; never edit sources before the real
  watching line (initial build absorbs edits into baseline without applying).
- Fingerprint files live under the config dir `fingerprints/`; e2e relies on NONE
  existing (absent = byte-silent freeze). Remove smoke fingerprints after device
  work.
