# Working agreement — token-efficient sessions

Binding constraint: the maintainer's $20 plan (5-hour windows; Fable until 2026-07-07, Opus after).
Rule of thumb: **tokens go to novel/hard work; everything repeatable becomes a script;
everything mechanical gets delegated.** Never at the cost of quality.

## Session loop
1. Read memory status file → `docs/PLAN.md` (relevant phase only) → `docs/phase0-findings.md` if touching device/Compose internals. Do NOT re-explore or re-research settled things.
2. Use `scripts/` for every routine action instead of ad-hoc commands.
3. Commit after every self-contained milestone; findings go in `docs/`, never only in chat.
4. End of session: update memory status file (state / next action / problems).

## Script catalog (`scripts/`, all source `env.sh` for pins/paths)
| Script | Does |
|---|---|
| `env.sh` | pinned toolchain paths (JBR, SDK, d8, AVD, package) — source it, never hardcode |
| `emulator-up.sh` | boot pinned AVD if needed, wait for full boot |
| `dev-install.sh` | build toy app → install → launch → print PID |
| `ui-state.sh` | PID + visible texts (standard post-swap verification) |
| `taps.sh [n]` | tap the counter n times (finds button, layout-proof) |
| `extract-keys.sh [fqcn]` | FunctionKeyMeta keys/offsets from compiled class |
| `stats.sh [--csv]` | product stats snapshot: GitHub stars/releases/traffic + Marketplace downloads; `--csv` appends to `~/.hotreload-stats.csv` |
| `spike/scripts/hotswap.sh <fqcn> <key> [--structural]` | full hot-swap loop (compile→d8→push→broadcast) |

New repeated action (≥2 uses expected) → write a script first, then use it.

## Delegation protocol
Three executors:
- **The coordinator:** architecture, difficult debugging, unknowns, task specification, review,
  and integration. Keep JVMTI agent semantics, Compose bridge/reflection, classifier rules, and
  protocol design here unless the maintainer approves a decision-complete delegated spec.
- **Antigravity CLI (`agy`):** well-specced independent work — module scaffolding, Gradle
  boilerplate, porting code to a written spec, sample apps, README/docs formatting, test harness
  plumbing, CI configs, and other bounded implementation or investigation.
- **The maintainer:** deferred/long-running agy dispatch, GUI-only actions (Android Studio), long
  installs, on-device manual testing, and decisions that require owner input.

Model selection is per task, not a fixed project default. Run `agy models` immediately before
dispatch and choose the least expensive capable model. Use Flash Low/Medium for mechanical work,
Flash High or a lower-cost GPT for normal implementation, and a stronger GPT/Gemini/Claude model
only when the task's reasoning demands it. **As recorded on 2026-07-15, Claude Opus 4.6 quota is
empty; do not select it until the maintainer explicitly confirms the reset.** Model listing alone
does not confirm quota availability.

Mechanics:
1. The coordinator writes a decision-complete spec in `tasks/T<NN>-<name>.md` (template in
   `tasks/README.md`). Every spec includes a recommended model, a cross-family fallback when
   possible, explicit non-goals, and an **Acceptance** section with exact commands.
2. For work intended to run later, the coordinator stops after writing the spec and reports the
   exact command the maintainer can execute with the then-available GPT or Gemini model. Do not
   dispatch deferred work implicitly.
3. For immediate work, dispatch with an explicit model:
   `scripts/delegate.sh tasks/T<NN>-<name>.md "<Model Name>"`. Run it in the background when useful;
   the script itself is foreground/blocking. If the sandbox blocks network-heavy Gradle work, the
   maintainer may run `agy -i --model "<Model Name>" "implement tasks/T<NN>-... exactly"`.
4. The coordinator reviews cheaply: `git diff --stat` + spot-check load-bearing files + run the
   acceptance commands. Fix-ups go back to agy with review notes unless they are small.
5. A task is done only when acceptance passes — that's the quality gate. The coordinator commits.

Session hooks (`.claude/settings.json`, scripts in `.claude/hooks/`): SessionStart injects the
protocol + pending tasks/dirty-tree state; SessionEnd auto-commits WIP (`wip:` prefix) so no
work is lost to a dead session.

Do NOT delegate: anything touching `hotreload_agent.cpp` semantics, ComposeBridge reflection,
classifier rules, or protocol message design. Delegating those costs more in review than it saves.
