# T09: GitHub Actions CI for the e2e harness
Status: DONE (2026-07-04) — both acceptance checks pass on real CI runs
Assignee: Opus (interactive — needs iteration against real CI runs)

## Result (2026-07-04)
- `.github/workflows/e2e.yml` + one-line `scripts/env.sh` ANDROID_HOME guard (only code change).
  Repo `xception-hash/compose-hot-reload` (private), branch `ci/t09-e2e`, PR #1.
- Acceptance #1 (green run): PASS — run 28700543687 (12m40s), all 5 e2e cases on the CI emulator.
  https://github.com/xception-hash/compose-hot-reload/actions/runs/28700543687
- Acceptance #2 (deliberate break → red + log artifact): PASS — renamed Counter→CounterBroken,
  run 28701799424 went red at the e2e gate with `e2e-run-log` (661 B) attached; then reverted.
  https://github.com/xception-hash/compose-hot-reload/actions/runs/28701799424
- Fixed a self-inflicted bug found while wiring: `run.sh 2>&1 | tee log` masks the exit code
  (pipeline returns tee's 0); changed to redirect + capture + `exit $code` (commit ce94b90).
- First-try clean once Actions was unblocked: KVM, pinned SDK (build-tools;36.0.0, ndk;28.2.13676358,
  cmake;3.22.1), NDK native build, installDebug, watch loop, all cases.

### Gotchas hit (for future CI work)
- New private repos need Actions billing set up; symptom is an account-wide lock
  ("job was not started because your account is locked due to a billing issue") that even a
  public flip does NOT bypass. The maintainer cleared it in github.com/settings/billing.
- Runner is ~13 min/run (emulator boot + NDK). Well within the 2000 free Linux-min/month.
- Harmless annotation: Node 20 deprecation for the marketplace actions (forced to Node 24).

## Goal
`.github/workflows/e2e.yml` running `e2e/run.sh` on push/PR to main, so engine/plugin
regressions are caught without a local emulator session.

## Spec
- Ubuntu runner with KVM (`reactivecircus/android-emulator-runner`), API 36 x86_64 AVD to
  match the pinned local AVD; JDK from `setup-java` (Temurin 21 — CI does not have the JBR;
  if anything in scripts hard-requires JBR-specific behavior, document it in the workflow).
- `scripts/env.sh` hardcodes local paths (JBR, SDK) — CI must override via env vars, not
  edit the script. If env.sh can't be sourced cleanly on Linux, add a guarded CI branch to
  env.sh (smallest possible change) rather than duplicating pins in the workflow.
- Cache Gradle (`gradle/actions/setup-gradle`).
- Upload the e2e log as an artifact on failure.
- Kill leaked CLI watchers exactly like e2e/run.sh does (`pkill -f dev.hotreload.cli.MainKt`)
  in an `always()` cleanup step.

## Out of scope
No changes to e2e/run.sh case logic. No matrix builds. No release/publish jobs.

## Acceptance
1. Workflow green on a real push to a branch of the GitHub repo (link the run in this file).
2. A deliberately broken commit (e.g. rename a composable the e2e edits) makes the workflow
   fail with the e2e log artifact attached — then revert.
