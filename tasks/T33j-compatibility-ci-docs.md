# T33j: Compatibility matrix, CI fixtures, and generic configuration docs
Status: DONE (2026-07-15)
Assignee: agy
Recommended model: Gemini 3.5 Flash (Medium)
Fallback model: GPT-OSS 120B (Medium)

## Dispatch

```bash
scripts/delegate.sh tasks/T33j-compatibility-ci-docs.md "Gemini 3.5 Flash (Medium)"
```

## Goal

Complete T33 phase 10 by making the existing AGP 8 standalone-Kotlin flavored fixture and AGP 9
built-in-Kotlin fixture part of CI, recording the configured/zero-touch compatibility coverage,
and replacing case-study wording with a generic advanced configuration guide.

## Spec

- Extend the GitHub Actions workflow with a separate compatibility job that runs the offline
  `e2e/run-zero-touch.sh` fixture suite on API 36 after building the CLI distribution. Install JDK
  17 in addition to JDK 21 and export an explicit `JAVA17_HOME`; preserve the existing configured
  e2e job unchanged. Keep every action pinned to a verified full commit SHA and least-privilege
  permissions.
- Make the compatibility job fail loudly if its AGP 8/JDK 17 leg is skipped. It must exercise the
  committed AGP 8 standalone-KGP flavored-variant fixture and the AGP 9 built-in-Kotlin fixture,
  including their zero-touch inspect/configure/prepare/watch paths. Do not add any target-project
  modifications during the test.
- Add a concise compatibility matrix to README (or a new linked docs page) covering AGP/Kotlin/JDK,
  single/multi module, physical-dir mismatch, flavored/custom debuggable variant, JVM dependency,
  configured versus zero-touch mode, and CI fixture location. State what remains unsupported rather
  than overclaiming multiple devices or Groovy coverage unless an actual fixture is added.
- Rewrite the advanced configuration example in README to use neutral paths/names and show both
  configured and zero-touch `start` flows, profiles, explicit module directory mappings, variants,
  target JDK, Gradle args, and device selection. Do not expose personal paths or case-study identities.
- Add lightweight shell/static checks where useful to make the workflow/docs contract regressable.

## Out of scope

- New Gradle discovery semantics, IDE UI changes, ComposeBridge/JVMTI/protocol changes, credentials,
  or unpinned GitHub Actions references.
- Do not modify owner-owned `AGENTS.md`, `docs/WORKFLOW.md`, `scripts/delegate.sh`, `tasks/README.md`,
  or `.agents/`.

## Acceptance

```bash
git diff --check
./gradlew :bootstrap:test :engine:test :protocol:test :cli:installDist :cli:verifyZeroTouchDistribution
ZERO_TOUCH_OFFLINE=1 JAVA17_HOME=/path/to/jdk-17 ./e2e/run-zero-touch.sh
./e2e/run.sh
./e2e/run-multi.sh
```
