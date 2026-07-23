# 0.2.0 Release Handoff

## Goal

Ship the 0.2.0 release with configured Gradle-plugin integration and reviewed profiles as the only
stable path. T41 is complete only after PR merge, GitHub Release, real JitPack resolution,
Marketplace approval, and Marketplace-artifact smoke.

## Checkable done-condition

The merged/tagged `0.2.0` commit has a GitHub Release, JitPack resolves the Gradle plugin marker,
plugin module, and runtime AAR through `scripts/verify-release-artifacts.sh 0.2.0
https://jitpack.io`, and the approved Marketplace artifact passes configured Start → Ready →
visible edit/revert → Stop on a production target. T41’s Completion record then contains release
URLs and evidence.

## Current state

### Verified

- Branch `release/0.2.0-narrow-support` is pushed and includes `03904db`; PR #29 is open against
  `main`.
- Local host/documentation/version/Maven Local artifact gates, configured single/multi/capture,
  packaged AGP-8/JDK-17 and AGP-9/JDK-21 lanes, clean-clone onboarding, and production-target
  configured smoke all passed.
- Local 0.2.0 IDE ZIP passed test/build/verifier and Android Studio Start → Ready → visible
  edit/revert → Stop smoke. This is not Marketplace-artifact evidence.
- The corrected compatibility-contract workflow for `03904db` is green. At this checkpoint the
  required e2e/compatibility device workflow remains in progress.

### Believed / wait to observe

- PR #29 will become mergeable only when every required CI check completes green and review is
  satisfactory. Re-check with `gh pr checks 29 --watch=false`; do not infer success from the
  contract check alone.

### No external release state changed

- No merge, `0.2.0` tag, GitHub Release, JitPack build, Marketplace upload, or Marketplace smoke
  has occurred.

## Findings and gotchas

- Android 16 may reject `adb shell monkey`; use an explicit launch component and pin
  `--launch-activity` before prepare.
- Watchers are blocking daemons: run only one, wait for `watching …`, and use log completion lines
  rather than process lifetime as evidence.
- Clear a conflicting target `JAVA_HOME` before sourcing `scripts/env.sh`; the CLI uses JBR 21.
- A profile is a saved CLI configuration. IDE structured fields override it. In the local IDE ZIP
  smoke, the default watched module `app` overrode a multi-module profile and produced a safe
  fingerprint rejection; exact prepared module mappings fixed it. Do not use `--ignore-fingerprint`.
- The production target was outside tested lanes. It is best-effort evidence, not a new universal
  compatibility claim.

## Next steps

1. Wait for and inspect PR #29 checks. If a check fails, read its full log and reproduce the exact
   contract before editing.
2. Ask the maintainer for explicit approval to merge only after all required checks are green.
3. After merge, ask separately before each external mutation: tag/GitHub Release; JitPack trigger;
   Marketplace signing/submission.
4. After publication, verify real JitPack resolution, Marketplace listing, and Marketplace-artifact
   smoke. Update T41/T43/roadmap provenance and add the T41 Completion record.

## Key records

- `tasks/T41-narrow-supported-release.md` — release contract, evidence, acceptance, publication
  sequence.
- `tasks/T43-marketplace-plugin-onboarding.md` — Marketplace-specific work and post-publication
  acceptance.
- `docs/PLAN.md` — canonical roadmap status.
- `.agents/STATUS.md` — detailed maintainer session history; never commit it.
