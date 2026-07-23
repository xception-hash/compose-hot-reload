# T42: Restore feature-module Compose state across configured hot swap

Status: DONE
Assignee: agy for bounded investigation and implementation; coordinator reviews every diff and runs device acceptance
Recommended model: claude-opus-4-6-thinking
Fallback model: claude-sonnet-4-6

## Dispatch

This task may be delegated when the maintainer asks for immediate execution. Query `agy models`
immediately before dispatch; model availability is dynamic. The maintainer confirmed on 2026-07-23
that Claude Opus 4.6 has quota and may be selected for this task.

```bash
scripts/delegate.sh tasks/T42-multi-module-feature-state-preservation.md "claude-opus-4-6-thinking"
```

Cross-family fallback:

```bash
scripts/delegate.sh tasks/T42-multi-module-feature-state-preservation.md "claude-sonnet-4-6"
```

The worker must not inspect `.agents/` or other untracked/private material. A previous delegated
attempt was rejected because the checkout contained the private maintainer handoff. If that recurs,
do not weaken the sandbox or copy private material; execute locally instead. The worker must not
commit, push, tag, publish, or discard existing working-tree changes.

## Problem

The configured multi-module device gate fails in case `multi-feature-composable`:

1. The app counter is tapped twice, then a `:core` Kotlin edit hot-swaps successfully and preserves
   `App count: 2`.
2. The `:feature` button is tapped. The existing assertion observes `Feature count: 1`, proving the
   selector and input event are correct.
3. A body-only edit changes `"FeatureCard: "` to `"FeatureX: "` in
   `samples/multi-module/feature/src/main/kotlin/dev/hotreload/multisample/feature/FeatureCard.kt`.
4. The watcher reports a successful targeted swap: `hot-swapped: 3 redefined, 3 groups invalidated`.
   The new label is visible and the app PID is stable, but the counter is now `Feature count: 0`.

This is state loss during a successful configured Android-library Compose hot swap. It is not an
input, launch, watcher-readiness, or compile failure. Do not weaken the assertion or remove the
capture-heavy fixture to make the gate green.

## Why it appears now

The older multi-module suite did not exercise state preservation for a capture-heavy library
composable. Commit `00d7d06` (included in PR #25 / `d72ef56`) added the `LazyColumn` item lambda,
separately created callback, and `Feature count: 1` preservation assertion to cover the configured
library-capture regression. The exact scenario is therefore newer than the original multi-module
PASS evidence. T41 changed release packaging/documentation and did not change the relevant engine
or fixture code.

## Starting state

- Branch: `release/0.2.0-narrow-support`.
- The working tree intentionally contains uncommitted Android-16 launcher substitutions in
  `e2e/run.sh`, `e2e/run-multi.sh`, and `e2e/run-configured-capture.sh`. Preserve them; they replace
  an Android 16 `monkey` launch failure with known explicit activity components.
- The configured-capture suite passes after that launcher change.
- The coordinator has a single API-36 emulator available. Do not start another emulator and never
  run two watchers against one device.

## Goal

Make the configured `:feature` Compose body hot swap preserve the remembered counter and callback
state while retaining targeted invalidation. The fix must address the product behavior, not hide
the failure in the e2e harness.

## Investigation requirements

1. Read the tracked engine, protocol, runtime-client, fixture, and `e2e/run-multi.sh` code relevant
   to changed-class collection, Compose key metadata, lambda/support classes, D8 batching, device
   patch application, and recomposition invalidation.
2. Compare the feature edit with the passing configured-capture scenario. Identify the changed class
   set and group-key/invalidation inputs for both; explain why the feature fixture loses state.
3. Add focused, deterministic host regression coverage at the narrowest useful layer. Do not make a
   device-only assertion the sole regression guard when the cause can be represented in host tests.
4. Preserve debuggable-only guards, protocol/fingerprint validation, socket/input validation, and
   release-variant safety. Do not change zero-touch behavior unless the same shared configured path
   demonstrably requires it.

## In scope

- A minimal engine/runtime/protocol fix required for the configured multi-module state-preservation
  behavior.
- Focused tests and, only if required for observability, an e2e assertion/log improvement.
- A concise tracked finding in this task’s Outcome section after coordinator-confirmed acceptance.

## Out of scope

- Changing the stable configured-mode release contract or broadening compatibility claims.
- Removing `LazyColumn`, the callback capture, the `Feature count: 1` assertion, or treating state
  reset as acceptable.
- Timing/swipe/retry workarounds for input injection.
- Zero-touch feature work, release publication, tags, pushes, PR creation, or commits by the worker.
- Editing `.agents/`, user files, or unrelated release documentation.

## Acceptance

The worker runs the relevant focused host tests it adds or changes, plus at minimum:

```bash
git diff --check
unset JAVA_HOME
source scripts/env.sh
./gradlew :engine:test :protocol:test
bash -n e2e/run-multi.sh
```

The coordinator then performs the device acceptance on exactly one API-30+ device, with JBR 21 and
build-tools 36.0.0 preflighted, capturing output to `/tmp`:

```bash
unset JAVA_HOME
source scripts/env.sh
./e2e/run-multi.sh > /tmp/t42-run-multi.log 2>&1
```

Require all four cases to pass, including all of:

- `Feature count: 1` before and after the feature body edit;
- `FeatureX:` visible after the edit;
- targeted `groups invalidated`, not whole-tree invalidation for that feature edit;
- unchanged app PID;
- no remaining watcher and fixture source restoration after the suite.

Repeat the complete multi-module suite once before accepting the fix. Then run the affected broader
configured suite(s) and record their captured results. Before any coordinator commit, run
`git diff --check` and the standing staged privacy scans; the scans must be empty.

## Outcome

**Root cause.** The source edit only changes the innermost `LazyColumn` item lambda. Kotlin also
rewrote enclosing `FeatureCard` and button-lambda bytecode because Compose embeds source-location
strings and marker-group operands in their `ComposerKt.sourceInformation*` calls. `FactsExtractor`
hashed those compiler-debug operands as executable code, so the engine redefined and invalidated
the parent `FeatureCard` group. Keyed invalidation correctly resets the keyed subtree's remembered
state, yielding `Feature count: 0` after the visible `FeatureX:` update.

**Fix.** `FactsExtractor` now normalizes only the immediate source-location constants passed to
`ComposerKt.sourceInformation` and `sourceInformationMarkerStart` before calculating a method body
hash. User literals and all non-debug instructions remain hash-significant. The feature edit now
redefines and invalidates only the actual item lambda group, preserving the outer composable's
counter and callback state.

**Regression coverage.** `FactsExtractorTest` proves that changes to both Compose
source-information forms leave the enclosing body hash unchanged, while a rendered literal still
changes it. `:engine:test :protocol:test`, shell syntax, and `git diff --check` pass.

**Device evidence (API 36, one emulator).** `e2e/run-multi.sh` passed twice (all 4/4 cases) with
the feature counter at `1` both before and after `FeatureCard:` → `FeatureX:`, targeted groups
invalidated, a stable PID, and fixture/watcher cleanup. The capture-heavy configured-library suite
also passed. The broader configured single-module suite passed all standard cases; its Android-16
runner now supplies the existing supported `.MainActivity` launch override to `hotreload start`
after prepare reinstalls the app. Captured logs: `/tmp/t42-run-multi-4.log`,
`/tmp/t42-run-multi-5.log`, `/tmp/t42-run-configured-capture.log`, and
`/tmp/t42-run-single-rerun.log`.
