# HANDOFF — Compose Hot Reload for Android (2026-07-14)

## CURRENT TASK (bg job 9b3ae766): /doctor run — Claude Code setup health check
Not project work. Follow the /doctor skill instructions (in that session's transcript):
checks 0–9 read-only, ONE report, at most TWO AskUserQuestion gates (cleanup 0–4+7;
separate permission gate 8+9). NO edits before gates. Done-condition: report + gates
asked + confirmed actions applied with undo list.

VERIFIED so far (all read-only):
- Check 0 CLEAN: single native install 2.1.208 via `~/.local/bin/claude`, matches
  installMethod=native; no `~/.claude/local`; all settings files parse OK; no project
  `.mcp.json`; only agent file `~/.claude/agents/notifier.md` (frontmatter not yet validated).
- Check 1: NO MCP servers configured anywhere. 19 user skills in `~/.claude/skills`
  (Android pack); skillUsage lifetime counters: only android-cli(5) + agp-9-upgrade(1)
  of the pack ever used → other 17 pack skills zero lifetime uses. pluginUsage:
  cowork-plugin-management@inline 0, design@inline 0 (seed-only lastUsedAt),
  clangd-lsp@claude-plugins-official 1 (enabled in user settings, LSP counter → keep).
  numStartups 87.
- Checks 2/3/4: NO checked-in CLAUDE.md/rules in repo, no CLAUDE.local.md; only
  `~/.claude/CLAUDE.md` (3767 chars) → little/nothing to do.
- Check 7: v2.1.208, native, channel latest, nonessential traffic allowed → GET
  https://downloads.claude.ai/claude-code-releases/latest from $HOME and compare.
  `autoUpdates:false` in `~/.claude.json` (user choice) explains any staleness; propose
  manual `claude update` if behind.
- Check 8: defaultMode unset in all scopes, no disableAutoMode → PROPOSE
  `permissions.defaultMode: "auto"` in `~/.claude/settings.json` (gate 2).
- Hooks configured: user PostToolUse+Stop (quota-handoff-nudge), project
  SessionStart+SessionEnd. Existing local allow rule: `Bash(agy --help)`.

ALL SCANS DONE (read-only). Findings: v2.1.208 vs latest 2.1.209 (autoUpdates:false,
user choice → propose manual `claude update`); no npm leftover; notifier.md frontmatter
valid; transcript window 50 sessions Jul 10–14: hooks all fast (max median 175ms,
no timeouts), denials all 1–2 count and none read-only-vetted → check 9: NO proposals;
17 of 19 Android-pack skills (installed Jun 29) zero lifetime uses except agp-9-upgrade
1 use Jul 3 (one-shot, done) → propose disabling 17 via skillOverrides in
`~/.claude/settings.json` (keep android-cli 5 uses Jul 11, agent-craft = the maintainer's own,
referenced by global CLAUDE.md); skill listing ~11.5k chars ≈ ~2.9k est tokens, over
~1% budget → truncation risk, fixed by the disables; cowork-plugin-management@inline +
design@inline are built-ins (not in installed_plugins.json) → not touching; clangd-lsp
keep (1 LSP use Jul 10). Checks 2/3/4: nothing (no checked-in CLAUDE.md anywhere).
Check 8: propose defaultMode "auto" in `~/.claude/settings.json` (gate 2, separate).

DONE: /doctor complete. the maintainer approved cleanup, declined auto-mode default. Applied:
17 skillOverrides "off" merged into `~/.claude/settings.json` (verified: 17 keys);
`claude update` 2.1.208 → 2.1.209 (verified via `claude --version`). No other files
touched. Undo: remove skillOverrides entries. This section can be deleted next session.
Gotcha: this file is tracked — NO absolute home paths (use ~), no real name.

---


**Authoritative handoff is the memory file** — read it FIRST:
`<claude-project-memory>/compose-hot-reload-status.md`

## ACTIVE TASK: prove IDE plugin uses NO internal API (the maintainer's ask this session)
Context: Marketplace rejected 0.1.2/0.1.3 for `@ApiStatus.Internal` PluginManager APIs;
0.1.4 removed them all (merged, main 1816f7a). the maintainer asked to make sure nothing internal
remains. Task = verify against the EXACT Marketplace verifier build + land the verifier
config so this drift class is caught locally forever.

### Done-condition (checkable)
1. verifyPlugin reports show Compatible + ZERO internal-API for ALL THREE targets incl.
   IC-262.8665.176 (the exact Marketplace rejection build).
2. build.gradle.kts pluginVerification change committed on a branch + PR (guard blocks main).
3. Memory status updated; the maintainer pinged on Telegram.

### VERIFIED so far
- **T33c fully DONE earlier this session: PR #12 open**
  (https://github.com/xception-hash/compose-hot-reload/pull/12, branch
  t33c/inspect-discovery, f37fc51). All acceptance re-verified by coordinator, e2e 16/16
  + run-multi 4/4 green unmodified, spec flipped DONE, memory updated, the maintainer pinged.
  Only the maintainer's merge left. T33d spec is the next queued work after merge.
- Plugin source grep CLEAN: PluginManager/getPluginByClass only in comments.
- `verifyPlugin` **BUILD SUCCESSFUL (9m41s)** against IC-251.23774.435 (2025.1),
  IC-261.26222.65 (2026.1.4), **IC-262.8665.176** (Marketplace's build). Log tail: only
  the 3 known StatusBarWidget deprecation warnings. Reports:
  `intellij-plugin/build/reports/pluginVerifier/IC-*/report.html` (html only, no .md).
- Enabler discovered: public snapshots repo NOW has the 262 EAP line
  (`262.8665.176-EAP-SNAPSHOT`) — old "verifier can't see 2026.2" blind spot closable.

### DONE: all verdicts parsed — Compatible + ZERO internal-API on all three targets,
including IC-262.8665.176 (the Marketplace rejection build). Committed f8382d3 on
`chore/verifier-262-gate` → **PR #13**
(https://github.com/xception-hash/compose-hot-reload/pull/13). Memory updated.
Confidentiality grep ran clean pre-commit (after fixing a /Users path this file briefly
contained — keep absolute home paths OUT of tracked files).

### Remaining
- the maintainer merges PR #12 (T33c) and PR #13 (verifier gate).
- Next session: write T33d phase-3 spec (needs coordinator design decisions).

## Gotchas
- `source scripts/env.sh` OVERWRITES exported REPO_ROOT — source FIRST, then
  `export REPO_ROOT="$(pwd)"` from the repo root (never rely on env.sh's own derivation).
- verifyPlugin = separate gradle build: `cd intellij-plugin && ./gradlew verifyPlugin`.
- Harness guard blocks main commits — always branch.
- Kill watchers via `pkill -9 -f dev.hotreload.cli.MainKt`; protocol v8.
- Emulator-5554 is up (from T33c gate). T33c worktree still at
  `.claude/worktrees/agent-a01cfa192aa03644d` (branch pushed; removable after #12 merges).
- `external/` is gitignored (employer docs) — never commit it.
