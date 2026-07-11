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
| `spike/scripts/hotswap.sh <fqcn> <key> [--structural]` | full hot-swap loop (compile→d8→push→broadcast) |

New repeated action (≥2 uses expected) → write a script first, then use it.

## Delegation protocol
Three executors:
- **Fable/Claude (scarce until Jul 7):** JVMTI agent work, Compose bridge/reflection, change
  classifier, protocol design, anything requiring debugging unknowns or architectural judgment.
- **Antigravity CLI ("agy", run by the maintainer):** well-specced mechanical work — module scaffolding,
  Gradle boilerplate, porting code to a written spec, sample apps, README/docs formatting,
  test harness plumbing, CI configs.
- **The maintainer:** provide starter projects/scaffolds when a phase needs one, run agy tasks, GUI-only
  actions (Android Studio), long installs, on-device manual testing.

Mechanics:
1. Claude writes a spec in `tasks/T<NN>-<name>.md` (template in `tasks/README.md`). Every spec
   includes an **Acceptance** section with exact commands that must pass.
2. Dispatch: Claude runs `scripts/delegate.sh tasks/T<NN>-*.md` in the background (headless
   sandboxed agy — verified working via `agy --print`). If the sandbox blocks the task
   (network-heavy Gradle work), the maintainer runs it interactively: `agy -i "implement tasks/T<NN>... exactly"`.
3. Claude reviews cheaply: `git diff --stat` + spot-check load-bearing files + run the
   acceptance commands. Fix-ups by agy (with review notes), not by Claude, unless small.
4. A task is done only when acceptance passes — that's the quality gate. Claude commits.

Session hooks (`.claude/settings.json`, scripts in `.claude/hooks/`): SessionStart injects the
protocol + pending tasks/dirty-tree state; SessionEnd auto-commits WIP (`wip:` prefix) so no
work is lost to a dead session.

Do NOT delegate: anything touching `hotreload_agent.cpp` semantics, ComposeBridge reflection,
classifier rules, or protocol message design. Delegating those costs more in review than it saves.
