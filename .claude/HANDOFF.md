# HANDOFF — Compose Hot Reload for Android (2026-07-14, T33f in flight)

**Authoritative handoff is the memory file** — read it FIRST:
`<claude-project-memory>/compose-hot-reload-status.md`
(This file is tracked: NO absolute home paths, no real names — "the maintainer".)

## GOAL / DONE-CONDITION
Land T33f (T33 phase 5, `tasks/T33f-output-metadata.md`): branch pushed, PR open,
CI green, spec flipped IN-REVIEW → DONE with Outcome, memory updated.

## STATE (VERIFIED — re-run by coordinator, not trusted from agent)
- Spec committed on main as 0be3e3e (+ roadmap flip in tasks/T33-project-agnostic.md).
  Local main = 0be3e3e, **origin + 2 — the maintainer pushes**.
- agy (Gemini 3.5 Flash High, headless delegate.sh) implemented the spec faithfully;
  full diff reviewed. Implementation = ONE clean commit **8d010f9 on branch
  `t33f/output-metadata`** (the SessionEnd wip 71597c5 was squashed in; main was
  reset back to 0be3e3e — do NOT hunt for the wip commit).
- All 9 host markers re-run green by coordinator (TESTS/SIDECAR/SIDECAR-JSON/
  PROFILE-METADATA/STALE-GUARD/NOCACHE/DISCOVERY-METADATA/FREEZE/E2E-FROZEN-OK).
- Device gate GREEN on emulator-5554: `./e2e/run.sh` all-PASS 280s (literals SKIP
  local-by-design) + `./e2e/run-multi.sh` 4/4 51s, both UNMODIFIED.
- Confidentiality grep clean on the branch commit. samples/ pristine (verified).
- Marketplace 0.1.4 still in JB moderation (approve=false) — no release wrap.

## NEXT (in order — a fresh session continues HERE)
1. Live smokes on emulator-5554 (CLI = `cli/build/install/cli/bin/cli`, rebuilt):
   a. Zero-config: `watch --project samples/multi-module` → expect `discovered:`
      + `metadata: 3 module(s) from discovery` → `^watching ` → edit
      `samples/multi-module/core/.../CoreLabel.kt` ("core says:" string) →
      `hot-swapped:` → edit `samples/multi-module/feature/src/main/res/values/strings.xml`
      (feature_label value) → `resource-swapped:` + `apk: … (output-metadata.json)`.
   b. Profile: `HOTRELOAD_CONFIG_DIR=$(mktemp -d)`; `configure --project
      samples/multi-module --save-as t33f-live` → `watch --profile t33f-live` →
      `profile:` + `metadata: … from discovery cache` → ready → edit → hot-swapped.
   Rules: force-stop dev.hotreload.multisample + dev.hotreload.sample BETWEEN watch
   sessions (T33e priming gotcha `$liveEditBytecode missing` = stale process); kill
   watchers ONLY `pkill -9 -f dev.hotreload.cli.MainKt`; never edit before the
   line-anchored `^watching `; `git checkout samples/` after each smoke.
2. Flip spec IN-REVIEW → DONE + Outcome on the branch. Note review deviations:
   dead `jsonMatched` var in ApkLocator (cosmetic); layout-by-type inferred from
   task-field presence (spec gap, functionally equivalent); ModuleSpecTest imports
   consolidated to wildcard (assertions untouched). Grep gate before commit.
3. Push branch → `gh pr create` ("T33f: build/APK/resource paths from Gradle
   metadata (T33 phase 5)"). CI e2e runs on the PR. The maintainer merges.
4. Update memory status (T33f state entry; next = phase-6 spec needs coordinator
   design session; main push reminder).
5. OPEN ISSUE: the Telegram bridge did NOT deliver this session (pingjay ran,
   nothing arrived — the maintainer reported it). Investigate next session.

## GOTCHAS
- delegate.sh dispatch already DONE — do not re-dispatch T33f.
- zsh chokes on bare `===X===` echo separators in tool shell calls — quote them.
- Readiness greps line-anchored `^watching `; initial watch build absorbs
  pre-watching edits into the baseline without applying them.

DONE: (pending — flip only when the PR is open and the spec is DONE, with PR URL
+ smoke log lines as evidence)
