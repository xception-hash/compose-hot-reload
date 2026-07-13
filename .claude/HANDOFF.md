# HANDOFF — Compose Hot Reload for Android

## ACTIVE TASK (2026-07-13, Opus): fix Marketplace internal-API rejection
Marketplace bounced the plugin update: verifier 1.408 vs **IntelliJ 2026.2 RC** flags
**1 internal API — `PluginManager.getPluginByClass(Class)`** (3 call sites).
Trap: 0.1.2's rejection was `PluginManagerCore.getPlugin` → swapped to `getPluginByClass`,
which is ALSO @Internal on 2026.2. **VERIFIED (WebFetch of intellij-community master):
EVERY `PluginManager` descriptor-lookup method is now @ApiStatus.Internal** —
`getPluginByClass`, `findEnabledPlugin`, `getPlugin`, `getPlugins`, all of them. No public
platform accessor exists for a plugin's own descriptor. So the fix must NOT use any of them.

**Approach (version-proof, no platform plugin-descriptor API):**
- Plugin VERSION (cosmetic, crash-report body) → inject at BUILD time as a generated
  properties resource read via classloader. Fallback "unknown".
- Plugin INSTALL PATH (needed for bundled CLI at `<pluginDir>/cli/bin/cli`) → derive at
  runtime from our OWN class's jar location (`getResource(".class")` → `jar:file:.../lib/x.jar!/…`
  → up two dirs). Pure JVM/URL, no platform API. Settings CLI-path override stays as escape hatch.
- New file `PluginInfo.kt` centralizes both. Removes `import ...PluginManager` from both files.

**Done-condition:** `cd intellij-plugin && source ../scripts/env.sh && ./gradlew test buildPlugin`
green; no source references `getPluginByClass`/`PluginManager`/`PluginManagerCore`; the built
zip's `<pluginDir>/cli/bin/cli` still resolves at runtime (bundled-CLI Start works on device).
NOTE: local `verifyPlugin` pins 2025.1 where these APIs are NOT yet @Internal — it will pass
even on the OLD code, so it can't catch this. Real gate = Marketplace 2026.2 verifier. Consider
adding a 2026.1+ IDE to `pluginVerification.ides` (heavy download) to catch future drift.
Then bump pluginVersion → 0.1.4 + changeNotes and publish.

Branch: made off `feat/project-portability` current tree — REBASE onto main before PR (this
fix is release-critical and independent of the T33 portability work).

---

**Authoritative handoff is the memory file**, not this one:
`~/.claude/projects/-Users-jay-projects-compose-hot-reload/memory/compose-hot-reload-status.md`
(read it FIRST — full per-session history, decisions, gotchas). This file is a thin
pointer maintained for the Stop-hook guard.

## Goal
OSS replica of hotswan.dev — Flutter-style hot reload for Jetpack Compose on Android
devices, zero edits to existing app code. Plan: `docs/PLAN.md`.

## Done-condition (project-level)
T21–T32 shipped; v0.1.x publicly resolvable; IDE plugin on JetBrains Marketplace.
✅ ALL MET. Current active thread = project portability (run on arbitrary Android
projects, not just bundled samples).

## Current state (2026-07-13) — VERIFIED
- Branch `feat/project-portability`, HEAD `16532f4`, **in sync with origin, tree clean**.
- **PR #9** (portability: custom variants, module-dir mapping, target-JDK, gradle args)
  is **OPEN, MERGEABLE, CI e2e GREEN** (run 29261927318). Waiting on **Jay to merge**.
- Plugin **0.1.2 PUBLISHED, in JetBrains moderation** (~2 days from 2026-07-11).

## Believed (recorded, not re-verified this session)
- Host gates + e2e (run.sh 15, run-multi.sh 4) were green when PR #9 landed.
- interp-dex-repro didn't run on PR #9 (paths trigger; branch doesn't touch the dex build).

## Next steps (fresh session starts here)
1. PR #9 is green — **Jay merges** (harness guard blocks direct main push).
2. Then: T33 implementation. Specs are dispatch-ready, all Status: TODO:
   `tasks/T33-project-agnostic.md`, `tasks/T33a-config-model.md`,
   `tasks/T33b-plugin-variant-wiring.md`. Phase 1 partially done by PR #9.
3. After 0.1.2 approval: Marketplace metadata + `git tag 0.1.2` + refresh GitHub Release
   asset, then ship 0.1.3. Next feature release = **tag 0.2.0** (portability + minSdk-24 AAR).

## Open robustness finding (T30-class, not blocking)
Killed watchers can WEDGE the device PatchServer accept loop — post-handshake reads have
no timeout (P0 fix guards only the first frame); dead-peer EOF doesn't propagate through a
stale adb forward → later `ping()`s hang silently. Recovery: force-stop + relaunch app.
Candidate fix: re-arm `soTimeout` between sessions or health-check the accept loop.

## Gotchas (device work)
- Always `source scripts/env.sh` with an ABSOLUTE path (else JAVA_HOME unset → gradlew
  no-ops exit 0 → stale APK ignores edits; check APK mtime).
- Kill watchers via `pkill -9 -f dev.hotreload.cli.MainKt`.
- Protocol is **v8**; before any device work reinstall BOTH sample apps + rebuild CLI
  launcher (`:cli:installDist`) + force-stop apps once (injected dex immutable per process).
- `external/` is gitignored (employer docs) — never commit it.

DONE: status-check session only; no code changed. Master state current in memory file above.
