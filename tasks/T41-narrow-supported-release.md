# T41: Ship 0.2.0 with a narrow supported path and AI-assisted setup

Status: DONE
Assignee: agy for bounded documentation/packaging implementation; coordinator reviews every diff
and runs all acceptance gates; maintainer alone authorizes and performs publication
Recommended model: Gemini 3.1 Pro (Low)
Fallback model: GPT-OSS 120B (Medium)

## Dispatch

This task is intentionally deferred. Do not dispatch it until the maintainer asks to begin.
Model availability is dynamic: run `agy models` immediately before dispatch, choose the least
expensive capable model, and do not use Claude Opus 4.6 until the maintainer confirms its quota has
reset. From the 2026-07-18 model list, the recommended command is:

```bash
scripts/delegate.sh tasks/T41-narrow-supported-release.md "Gemini 3.1 Pro (Low)"
```

Cross-family fallback:

```bash
scripts/delegate.sh tasks/T41-narrow-supported-release.md "GPT-OSS 120B (Medium)"
```

The worker must not inspect `.agents/` or other untracked/private material. If external delegation
rejects the checkout because it can see private untracked material, do not weaken the sandbox or
copy that material elsewhere. Execute locally. Delegated output is not accepted without the
coordinator's complete diff review and the acceptance gates below.

## Starting point

PR #26 (`4147420`, `T37: complete production validation and Ready state`) merged on 2026-07-18
with both `e2e` and `compatibility` checks green. Before implementation, update the local `main`
through the normal non-destructive workflow and require that commit to be present. Start a new
branch; never work directly on or push directly to `main`.

The public release is currently fragmented:

- GitHub/JitPack Gradle-plugin and runtime-client artifacts are 0.1.6.
- JetBrains Marketplace carries IDE plugin 0.1.8 with its bundled CLI/runtime payload.
- Configured-mode parity fixes and the final production-trial fixes landed after 0.1.6.

T41 creates one coherent 0.2.0 release and narrows what the project promises. It is a product
contract, documentation, packaging, and release-validation task. It is not another compatibility
expansion project.

## Progress — 2026-07-23

### Completed host milestone (`092a3e0`)

- Added the decision-complete task spec on fresh branch `release/0.2.0-narrow-support`, based on
  updated `main` at `a901410`. External delegation was rejected because the checkout contains a
  private untracked maintainer handoff; local execution follows the Dispatch fallback.
- Rewrote public onboarding around the configured Gradle-plugin/profile path. README now contains
  the capability tiers, an exhaustive CLI-help-derived reference with safe recovery for every
  command and option, experimental zero-touch/live-literal boundaries, and a link to the public
  AI guide. Configuration, IDE, and root agent documentation use the same contract.
- Added `docs/ai-project-setup.md`, an end-user AI inventory/setup/failure/cleanup playbook with
  the specified copy/paste prompt. It contains no maintainer-only paths or project details.
- Added deterministic offline `e2e/check-docs-contract.sh` and
  `e2e/check-release-version-contract.sh`, wired into the lightweight contract workflow.
- Aligned the Gradle plugin, runtime publication, injected runtime coordinate, and IntelliJ plugin
  at 0.2.0. The Gradle plugin publication group now matches the documented public coordinate,
  fixing a Maven Local scratch-consumer failure discovered during this work.
- Added `scripts/verify-release-artifacts.sh <version> <mavenLocal|repository-url>`. It creates a
  temporary consumer and resolves the plugin marker, plugin module, and runtime AAR without
  composite substitution.

### Verified

- PASS: documentation/version contracts, `git diff --check`, and staged privacy scans.
- PASS: `:engine:test :protocol:test :cli:installDist :cli:verifyZeroTouchDistribution`.
- PASS: Gradle-plugin and runtime-client Maven Local publications plus
  `scripts/verify-release-artifacts.sh 0.2.0 mavenLocal`.
- PASS: IntelliJ plugin `test --rerun-tasks buildPlugin verifyPlugin`; only the previously known
  verifier/deprecation warnings remain.
