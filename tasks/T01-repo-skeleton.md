# T01: Root Gradle skeleton for engine / cli / protocol modules
Status: TODO
Assignee: agy

## Goal
Phase 1 needs three host-side JVM modules (see `docs/PLAN.md` "Repo layout"). Create the empty
but building skeleton so Claude can drop implementation code in without doing Gradle plumbing.

## Spec
- Root `settings.gradle.kts` + `build.gradle.kts` + `gradle.properties` + Gradle **9.6.1** wrapper
  (copy wrapper from `spike/toy-app`). Include modules `:engine`, `:cli`, `:protocol`.
  Do NOT include `spike/toy-app` (it stays a standalone build).
- All three are plain Kotlin/JVM modules: Kotlin **2.4.0** (`org.jetbrains.kotlin.jvm`),
  JVM toolchain 21, repositories google() + mavenCentral().
- `:protocol` — no dependencies, single placeholder file `protocol/src/main/kotlin/dev/hotreload/protocol/Placeholder.kt` (empty object).
- `:engine` — depends on `:protocol`; add deps `io.methvin:directory-watcher:0.19.1`,
  `org.ow2.asm:asm:9.9`, `org.ow2.asm:asm-tree:9.9`; placeholder `dev.hotreload.engine.Placeholder`.
  Add kotlin-test + JUnit5 test setup with one trivial passing test.
- `:cli` — depends on `:engine`; apply `application` plugin, mainClass `dev.hotreload.cli.MainKt`;
  `main()` prints `hotreload 0.1.0-dev` and exits 0.
- Update root `.gitignore` if needed (build/, .gradle/ already covered).

## Out of scope
No protocol messages, no engine logic, no CLI arg parsing. Don't touch `spike/`, `docs/`, `scripts/`.

## Acceptance
```
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew build   # succeeds, runs the engine test
./gradlew :cli:run -q   # prints: hotreload 0.1.0-dev
```
