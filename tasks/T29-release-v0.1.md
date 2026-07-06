# T29: Release v0.1 — docs polish, license audit, versioning, publish
Status: TODO
Assignee: agy + jay (Opus reviews)

## Goal
Ship the first public release. The product is functionally complete (T21–T27; e2e 14/14).
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
- Limitations section, explicitly including: composable signature changes fall back to rebuild
  until T28 lands; `<clinit>`/constructor edits rebuild; debuggable builds only; min API 30;
  compiled-callee exceptions skip interpreted try/catch (research §5).
- IDE plugin section: what it does (spawns CLI, status widget), where the zip comes from (§4).

### 2 (agy, jay reviews) — LICENSE + NOTICE audit
- Root `LICENSE`: confirm present + correct for OUR code (repo is Apache-2.0).
- `NOTICE`: already attributes interp.dex / interpreter_jni.cpp / StubTransform (T27 step 8).
  Extend for T28 artifacts once merged (generated Proxies.java, LambdaGenerator usage) — the
  ported-file inventory = `third_party/PINNED.txt` + every file with an AOSP copyright header:
  `grep -rl "Android Open Source Project" --include=*.kt --include=*.java --include=*.cpp \
   engine/ runtime-client/ scripts/` — every hit must be listed in NOTICE.
- `third_party/` stays out of any published artifact (it is build-time input only) — verify the
  gradle-plugin/AAR artifacts do not bundle it.

### 3 (agy) — version 0.1.0 everywhere
- One `version = "0.1.0"` per published module: gradle-plugin, runtime-client AAR, engine/cli
  if published, `intellij-plugin` (already 0.1.0 — its zip name proves it). Grep for stray
  `0.0.1`/`SNAPSHOT`.

### 4 (jay decides, agy executes) — publish path
- **Recommended for v1: JitPack or GitHub Packages** (no Sonatype account friction; Maven
  Central can be v0.2). Jay decides; whatever is picked, the README quickstart must reference
  the real coordinates.
- Artifacts: gradle-plugin (plugin marker + jar), runtime-client AAR. Publish from a tagged
  commit.
- IDE plugin: attach `hotreload-intellij-plugin-0.1.0.zip` (built by
  `cd intellij-plugin && ./gradlew buildPlugin`) to a GitHub Release. Marketplace publishing is
  OUT of scope (stretch).
- Tag `v0.1.0`, GitHub Release notes = README feature matrix + limitations.

## Out of scope
Engine/runtime/classifier changes; T28; marketplace; Maven Central.

## Acceptance
- `./e2e/run.sh` green on the release commit (no code changed, so this is a sanity gate).
- A **clean clone** on the pinned toolchain, following ONLY the README quickstart, reaches a
  working hot-reload session (Jay runs this by hand — the quickstart must not assume repo
  scripts knowledge).
- NOTICE covers every AOSP-headered file (grep above returns no unlisted file).
- `git tag v0.1.0` exists; release page has the plugin zip; published coordinates resolve from
  a scratch project.
