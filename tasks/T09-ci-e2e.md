# T09: GitHub Actions CI for the e2e harness
Status: TODO
Assignee: Opus (interactive — needs iteration against real CI runs)

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
