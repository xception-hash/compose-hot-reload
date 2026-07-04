# T11: Resource-edits research notes (AOSP deployer reading, no code)
Status: TODO
Assignee: agy (Opus-class; network access for cs.android.com / googlesource needed — Jay runs interactively if sandboxed)

## Goal
`docs/PLAN.md` Phase 2 lists resource edits (strings/drawables/colors without reinstall) and notes
Android Studio's Apply Changes already ships this. Produce `docs/resource-edits-notes.md` so a later
implementation session starts from a map of the AOSP code instead of re-researching. Reading +
notes ONLY — no code in this repo changes.

## Spec
Read the AOSP Studio deployer source (tools/base project, `deployer/` — browse via
cs.android.com or android.googlesource.com/platform/tools/base) and write
`docs/resource-edits-notes.md` covering:

1. **Mechanism.** How Apply Changes swaps resources into a RUNNING process without reinstall:
   the device-side path (ResourcesLoader / ResourcesProvider APIs? arsc overlay? which API level
   introduced it), and the host-side path (how the changed resources are compiled/linked —
   aapt2 compile + link of a partial package? full res rebuild?).
2. **Exact AOSP pointers.** Class/file names + roles for the resource-swap path (host deployer
   classes AND the device-side installer/agent pieces). Cite paths as they appear in tools/base.
3. **Constraints.** Min API for the runtime resource-loading APIs used; which resource kinds work
   live vs which force activity restart (Apply Changes distinguishes "Apply Code Changes" vs
   "Apply Changes and Restart Activity" — record what forces the latter).
4. **Mapping onto our stack.** Given our architecture (host engine + on-device PatchServer over
   LocalServerSocket, protocol v2 opcodes in `protocol/Protocol.kt`): sketch (prose only) what a
   resource-swap would need — host side (watch res/, aapt2 steps, what bytes get sent) and device
   side (likely a new protocol request, e.g. LoadResources(bytes) + ResourcesLoader install +
   activity recreate tier-3), and what is genuinely unknown until prototyped.
5. **Feasibility verdict.** Small/medium/large effort estimate and the single riskiest unknown.

## Out of scope
- No code changes anywhere in the repo. No protocol edits (naming a hypothetical opcode in prose is fine).
- No APK/emulator experiments — this is a reading task.

## Acceptance
Run from repo root:
1. `test -f docs/resource-edits-notes.md` — exists, sections 1–5 present.
2. Doc cites ≥5 specific AOSP file/class names from tools/base deployer (spot-checkable on cs.android.com).
3. `git status` shows ONLY the new doc.
