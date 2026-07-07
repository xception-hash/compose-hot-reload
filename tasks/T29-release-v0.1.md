# T29: Release v0.1 — docs polish, license audit, versioning, publish
Status: IN-REVIEW
Assignee: agy + jay (Opus reviews)

## Goal
Ship the first public release. The product is functionally complete (T21–T28; e2e 15/15).
This task is packaging + polish only — NO engine/runtime code changes. Anything that looks like
a product bug found along the way goes into a new task file, not fixed inline here.

## Spec

### 1 (agy) — README final pass
- Feature matrix table: edit type → mechanism → latency (source: `docs/PLAN.md` classifier
  matrix + T24/T27/T23 outcomes; live literals ~22 ms, body edits <1 s, interpreter edits ~1 s).
- Quickstart: clean clone → the pinned toolchain (Kotlin 2.4.0, AGP 9.2.1, Gradle 9.6.1,
  Compose BOM 2026.06.01, NDK 28.2.13676358, build-tools 36.0.0, JBR as JAVA_HOME, API 30+
  debuggable) → apply gradle plugin → `hotreload watch` → first edit. Must match what
  `scripts/env.sh` pins — do not invent versions.
- Limitations section, explicitly including (T28 has LANDED — sig changes are NO LONGER a
  limitation; do not write "until T28"): `<clinit>`/constructor edits rebuild; composeKey
  renumber rebuilds; field removal rebuilds; debuggable builds only; min API 30; compiled-callee
  exceptions skip interpreted try/catch (research §5). Note that @Composable signature changes
  now hot-reload via lambda proxies (T28) but may reset the enclosing subtree's remember state
  (parent invalidate) — document this behavior, not as a rebuild fallback.
- IDE plugin section: what it does (spawns CLI, status widget), where the zip comes from (§4).

### 2 (agy, jay reviews) — LICENSE + NOTICE audit
- Root `LICENSE`: confirm present + correct for OUR code (repo is Apache-2.0).
- `NOTICE`: already attributes interp.dex / interpreter_jni.cpp / StubTransform (T27 step 8).
  T28 has merged — extend NOW for T28 artifacts (generated Proxies.java, LambdaGenerator usage) — the
  ported-file inventory = `third_party/PINNED.txt` + every file with an AOSP copyright header:
  `grep -rl "Android Open Source Project" --include=*.kt --include=*.java --include=*.cpp \
   engine/ runtime-client/ scripts/` — every hit must be listed in NOTICE.
- `third_party/` stays out of any published artifact (it is build-time input only) — verify the
  gradle-plugin/AAR artifacts do not bundle it.

### 3 (agy) — version 0.1.0 everywhere
- One `version = "0.1.0"` per published module: gradle-plugin, runtime-client AAR, engine/cli
  if published, `intellij-plugin` (already 0.1.0 — its zip name proves it). Grep for stray
  `0.0.1`/`SNAPSHOT`.

### 4 (agy configures, jay tags/publishes) — publish path: JitPack (DECIDED 2026-07-07)
- **JitPack chosen** (no Sonatype/token friction; Maven Central deferred to v0.2). agy tasks:
  - Add `jitpack.yml` at repo root pinning the JDK to the Android Studio JBR major version
    (JitPack builds on a tag) — see `scripts/env.sh` for the JBR.
  - Ensure each published module (gradle-plugin, runtime-client AAR) applies `maven-publish` with
    a `publishing {}` block JitPack can drive (groupId can stay the JitPack default
    `com.github.xception-hash.compose-hot-reload`; no credentials).
  - README quickstart references the REAL JitPack coordinates:
    repo `maven { url "https://jitpack.io" }` +
    `com.github.xception-hash.compose-hot-reload:<module>:v0.1.0`.
- **Jay-only (do NOT attempt headless):** tag `v0.1.0`, push tag, create the GitHub Release,
  attach `hotreload-intellij-plugin-0.1.0.zip` (built by `cd intellij-plugin && ./gradlew
  buildPlugin`), verify the JitPack build resolves from a scratch project. Marketplace publishing
  is OUT of scope. Release notes = README feature matrix + limitations.

## Out of scope
Engine/runtime/classifier changes; T28; marketplace; Maven Central.

## Review notes (Opus, 2026-07-07) — agy pass reviewed
Fixed during review (verified):
- `runtime-client/lib/build.gradle.kts` — agy added a top-level `publishing` publication using
  `components["release"]` but not the AGP-side `android { publishing { singleVariant("release") } }`,
  so `publishReleasePublicationToMavenLocal` failed ("SoftwareComponent 'release' not found").
  Added the variant; dry-run now BUILD SUCCESSFUL.
