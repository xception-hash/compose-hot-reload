# HANDOFF — Compose Hot Reload for Android (2026-07-14, post-T33d)

**Authoritative handoff is the memory file** — read it FIRST:
`<claude-project-memory>/compose-hot-reload-status.md`
(This file is tracked: NO absolute home paths, no real names — "the maintainer".)

## STATE (verified)
- **T33d (T33 phase 3) DONE — PR #14 open, awaiting CI + merge:**
  https://github.com/xception-hash/compose-hot-reload/pull/14
  (branch t33d/device-modules-launch, 8392b3d). --device, zero-config discovery
  watch, --include/--exclude-module, --launch-activity auto-launch. Implemented by
  headless agy (scripts/delegate.sh), coordinator re-verified EVERYTHING: engine
  tests 35/35 fresh, all 8 host markers, e2e run.sh all-PASS 298s + run-multi 4/4
  54s unmodified, live smokes incl. zero-config cross-module hot swap (1158ms).
  Full record: tasks/T33d-device-modules-launch.md §Outcome.
- Local main is 2 ahead of origin (session-handoff fe3cd84 + T33d spec df9a52c);
  both ride in PR #14 and de-dup on push/merge.
- Marketplace 0.1.4 still in JB moderation (plugin 32850).

## NEXT (in order)
1. The maintainer merges PR #14 (checks green?) + pushes main.
2. Coordinator session writes T33e phase-4 spec (external TOML profiles +
   `configure`) — design decisions listed in the memory Next-action entry; then
   dispatch (agy worked for T33d; keep device-heavy acceptance with coordinator).
3. After 0.1.4 approval: Marketplace metadata + release wrap (tag 0.1.4, refresh
   Release asset); next code release = 0.2.0.

## GOTCHAS (fresh, this session)
- agy's delegate.sh run hit its 45-min print-timeout on device-blocked acceptance:
  doctor hangs on the runtime handshake when the app socket is wedged/stale —
  force-stop the sample apps before doctor-based acceptance. Give delegated specs
  host-only markers.
- Readiness greps must be line-anchored `^watching ` — `discovered:` lines may
  contain the word; never edit sources before the real watching line (the initial
  build absorbs the edit into the baseline without applying).
