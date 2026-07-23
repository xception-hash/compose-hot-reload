# 0.2.0 Release Handoff

## Goal

Ship the 0.2.0 release with configured Gradle-plugin integration and reviewed profiles as the only
stable path. T41 is complete after PR merge, GitHub Release, real JitPack resolution, Marketplace
approval, and Marketplace-artifact smoke.

## Checkable done-condition

The merged/tagged `0.2.0` commit has a GitHub Release, JitPack resolves the Gradle plugin marker,
plugin module, and runtime AAR through `scripts/verify-release-artifacts.sh 0.2.0
https://jitpack.io`, and the approved Marketplace artifact passes configured Start → Ready →
visible edit/revert → Stop on a production target. T41’s Completion record then contains release
URLs and evidence.

## Current state

### Verified

- PR #29 was merged as `7f3399b46ede24241c394e95fe289980126f6f16` with all required
  checks green. Annotated tag `0.2.0` points at that exact commit and is pushed.
- GitHub Release `0.2.0` is public at
  https://github.com/xception-hash/compose-hot-reload/releases/tag/0.2.0. It targets
  `7f3399b` and contains the verified `cli.zip` plus the signed
  `hotreload-intellij-plugin-0.2.0-signed.zip`; their published SHA-256 digests match
  the locally built exact-commit assets.
- The exact-commit CLI distribution and IDE plugin passed local build/test/verifier
  gates before signing. The IDE ZIP descriptor reports plugin `dev.hotreload.ide`,
  version `0.2.0`, and the reviewed Marketplace description.
- JitPack built tag `0.2.0` from `7f3399b` successfully. The corrected consumer gate
  `scripts/verify-release-artifacts.sh 0.2.0 https://jitpack.io` passes and proves
  JitPack's real marker POM, Gradle plugin module, and runtime AAR resolve.
- Marketplace submission completed from the verified signed ZIP. Update `1114979`
  is version `0.2.0` and is approved.
- The official Marketplace 0.2.0 archive was downloaded and its plugin JAR byte-matches the
  installed 0.2.0 plugin bundle.
- The Marketplace-bundled CLI completed a configured production smoke in an isolated public
  multi-module target checkout: Prepare and Doctor passed, the watcher reached `watching …`, a
  visible body edit and reversal hot-swapped on one app PID, and Stop ended the watcher.
- Local host/documentation/version/Maven Local artifact gates, configured single/multi/capture,
  packaged AGP-8/JDK-17 and AGP-9/JDK-21 lanes, clean-clone onboarding, and production-target
  configured smoke all passed.
- Local 0.2.0 IDE ZIP passed test/build/verifier and Android Studio Start → Ready → visible
  edit/revert → Stop smoke. This is not Marketplace-artifact evidence.
- The corrected compatibility-contract workflow for `03904db` is green.

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

DONE: T41 and T43 are complete. Evidence: approved Marketplace 0.2.0 archive, byte-matched
installed bundle, and configured production Prepare → Doctor → Ready → visible edit/revert → Stop
smoke. Optional T36 notification cosmetics and PatchServer timeout re-arming remain separate work.

## Key records

- `tasks/T41-narrow-supported-release.md` — release contract, evidence, acceptance, publication
  sequence.
- `tasks/T43-marketplace-plugin-onboarding.md` — Marketplace-specific work and post-publication
  acceptance.
- `docs/PLAN.md` — canonical roadmap status.
- `.agents/STATUS.md` — detailed maintainer session history; never commit it.