- PASS: configured packaged-artifact smoke on the API-36 emulator for AGP 9/JDK 21 and AGP 8/JDK
  17. Both lanes resolve only Maven Local 0.2.0 coordinates, pin an explicit profile, and prove
  Doctor, Ready, a visible edit and reversal, stable PID, and Stop (`ba3b5a2`). The Gradle plugin
  publication now declares and emits JVM 17 compatibility for the supported target-JDK-17 lane.

### Completed configured-device milestone (`b890922`)

- PASS: the configured single-module suite completed all standard cases on one API-36 emulator:
  configured `start`, body and state-preserving leaf edits, structural/new-class/interpreter paths,
  resources including bitmaps, and signature-change recovery. Live literals remained intentionally
  skipped because the suite was not rebuilt with its experimental compiler mode.
- PASS: the configured multi-module suite completed all 4 cases twice. It now verifies that a
  capture-heavy `:feature` composable keeps `Feature count: 1` across its own body edit, renders
  `FeatureX:`, uses targeted group invalidation, keeps one PID, and restores sources and watcher
  state after each run.
- PASS: configured capture completed sequential watched-library edits, a structural helper
  addition, and its interpreter-backed removal with callback behavior and one PID intact.
- T42 found and fixed a real classifier precision bug: Compose compiler source-location operands
  changed enclosing lambda bytes for a leaf edit, so the engine invalidated a parent group and
  reset its remembered state. `FactsExtractor` now excludes only those debug operands from method
  hashes; focused host coverage still detects real rendered-literal changes.

### Completed clean-clone documentation gate

- Added `e2e/run-clean-clone-configured.sh`. It creates a fresh local clone without `.agents`,
  copies the public single-module sample into an independent target repository, removes its local
  composites, and applies the documented configured-plugin setup against the already verified
  Maven Local 0.2.0 publications. Maven Local is the necessary pre-publication stand-in for the
  README's JitPack repository; the separate artifact resolver proves the same coordinates.
- PASS: the isolated clone built its own CLI distribution and the target pinned an explicit profile,
  completed matching prepare then Doctor, reached watcher readiness, preserved its PID across a
  visible body edit and reversal, stopped the watcher, and restored every tracked target source.
- The gate exposed and corrected the public ordering error: Doctor validates an installed runtime,
  so the stable flow is `prepare`, then `doctor`, then `watch`. README, AI, configuration, IDE,
  and root agent guidance now agree.

### Completed maintainer production-target configured smoke

- PASS: a clean public multi-module Compose target completed the stable configured path against
  Maven Local 0.2.0 artifacts: explicit `:app` plus watched `:feature:foryou:impl` modules,
  `demoDebug`, a pinned JBR 21 target Gradle home, matching prepare, Doctor runtime handshake,
  watcher readiness, app-body and watched-library body edits and reversions, stable PID, Stop,
  and source/device/profile restoration.
- The target is deliberately outside the CI-tested lanes (AGP 9.0.0, Kotlin 2.3.0, Compose BOM
  2025.09.01), so this is evidence that the stable contract can adapt to an unlisted existing
  project, not a new universal compatibility claim. No target versions or unrelated build logic
  were changed.

### Release actions — 2026-07-23

- **Merged release commit:** PR #29 merged as `7f3399b46ede24241c394e95fe289980126f6f16` after
  contract, e2e, repro, and compatibility checks succeeded. Annotated tag `0.2.0` targets that
  exact commit.
