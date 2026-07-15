# T33: Project-agnostic hot reload — generalization roadmap
Status: TODO
Assignee: agy (phases 1–6), maintainer (phases 7–10)

## Goal
Make Compose Hot Reload work with any Android/Compose project without project-specific
code or hardcoded paths in the repository. All project differences — target JDK,
application module, physical module directory, application ID, build variant, library
variants, Gradle arguments, and Android tooling — are supplied through CLI options,
Gradle-based discovery, or an external configuration profile.

The tool supports two integration modes:

1. **Configured mode:** the target project applies the `dev.hotreload` Gradle plugin;
   every project-specific value is selected externally via CLI or profile.
2. **Zero-touch mode:** a generated Gradle init script applies a bundled bootstrap plugin
   and runtime AAR without modifying the target project's settings or build files.

Case-study reference point: a large layered app (Gradle 8.13, AGP 8.13.2, Kotlin 2.3.21,
JDK 17, custom variants including flavored products, Gradle paths ≠ physical dirs,
minSdk 24).

## Spec

### Configuration model

Create a typed project configuration model shared by the CLI, doctor, watcher, and IDE
plugin. Fields:

- Target project root.
- Gradle wrapper path and extra Gradle arguments.
- CLI JDK and target-project Gradle JDK (separate values).
- Android SDK, build-tools, adb, d8, and device serial.
- Application Gradle path (e.g. `:mega-app`) and physical directory
  (e.g. `layers/mega-app`).
- Application ID (e.g. `com.example.app.debug`) and optional launch activity.
- Application variant (e.g. `stageDebug`, `vendorProductionDebug`).
- Watched module Gradle paths and physical directories.
- Per-module variant overrides.
- Per-module source, resource, and class-output directories.
- Compile, assemble, and install tasks.
- Runtime integration mode: configured or zero-touch.
- Live-literals setting.

Resolution precedence:

1. Explicit CLI option.
2. External project profile.
3. Gradle/Android project discovery.
4. Safe conventional default.
5. Interactive selection or a clear error when still ambiguous.

### CLI command surface

```text
hotreload inspect
hotreload configure
hotreload doctor
hotreload prepare
hotreload watch
hotreload start
```

- `inspect` — show discovered apps, variants, modules, JDK, tasks, and outputs.
- `configure` — resolve choices and optionally save an external profile.
- `doctor` — validate the fully resolved configuration.
- `prepare` — build, install, and launch an instrumented APK.
- `watch` — watch an already prepared/running app.
- `start` — run doctor, prepare when necessary, then watch.

All commands accept the same core project-selection options:

```text
hotreload start \
  --project /path/to/project \
  --app-module :mega-app \
  --app-module-dir layers/mega-app \
  --app-id com.example.app.debug \
  --variant stageDebug \
  --project-java-home /path/to/jdk-17 \
  --module :mega-app=layers/mega-app \
  --module :feature=features/feature \
  --module-variant :feature=vendorProductionDebug \
  --gradle-arg -PdevelopmentMode=true \
  --gradle-arg --parallel \
  --device emulator-5554
```

### External project profiles

Support a configuration file outside the target repository:

```text
~/.config/compose-hot-reload/projects/<name>.toml
```

Selectable with `hotreload start --profile <name>`. Profiles contain only
project-specific configuration and never require changes to Compose Hot Reload source.

Commands to create and inspect profiles:

```text
hotreload configure --project /path/to/project --save-as myapp
hotreload config show --profile myapp
```

### Gradle project discovery

A Gradle-side discovery component reports machine-readable metadata. It discovers:

- Included Gradle projects and their physical directories.
- Android application and library modules.
- Kotlin/JVM modules.
- Available debuggable application variants.
- Application IDs.
- Variant compile, assemble, install, and resource-processing tasks.
- Source and resource directories.
- Kotlin class-output directories from task outputs.
- Project dependencies reachable from the selected application variant.
- The actual library variant selected for each dependency.
- APK locations from AGP built artifacts or `output-metadata.json`.

Uses task inputs, outputs, and dependency graphs instead of hardcoded AGP 8/9 directory
conventions. Current directory probes retained as compatibility fallbacks.