- `README.md` quickstart — `dependencyResolution` typo → `dependencyResolutionManagement`; plugin-id
  mismatch (`gradlePlugin{}` declares `id = "dev.hotreload"`, README applied a com.github id). Fixed
  to apply `id("dev.hotreload")` + a pluginManagement `resolutionStrategy.eachPlugin` mapping that
  id to the JitPack module. `jitpack.yml` jdk 21 confirmed correct (JBR = openjdk 21).

### RESOLVED 2026-07-07 — runtime-client coordinate fix applied (tag naming DECIDED: `0.1.0`, no `v`)
Jay picked tag `0.1.0`. The recommended fix below was applied in full (Fable session 2026-07-07):
- `runtime-client` group → `com.github.xception-hash.compose-hot-reload` (build.gradle.kts, both
  the project `group` and the publication `groupId`).
- `HotReloadPlugin.kt` now injects `com.github.xception-hash.compose-hot-reload:runtime-client:0.1.0`.
- README: `version "0.1.0"` in the plugins block + matching AAR coordinate (no `v` prefix anywhere).
- **jitpack.yml gained an `install:` section** — the root Gradle build only contains
  engine/cli/protocol, so JitPack's default install would have published NOTHING; it now runs
  `./gradlew -p gradle-plugin publishToMavenLocal` + `./gradlew -p runtime-client publishToMavenLocal`
  (both verified working locally with the root wrapper; runtime-client lands under the new group in ~/.m2).
- Verified: both samples `assembleDebug` green; `:app:dependencies` shows
  `com.github.xception-hash.compose-hot-reload:runtime-client:0.1.0 -> project ':runtime-client:runtime-client'`
  (composite substitution matches the new group — samples' repos have no mavenLocal/JitPack, so this
  can't be a false positive).
Still open (Jay, unchanged): the acceptance "scratch project resolves from JitPack" gate — first
real JitPack build will exercise the install commands + Android SDK/NDK availability on their
builder (runtime-client has NDK native code; if the JitPack build times out or lacks NDK, that
surfaces there, not locally).

### Original blocker record (pre-fix, kept for context)
`HotReloadPlugin.kt:59` injects `debugImplementation("dev.hotreload:runtime-client:0.1")`. This
works for **local e2e** only because the samples `includeBuild("../../runtime-client")` and Gradle
composite substitution matches on group:artifact (`dev.hotreload:runtime-client`), ignoring version
— which is why e2e is green with the stale `0.1`. But a **published JitPack consumer** cannot
resolve `dev.hotreload:runtime-client`; JitPack serves it as
`com.github.xception-hash.compose-hot-reload:runtime-client:<tag>` (which is what README line ~65
already promises the plugin adds). NOT fixed inline — changing the coordinate would break the green
composite-build e2e, and the correct form can only be confirmed by a real JitPack build.
Recommended coherent fix (apply + validate together, Jay decides tag naming):
1. Set `runtime-client` `group = "com.github.xception-hash.compose-hot-reload"` (aligns composite
   substitution + JitPack on one group).
2. Plugin injects `com.github.xception-hash.compose-hot-reload:runtime-client:<version>`.
3. Tag naming: JitPack version == the git tag verbatim. If plugin hardcodes `0.1.0`, tag `0.1.0`
   (no `v`) and make README coordinates match; if you keep `v0.1.0`, the plugin must inject `v0.1.0`.
4. Validate via the acceptance "scratch project resolves" gate BEFORE announcing the release.

## Acceptance
> agy (headless) runs ONLY the NOTICE-grep check below and confirms the version grep is clean.
> The e2e run, clean-clone, and tag/release gates are DEVICE/manual — Jay runs them. agy must
> NOT attempt `./e2e/run.sh`, `git tag`, or any publish/network command.
- `./e2e/run.sh` green on the release commit (no code changed, so this is a sanity gate). [JAY]
- A **clean clone** on the pinned toolchain, following ONLY the README quickstart, reaches a
  working hot-reload session (Jay runs this by hand — the quickstart must not assume repo
  scripts knowledge).
- NOTICE covers every AOSP-headered file (grep above returns no unlisted file).
- `git tag v0.1.0` exists; release page has the plugin zip; published coordinates resolve from
  a scratch project.