- **GitHub Release:** [0.2.0](https://github.com/xception-hash/compose-hot-reload/releases/tag/0.2.0)
  is public with `cli.zip` and `hotreload-intellij-plugin-0.2.0-signed.zip`. Its published SHA-256
  digests match the exact-commit locally built assets.
- **JitPack:** its build completed from `7f3399b` and listed the real marker POM, plugin module,
  and runtime AAR. JitPack republishes the marker under
  `com.github.xception-hash.compose-hot-reload`, while Maven Local retains its conventional
  `dev.hotreload` group. The resolver now proves the repository-appropriate marker plus the direct
  plugin module and runtime AAR; both Maven Local and
  `scripts/verify-release-artifacts.sh 0.2.0 https://jitpack.io` pass.
- **Marketplace:** signed update `1114979` for 0.2.0 was approved. The official 0.2.0 download
  contains the reviewed descriptor and its plugin JAR matches the installed 0.2.0 bundle byte for
  byte.
- **Post-publication Marketplace smoke PASS:** an isolated checkout of a public multi-module
  Compose target resolved 0.2.0 through JitPack, prepared and passed Doctor on one API-36 device,
  reached watcher readiness, visibly applied and reverted a body edit with one stable app process,
  then stopped. The temporary target checkout and profile were isolated from the maintainer's
  working project.

### Findings and operational gotchas

- Android 16 rejects the historical `adb shell monkey` launcher fallback. The sample suites use
  their explicit `MainActivity` components, and the configured `start` suite supplies the supported
  `--launch-activity .MainActivity` override because prepare reinstalls and must relaunch the app.
- A watcher is a blocking daemon. Start only one per device, wait for `watching …`, and let the
  suite cleanup stop it; do not infer success from a surviving process or elapsed time.
- The API-36 device gate used Android Studio JBR 21 and build-tools 36.0.0. Clearing a pre-set
  target JDK before sourcing `scripts/env.sh` is necessary for this checkout's CLI toolchain.
- Before publication, the clean-clone gate resolves the locally published release artifacts rather
  than JitPack; public JitPack resolution remains a required post-publication check.
- A flavor-suffixed application ID may require a fully qualified launch activity from the manifest,
  rather than the default `.MainActivity` shorthand. Pin that value before prepare so it is part of
  the matching profile baseline.

### Completion

All release-blocking actions are complete: the Marketplace update was approved, its official
0.2.0 archive was verified, and the configured production smoke passed from the Marketplace-
bundled CLI.

## Goal

Ship a trustworthy 0.2.0 release with one release-blocking onboarding path: explicit configured
integration through the `dev.hotreload` Gradle plugin, an explicit reviewed project profile, a
matching CLI-owned prepare/install, and `doctor` before watching. Keep current convenience and
experimental capabilities available, document their failure boundaries honestly, and give end
users a public AI-agent playbook that can inspect and adapt their target project when setup or a
reload fails.

Success means a new user—or their AI coding agent—can determine the supported path, configure a
project without guessing, understand every CLI command and option, recover from known failures,
and identify when the project is outside the tested support envelope. Success does not mean that
every Gradle/AGP/Kotlin/Compose combination is automatically supported.

## Fixed product contract

Do not reopen these decisions during implementation. A proposed change requires coordinator and
maintainer approval before code or documentation is changed.

### 1. Stable release-blocking path

The only stable onboarding path claimed by 0.2.0 is **configured mode**:

1. Apply `dev.hotreload` explicitly to the selected Android application module and every watched
   Android-library/Kotlin-JVM module.
2. Use `hotreload configure`/`inspect` only to produce an initial suggestion. Review it, then pin
   the app module, application ID, variant, watched modules, module directories/variants, target
   JDK, Gradle arguments, and device in an explicit profile.
3. Use matching `hotreload prepare --profile <name>` and `hotreload doctor --profile <name>` (or
   `start`) so the CLI owns and validates the installed APK baseline before watching.
4. Watch with the exact same profile. Do not mix an Android Studio/ordinary `installDebug` APK
   with a profile and describe that as the supported path.
5. Require one API-30+ device/emulator and a debuggable variant. The runtime must remain absent
   from every non-debuggable variant.

The Gradle plugin remains. Do not replace it with copied snippets as the primary path: it
centralizes runtime dependency wiring, debug/release safety, JNI packaging, deterministic Kotlin
class-shape flags, Compose FunctionKeyMeta generation, live-literal instrumentation, and coverage
handling. A manual Gradle recipe may be documented as an unsupported escape hatch for an AI agent
to adapt, but it must repeat the release-safety warning and must not become a second tested product
contract.

### 2. Capability tiers

Use these exact labels consistently in README, configuration docs, IDE docs, release notes, and
the AI guide:

- **Stable:** configured Gradle-plugin integration; explicit app/module/variant configuration;
  profiles; `doctor`; `prepare`; `start`; `watch`; `config show`; `--project-java-home`;
  `--device`; `--launch-activity`; explicit module-directory and module-variant mappings.
- **Setup helper:** `inspect`, `configure` discovery defaults, implicit automatic discovery, and
  discovery include/exclude filters. These are supported conveniences on tested layouts, but the
  recovery contract is to inspect the result and provide explicit values—not to add endless
  discovery heuristics.
- **Advanced:** `--gradle-arg`, `--sdk`, `--build-tools`, and non-default variants. They are valid
  supported inputs when pinned identically in prepare/watch, but they can change the build or APK
  fingerprint and therefore require deliberate use.
- **Experimental:** `--zero-touch` and `--literals`. Preserve them and their tests, but do not lead
  Quickstart with them and do not let new zero-touch/live-literal compatibility work block 0.2.0.
- **Diagnostic escape hatch:** `--ignore-fingerprint`. Never recommend it as normal setup or the
  first recovery action. Explain that it can permit an APK/class baseline mismatch and Compose
  state corruption; the safe recovery is a matching `prepare`.
- **Machine-readable:** `inspect --json`; stable schema-v1 output for tools/agents, not a separate
  integration mode.

### 3. Tested support envelope

Describe these as **tested lanes**, not universal version claims:

- AGP 8.12.x, standalone Kotlin 2.3.x, target Gradle on a full JDK 17.
- AGP 9.2.x, built-in Kotlin/Kotlin 2.4.x, target Gradle on JDK/JBR 21.
- JDK vendor is not part of the compatibility contract. Azul, Temurin, and other conforming full
  JDKs are acceptable when their major version matches the target lane and `bin/java` plus
  `bin/javac` exist.
- The CLI itself uses Android Studio's JBR 21 in the documented source/IDE workflows. Explain that
  `--project-java-home` controls the target Gradle daemon independently.
- Kotlin DSL is tested. Groovy scripts, multiple flavor dimensions, included-build application
  modules, multiple concurrent devices, and unlisted toolchain combinations are best effort.
- API 30+, debuggable builds, one selected device, and the existing documented edit limitations
  remain hard boundaries.

Do not add new hard version rejection code in T41. Documentation must distinguish “tested” from
“rejected.” If the target project already builds with a different combination, the AI guide may
try the stable configured contract, but it must report that the combination is outside CI coverage.

## Implementation milestones

Commit each milestone separately after coordinator review. Do not publish, tag, or push from a
delegated worker.

### Milestone A — Rewrite README around the stable path

Restructure `README.md` without removing the technical feature/limitation information:

1. Lead with a short “Supported path” statement and a compatibility-tier legend.
2. Make configured mode the first and only stable Quickstart. The shortest successful path must
   cover:
   - CLI JBR versus target-project JDK;
   - JitPack plugin resolution;
   - applying `dev.hotreload` to the app and every watched code module;
   - `configure` with explicit profile review;
   - matching `prepare`, `doctor`, then `watch`/`start`;
   - readiness and one body-edit success signal;
   - Stop/cleanup.
3. Move zero-touch out of Quickstart into an “Experimental zero-touch evaluation” section. State
   its known risk areas: init scripts/composite builds, reflective AGP/Kotlin lifecycle handling,
   exact prepare/watch parity, included-build boundaries, and its non-release-blocking status.
4. Keep live literals in an experimental section and state the exact safe rule: the same profile
   and `--literals` choice must build/install/watch the APK; a mismatch can corrupt the Compose
   slot table.
5. Add a concise “Use an AI agent” section linking `AGENTS.md` and the new public AI guide. Include
   the copy/paste prompt specified under Milestone C.
6. Replace stale or duplicate roadmap text. The README must not claim T33 is still pending, call
   0.1.6 the current release after 0.2.0 ships, or imply that Marketplace and JitPack artifacts
   have different current feature sets.
7. Preserve the feature matrix, state-reset semantics, limitations, security/debuggable boundary,
   and AOSP attribution links.

### Milestone B — Document every current command and option

Add one complete README reference table derived from the `USAGE` constant in
`cli/src/main/kotlin/dev/hotreload/cli/Main.kt`. It must cover every command/help entry point:

- `help` / `--help`
- `watch`
- `prepare`
- `start`
- `doctor`
- `inspect`
- `configure`
- `config show`

It must cover every current option:

- `--project`
- `--profile`
- `--save-as`
- `--app-id`
- `--app-module`
- `--app-module-dir`
- `--build-tools`
- `--device`
- `--exclude-module`
- `--gradle-arg`
- `--ignore-fingerprint`
- `--include-module`
- `--json`
- `--launch-activity`
- `--literals`
- `--module`
- `--module-variant`
- `--project-java-home`
- `--sdk`
- `--variant`
- `--zero-touch`

Each row must name: applicable command(s), capability tier, what it changes, when a user needs it,
whether prepare/watch must match, common failure, and safe recovery. Do not hide dangerous options
in prose. Use the exact CLI spelling and do not document options that do not exist.

Add `e2e/check-docs-contract.sh`, or extend an existing docs contract script if that produces a
smaller coherent change. It must fail when any command/option listed by CLI help is absent from
the README reference. Keep the check deterministic, offline, and shell-platform compatible with
the existing Ubuntu CI environment. Wire it into an existing host CI job; do not add a new costly
job.

Update `docs/project-configuration.md` to carry the details that would overwhelm Quickstart:

- stable explicit-profile example;
- tested support lanes versus best-effort combinations;
- CLI JBR/target JDK separation, including Azul JDK 17;
- module/variant/physical-directory examples;
- exact fingerprint/parity rules;
- experimental zero-touch and live-literal boundaries;
- recovery from discovery that selects the wrong app, module closure, variant, or resource owner.

Update `intellij-plugin/README.md` and `docs/ide-plugin-settings.md` so UI labels and guidance use
the same capability tiers and recommend an explicit profile/configured path. The IDE plugin may
continue exposing current settings; this milestone changes their documentation, not the settings
schema or UI behavior.

### Milestone C — Public AI-agent setup and recovery guide

Create `docs/ai-project-setup.md`. It is public end-user guidance, not a maintainer handoff. It
must be self-contained, contain no private paths or private project details, and instruct an AI
coding agent to work in this order:

1. **Read-only inventory:** locate Gradle root/wrapper, AGP/Kotlin/Compose versions, settings and
   repository management, Android app modules, application IDs, debuggable variants, module
   dependency closure, physical module directories, coverage instrumentation, project Gradle JDK,
   Android SDK, devices, and existing hot-reload wiring. Show findings before editing.
2. **Choose the stable lane:** default to configured mode, explicit modules/profile, no literals,
   no zero-touch, and no `--ignore-fingerprint`. If outside the tested lanes, state that fact
   without immediately changing product or target versions.
3. **Propose the target diff:** add JitPack/plugin resolution and apply `dev.hotreload` only to the
   app and watched code modules. Preserve unrelated repositories/plugins and release behavior.
   Never add runtime-client directly to a non-debuggable configuration.
4. **Resolve JDKs:** keep the CLI on JBR 21; pass the target's full JDK explicitly through
   `--project-java-home`. Accept Azul/Temurin vendor differences. Run both `java -version` and
   `javac -version` for the selected target JDK before Gradle.
5. **Pin configuration:** run inspect/configure as a suggestion, review its module/variant/app-ID
   output against the build, correct it explicitly, save a profile, and show `config show`.
6. **Prepare and validate:** run matching `prepare`, then `doctor`, launch, start one background watcher,
   and wait for `watching …` before editing. Reuse AGENTS.md's exact log signals.
7. **One safe proof:** make or ask the user for one reversible composable-body text/style change,
   require `changed:` plus `hot-swapped:`/`interpreted:`/`literal-pushed:`, verify stable PID when
   possible, then restore the source and confirm the restoration also applies.
8. **Failure ladder:** classify before changing anything:
   - Java/Gradle failure → correct `--project-java-home`/Gradle arguments; do not replace the
     project's toolchain casually.
   - discovery wrong/missing → specify app/module/variant/directory values explicitly.
   - watched library/resource missing → add its owning reachable module and run matching prepare.
   - coverage/class-shape mismatch → use the plugin-managed hot-reload build and matching prepare;
     do not patch around ART errors blindly.
   - protocol/fingerprint/integration/literals mismatch → stop, matching prepare/reinstall, relaunch,
     restart; never lead with `--ignore-fingerprint`.
   - compile or recomposition error → fix target source and save again; do not restart a healthy
     watcher.
   - rebuild-required edit → perform the documented full install/prepare and restart once.
   - zero watcher event → classify unsupported extension/module scope; use a full reinstall when
     appropriate.
   - suspected product bug → preserve sanitized doctor/watcher output, exact versions/options, and
     minimal reproduction; do not modify compose-hot-reload internals as the first response.
9. **Cleanup/report:** stop the watcher, verify no duplicate process, list target files changed,
   leave user-owned changes intact, and explain how to revert the integration if requested.

Include this copy/paste prompt, adjusted only for clarity:

```text
Read AGENTS.md and docs/ai-project-setup.md from the Compose Hot Reload repository. Configure this
Android project using the stable configured-plugin path. First inspect and report the Gradle/AGP/
Kotlin/JDK/app-module/variant/module graph without editing. Then propose the smallest target-project
diff. Do not use zero-touch, live literals, or --ignore-fingerprint unless I explicitly opt in.
After I accept the target diff, run matching prepare, doctor, and one background watcher; wait for
the `watching` readiness line before a reversible test edit. Classify failures using the documented
log contract and adapt this target project before proposing changes to Compose Hot Reload itself.
```

Update root `AGENTS.md` so it leads with the stable configured setup and links this guide. Preserve
its machine-readable readiness/failure contract and watcher background rules. Re-label zero-touch
instructions as opt-in experimental recovery, not the default for an unconfigured project.

### Milestone D — Align every published component at 0.2.0

Use the tag/version string `0.2.0` with no `v` prefix. Align all user-visible/published versions:

- `gradle-plugin` project version;
- runtime-client project/publication version;
- runtime coordinate injected by `HotReloadPlugin`;
- IntelliJ plugin version and change notes;
- README/configuration examples;
- JitPack coordinates;
- release metadata and artifact filenames that contain a version.

Do not hand-maintain two different “current release” numbers. Add or extend a deterministic
offline version-contract check that fails if the Gradle plugin, runtime AAR, injected coordinate,
IDE plugin, and current README setup version disagree.

Build and publish both Maven artifacts to `mavenLocal`, then resolve them from a clean scratch
Gradle consumer without composite substitution. Add a reusable script under `scripts/` for the
resolution check because it is required again after the public JitPack build. The script must take
an explicit version and repository URL/mode, use a temporary directory, avoid altering the user's
project, and verify both the plugin marker/module and runtime AAR coordinates. It must not trust a
green JitPack status alone.

The source CLI distribution and the IDE-bundled CLI must be built from this same release commit.
Keep zero-touch payload packaging intact even though zero-touch is experimental.

### Milestone E — Stable-path release gates

No new compatibility features may be added to make a gate pass. A newly found product defect gets
a separate task and blocks only if it violates the fixed stable contract.

Required release-candidate evidence, using built artifacts rather than source-level assumptions:

1. Existing configured single-module and multi-module e2e suites pass.
2. Configured-capture regression passes, including sequential watched-library edits, structural
   addition/reversion, callback behavior, and stable PID.
3. The AGP-8/JDK-17 lane is exercised in configured mode with locally published 0.2.0 artifacts,
   explicit module/variant/JDK configuration, and at minimum prepare/Doctor/Start, body edit,
   reversal, stable PID, and Stop. Reuse/copy the existing fixture rather than creating another
   broad compatibility framework. The gate must use a full JDK 17; vendor is not asserted.
4. The AGP-9/JDK-21 configured lane passes the same minimal packaged-artifact smoke.
5. One clean public production-grade project passes the stable configured path using only the
   0.2.0 README/AI guide: explicit setup, prepare, Doctor, body edit/reversal in app and watched
   library, stable PID, and Stop. This is a maintainer gate; sanitize all recorded findings.
6. Clean-clone documentation gate: on a fresh checkout and clean target copy, a person/agent
   following README plus the AI guide reaches Ready and completes one reversible body edit without
   relying on `.agents/`, maintainer scripts, local composites, or undocumented knowledge.

Experimental zero-touch and live-literal regressions should remain green before the release
commit because the code is unchanged, but failures discovered only in new unclaimed project
shapes do not expand T41. Record their limitation and open a separate task if appropriate.

### Milestone F — Maintainer-only publication and post-publication proof

The delegated worker and coordinator prepare artifacts and release notes but do not publish. After
all prior gates pass, the maintainer explicitly authorizes each external mutation:

1. Merge through a reviewed PR with required checks green; never push to `main`.
2. Tag the merged release commit `0.2.0` and create a GitHub Release.
3. Attach the signed IntelliJ plugin ZIP and any documented CLI distribution artifact.
4. Trigger/verify JitPack. Inspect the build log's final file list, require every expected module,
   fetch the real artifact URLs, and run the reusable scratch-resolution script against JitPack.
5. Sign and submit IntelliJ plugin 0.2.0 to JetBrains Marketplace using the existing secure local
   credential workflow; never print credentials.
6. After Marketplace approval, install the Marketplace artifact and repeat the minimal configured
   production smoke. A source/local ZIP result does not prove the published bundle.
7. Update public release status only after the real artifacts resolve and the Marketplace bundle
   passes. Record exact release URLs and sanitized evidence.

Release notes must lead with the narrowed stable contract, list zero-touch/live literals as
experimental, state tested toolchain lanes, link the AI guide, and summarize the important fixes
since 0.1.6/Marketplace 0.1.8 without claiming universal project compatibility.

## Out of scope

- Removing zero-touch, discovery, profiles, live literals, interpreter, or IDE features.
- New Compose N-1 shims, suspend-lambda proxies, flavor-dimension discovery, included-build module
  instrumentation, multiple-device orchestration, or additional AGP/Kotlin/Compose lanes.
- Refactoring core engine/runtime/classifier/protocol behavior merely to simplify internals.
- Fixing T36 notification cosmetics or PatchServer timeout re-arming unless separately approved.
- Migrating from JitPack to Maven Central/Gradle Plugin Portal.
- Adding telemetry, auto-editing target projects from the CLI, or allowing runtime-client in
  release/non-debuggable variants.
- Weakening fingerprint, protocol, debuggable, socket, path, or release-variant security checks.
- Publishing, tagging, pushing, or opening/merging a PR without the maintainer's explicit approval.

## Acceptance

The coordinator runs all commands from the repository root on the release-candidate diff. Exact
task names may be corrected only if the repository's existing wrapper exposes a renamed equivalent;
record any correction in this task before claiming acceptance.

### Documentation and host gates

```bash
git diff --check
bash e2e/check-docs-contract.sh
unset JAVA_HOME
source scripts/env.sh
"$JAVA_HOME/bin/java" -version
./gradlew :engine:test :protocol:test :cli:installDist :cli:verifyZeroTouchDistribution
./gradlew -p gradle-plugin clean test publishToMavenLocal
./gradlew -p runtime-client clean publishToMavenLocal
scripts/verify-release-artifacts.sh 0.2.0 mavenLocal
(cd intellij-plugin && unset JAVA_HOME && source ../scripts/env.sh && \
  ./gradlew test --rerun-tasks buildPlugin verifyPlugin)
```

Requirements:

- CLI help and README command/option inventory match exactly.
- The version-contract check passes with no stale current-version coordinates.
- Plugin Verifier reports Compatible for every pinned IDE and zero internal-API use; only already
  documented deprecations may remain.
- Maven scratch resolution proves both 0.2.0 artifacts without composite substitution.
- Documentation contains no private paths/names and no claim that experimental/best-effort paths
  are stable.

### Device/configured gates

Run the existing preflight from root before any Gradle/device suite:

```bash
unset JAVA_HOME
source scripts/env.sh
"$JAVA_HOME/bin/java" -version 2>&1 | head -1
ls "$ANDROID_HOME/build-tools/36.0.0/d8"
ls "$ANDROID_HOME/build-tools/36.0.0/dexdump"
adb devices
adb shell getprop ro.build.version.sdk
```

Then require:

```bash
./e2e/run.sh
./e2e/run-multi.sh
./e2e/run-configured-capture.sh
```

Add and run one bounded configured packaged-artifact gate for AGP 8/JDK 17 and one for AGP 9/JDK
21 as specified in Milestone E. Each must use locally published 0.2.0 artifacts rather than
composite substitution. The coordinator reviews the scripts before execution and requires exact
machine-checkable Ready/edit/reversal/PID/Stop signals.

### Privacy, security, and release gates

Before every commit, PR, tag, or publication:

```bash
git diff --check
```

Run both standing staged-content privacy scans from `.agents/README.md`; they must return no
tracked-content matches. Do not copy the private scan terms into tracked files. Review runtime
dependency coordinates and require the existing non-debuggable release tripwire/security tests to
remain intact.

After the maintainer publishes 0.2.0, require:

```bash
scripts/verify-release-artifacts.sh 0.2.0 https://jitpack.io
```

The GitHub release, JitPack artifacts, and Marketplace update must all identify the same 0.2.0
release commit. Install the Marketplace artifact and complete the post-publication configured
production smoke before changing T41 to DONE.

## Completion record

When all gates pass, append an Outcome section containing:

- merge commit, tag, PR, GitHub Release, JitPack, and Marketplace URLs;
- exact tested AGP/Kotlin/JDK/device lanes;
- host/device/clean-clone/production acceptance evidence;
- final stable/experimental contract;
- any newly documented best-effort limitations;
- confirmation that target test projects and watcher/device state were restored cleanly.

## Outcome — 2026-07-23

- **Release provenance:** [PR #29](https://github.com/xception-hash/compose-hot-reload/pull/29)
  merged as `7f3399b`; annotated tag `0.2.0`, the
  [GitHub Release](https://github.com/xception-hash/compose-hot-reload/releases/tag/0.2.0),
  [JitPack](https://jitpack.io/#xception-hash/compose-hot-reload/0.2.0), and the
  [JetBrains Marketplace plugin](https://plugins.jetbrains.com/plugin/32850-compose-hot-reload)
  are published.
- **Artifact proof:** the official Marketplace 0.2.0 download contained the reviewed descriptor;
  its plugin JAR matched the installed 0.2.0 JAR byte for byte.
- **Post-publication smoke:** the Marketplace bundle configured an isolated public multi-module
  target with an explicit app/library profile, completed matching Prepare and Doctor, reached the
  `watching …` readiness gate, visibly applied and reverted a body edit, preserved one app PID,
  and stopped the watcher. It ran on API 36 with JBR 21; the target uses AGP 9.0.0, Kotlin 2.3.0,
  and Compose BOM 2025.09.01, which remains best-effort production evidence outside the tested
  lanes.
- **Contract:** configured Gradle-plugin integration with reviewed explicit profiles remains the
  stable path. Zero-touch and live literals remain experimental.
- **Cleanup:** the smoke used a disposable checkout and temporary profile, leaving the maintainer's
  working target unmodified. The watcher stopped cleanly after the reversal and the temporary app
  was uninstalled from the emulator.