### JDK handling

- CLI JVM is independent from the target Gradle JVM.
- Target project runs with `--project-java-home`.
- Auto-detect target JDK from: explicit config → IDE Gradle settings →
  `org.gradle.java.home` → environment managers → current environment.
- Print both JVMs in doctor output.
- Never require editing Compose Hot Reload to support another JDK vendor.
- Validate Gradle/JDK compatibility before starting.
- Azul, JetBrains Runtime, Temurin, Oracle distributions treated uniformly.

### Module and variant selection

- Dedicated `--app-module` option (not just the first watched module).
- `GradlePath=physical/directory` mappings as explicit overrides
  (e.g. `--module :mega-app=layers/mega-app`).
- Repeatable `--module` options; preserve comma-separated compatibility.
- Discover watched modules from the selected app variant dependency graph.
- Include/exclude module filtering.
- Resolve each module's actual selected variant automatically.
- `--module-variant` overrides for unusual builds.
- Display resolved module table before building and in `hotreload inspect`.

### Build and APK handling

- Generate compile/assemble/install task names from discovered variant metadata.
- Run every Gradle operation with the configured target JDK and Gradle arguments.
- Obtain APK paths from AGP metadata, not guessed directory names.
- Support flavored and custom build-type output layouts.
- Record a build fingerprint (variant, modules, compiler flags, runtime version, JDK,
  Gradle arguments).
- Refuse to watch when the installed APK does not match the resolved configuration.

### Configured mode improvements

The gradle-plugin's `debugImplementation` wiring misses custom debuggable build types:
custom-named debuggable variants (e.g. `stageDebug`) get no runtime-client dependency.
Fix: runtime dependency configuration follows the selected debuggable variant, not only
`debugImplementation`.

Additional configured-mode requirements:

- Handle AGP application, AGP library, Kotlin/JVM, standalone KGP, and built-in Kotlin
  modules consistently (Layout `AGP_BUILT_IN | AGP_KGP | JVM` already landed).
- Runtime artifact version not hardcoded independently from the tool version.
- Doctor verifies resolved runtime and compiler flags through Gradle metadata, not
  text-searching build files.

### Variant→source-set resolution

The current variant→source-set splitting assumes a single flavor dimension ending in
`debug`/`release`. Proper fix: Gradle-metadata discovery of the full variant→source-set
mapping, supporting multi-dimension flavored variants
(e.g. `vendorProductionDebug`).

### Zero-touch mode

Bundle a bootstrap Gradle plugin and runtime AAR with the CLI. Generate an init script
that:

- Loads the bootstrap plugin by implementation class.
- Applies it to the selected app and watched modules.
- Adds the bundled runtime AAR as a local file dependency.
- Adds required compiler flags.
- Configures legacy JNI packaging for the selected app variant.
- Rejects non-debuggable variants.

Pass the init script to every build: discovery, initial build, install, incremental
compile, and resource assembly. No `includeBuild`, repository entry, plugin declaration,
or dependency is written into the target project.

### IDE plugin integration

- Reuse the same discovery and configuration model as the CLI.
- Replace the single free-form extra-arguments field with structured controls for app
  module, variant, target JDK, modules, device, and Gradle arguments.
- Offer discovered values through dropdowns.
- Keep advanced raw overrides available.
- Store user choices in IDE configuration or the external profile, not in target source
  files.
- Show the exact resolved CLI command for reproducibility.

### Compatibility-fixture matrix

Create fixture projects and CI tests covering:

- AGP 8 with standalone Kotlin + flavored variants (sample for CI is part of this task).
- AGP 9 with built-in Kotlin.
- JBR 21 CLI with target builds on JDK 17 and JDK 21.
- Single-module and multi-module projects.
- Gradle paths that differ from physical directories.
- Product flavors and custom build types.
- Different app and library variant names.
- Kotlin/JVM dependency modules.
- Multiple application modules and multiple devices.
- Custom APK output names and directories.
- Configured and zero-touch integration modes.
- Groovy and Kotlin Gradle scripts.

## Delivery phases

### Phase 1 — Consolidate options into a shared config model
**Partially done** by the `feat/project-portability` PR: CLI flags `--variant`,
`--project-java-home`, `--gradle-arg`, `--module :path=dir`, `--module-variant`, plus
`ModuleSpec.Request` / `applyVariantOverrides()` and Layout `AGP_BUILT_IN | AGP_KGP | JVM`
already landed.

Remaining: formalize the typed `ProjectConfig` model, wire precedence chain, accept
`--app-module` and `--app-module-dir`.

Assignee: agy — **DONE (`tasks/T33a-config-model.md`, merged in PR #11)**

### Phase 2 — `inspect` and Gradle metadata discovery
Add the Gradle-side discovery component and `hotreload inspect` command. Report
machine-readable metadata (modules, variants, tasks, outputs, dependency graph).

Assignee: agy — **DONE (`tasks/T33c-inspect-discovery.md`, merged in PR #12)**
(design: init-script task over the Tooling API, schema v1, gson 2.11.0)

### Phase 3 — Explicit app-module, device, include/exclude-module, and launch options
Replace assumptions that the app is `:app`, lives under `app/`, or uses `debug`.
Dedicated `--app-module`, `--device`, module include/exclude, `--launch-activity`.

Assignee: agy — **DONE (`tasks/T33d-device-modules-launch.md`, PR #14)**
(design fixed: discovery-defaulted modules/appId reusing T33c's GradleDiscovery, with a
trigger rule that keeps e2e's explicit `--app-id`/`--module` invocations off the
discovery path; auto-launch via pidof gate + am/monkey; serial-aware Adb)

### Phase 4 — External project profiles and `configure`
Support `~/.config/compose-hot-reload/projects/<name>.toml` profiles.
`hotreload configure --save-as` and `hotreload config show --profile`.

Assignee: agy — **DONE (`tasks/T33e-profiles-configure.md`, merged in PR #15)**
(design: hand-rolled strict-subset TOML in the engine — no TOML lib exists in the
pinned offline caches; profiles persist the RESOLVED plan, include/exclude consumed at
configure time; merge at the flag-string level so CLI > profile > discovery > default
rides the existing parse path; configure-written profiles pin modules+appId and never
re-trigger discovery)

### Phase 5 — Replace output-directory guesses with Gradle task/output metadata
Obtain compile output dirs, APK paths, and resource directories from Gradle task
inputs/outputs and AGP built-artifacts API rather than hardcoded directory conventions.

Assignee: agy — **DONE (`tasks/T33f-output-metadata.md`, merged in PR #16)**
(design fixed: schema v1 already carries the metadata — T33f consumes it host-side;
per-field metadata-first/convention-fallback in ModuleSpec via a `ModuleMetadata`
map on ProjectConfig; profiles get a machine-managed `<name>.discovery.json`
sidecar written by `configure` — the TOML schema is untouched; the stale-APK
heuristic is fixed always-on by parsing AGP's `output-metadata.json` with
variant/applicationId checks before any newest-mtime fallback)

### Phase 6 — `prepare` and `start` orchestration with build fingerprints
End-to-end `hotreload start`: doctor → build → install → launch → watch. Record and
validate build fingerprints.

Assignee: agy — **DONE (`tasks/T33g-prepare-start.md`, PR #17: implemented by the
coordinator, device gate all-PASS 2026-07-15)**
(design fixed: fingerprint = host-side JSON per (device, app) under the config
dir, bound to the device's post-install base.apk sha256; refuse-to-watch ONLY on
positively-known mismatch — unknown provenance warns and proceeds, absent file is
byte-silent (e2e freeze); prepare = fail-fast serial → assemble via the exact
watch GradleCompiler expression (literals mode identical) → ApkLocator →
adb install -r → force-stop → AppLauncher launch → fingerprint; start = doctor
runChecked → prepare when app missing / handshake hard-fails / fingerprint not
clean → the same watch path)

### Phase 7 — Generalize configured-mode Gradle plugin variant handling
Fix `debugImplementation` to follow the selected debuggable variant. Handle all module
layouts consistently. Version-lock runtime artifact to tool version. Doctor validates
via Gradle metadata.

Assignee: maintainer — **core wiring fix DONE (`tasks/T33b-plugin-variant-wiring.md`,
merged in PR #11)**; version-locking + doctor metadata checks remain
maintainer/coordinator work.

### Phase 8 — Zero-touch bootstrap / init-script mode
Bundle bootstrap plugin + runtime AAR. Generate init script. No target-project
modifications.

Assignee: maintainer

### Phase 9 — Update IDE plugin to consume discovery and profiles
Structured controls, dropdown discovery values, profile support, resolved CLI command
display.

Assignee: maintainer

### Phase 10 — Compatibility matrix and generic documentation
Run the full fixture matrix. Convert the case-study guide into a generic advanced
configuration example. AGP 8 + flavored-variant sample for CI.

Assignee: maintainer

## Agent execution guide

How an agent (agy headless via `scripts/delegate.sh`, or any coding agent) picks up T33
work without a human in the loop:

**Order.** Phases 1–7 are DONE (T33a/T33c/T33b merged; T33d in PR #14; T33e in
PR #15; T33f in PR #16; T33g/phase 6 in PR #17). No dispatch-ready spec remains —
phases 8–10 are maintainer-led and need coordinator design sessions before any
delegation.

**Rules (binding, from `docs/WORKFLOW.md` + `AGENTS.md`):**
1. Implement the spec EXACTLY — nothing extra, nothing under "Out of scope". A needed
   design decision = stop and surface it, don't guess.
2. Done = the spec's Acceptance commands pass, run for real, output captured. Never
   weaken, skip, or reinterpret an acceptance check to make it pass.
3. Environment gotchas that have bitten every agent here: source `scripts/env.sh` by
   ABSOLUTE path and `export REPO_ROOT` explicitly BEFORE relying on it (its
   `BASH_SOURCE` derivation breaks under zsh and silently points one directory up);
   a `./gradlew` run without JAVA_HOME can exit 0 having done nothing — verify by output
   or artifact mtime, not exit code alone.
4. Device gates: e2e needs the pinned emulator (`scripts/emulator-up.sh`). Kill watchers
   ONLY with `pkill -9 -f dev.hotreload.cli.MainKt` (killing the Gradle wrapper leaks the
   CLI JVM). Known trap: a killed watcher can wedge the app's accept loop — if a later
   watch start hangs printing NOTHING, force-stop + relaunch the app and retry (see
   docs/security-hardening.md, post-handshake wedge note). If no device is available,
   finish the host-side acceptance and mark the device gate for the coordinator.
5. Confidentiality gate before ANY commit: no employer names, no maintainer personal
   names, no `/Users/<name>` paths may appear in any tracked file. The exact grep
   pattern is deliberately NOT in this public repo — on the maintainer's machine run
   `git grep -iE -f external/confidential-grep.txt -- ':!third_party'` (local-only,
   gitignored file) and require empty output. Never read `external/` except where a
   spec directs, and never commit it.
6. Do not `git commit` unless your spec says to; flip your spec's `Status: TODO` →
   `IN-REVIEW` when acceptance passes and stop. The coordinator reviews, runs device
   gates, and commits.
7. Default `--variant debug` behavior is sacred: any change here must keep e2e
   `run.sh` + `run-multi.sh` green unmodified — those suites ARE the regression contract.

## Out of scope
- JVMTI agent semantics (`hotreload_agent.cpp`), ComposeBridge reflection, classifier
  rules, and protocol message design — configuration/discovery only.
- Hot reload below device API 30 (the runtime already bails loudly there).
- Non-debuggable builds, server-driven UI.

## Acceptance
Each phase lands separately with its own acceptance commands agreed at dispatch time
(design decisions go back to Claude first, per `docs/WORKFLOW.md`). Standing gates for
every phase, run from repo root:
```bash
./e2e/run.sh          # 16 cases — default-variant path stays behavior-identical
./e2e/run-multi.sh    # 4 cases
```
Overall T33 done-condition: the compatibility-fixture matrix above runs in CI (including
the AGP 8 standalone-KGP flavored-variant fixture), and `hotreload start --project <dir>`
works on a fixture project in both configured and zero-touch modes with no
Compose-Hot-Reload source edits.
