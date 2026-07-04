# T04: engine plumbing (extractor, snapshot, compilers, watcher, adb)
Status: DONE (2026-07-04) — reviewed by Claude; Adb.forward(socketName: String) deviation was
the spec's own mid-task update (localabstract transport), correctly self-reconciled by agy.
Acceptance green incl. integration compile of WatchSession/CLI against these components.
Assignee: agy

## Goal
Mechanical infrastructure around the engine core (Phase 1). Claude has written the
decision layer — `ClassFacts.kt`, `Classifier.kt`, `DeviceClient.kt` (do not modify).
This task adds the plumbing components the watch loop will be assembled from. The
final `hotreload watch` orchestration is NOT in this task (Claude writes it next).

## Spec
All files in `engine/src/main/kotlin/dev/hotreload/engine/` unless noted. Match the
existing code style. Each component is standalone; no cross-wiring beyond what's stated.

### 1. `FactsExtractor.kt`
`object FactsExtractor { fun extract(classBytes: ByteArray): ClassFacts }` using ASM
(already a dependency). Contract is documented on `ClassFacts` — follow it exactly:
- Parse with `ClassReader.SKIP_DEBUG` (line-number shifts from edits elsewhere in the
  file must not change body hashes).
- `members`: every declared method (id = `name` + descriptor, e.g. `foo(I)V`) and field
  (id = `name:descriptor`). `access` verbatim from ASM.
- `bodyHash` (methods with code only, else null): render the method's instructions with
  `org.objectweb.asm.util.Textifier` + `TraceMethodVisitor`, hash the resulting text
  (e.g. first 8 bytes of SHA-256 as a Long). Must be deterministic across JVM runs.
- `composeKey`: from the method annotation `Landroidx/compose/runtime/internal/FunctionKeyMeta;`
  (invisible annotation), element `key` (Int). Ignore startOffset/endOffset entirely.

### 2. `ClassSnapshot.kt`
```kotlin
class SnapshotEntry(val file: java.nio.file.Path, val contentHash: Long, val facts: ClassFacts)
object ClassSnapshot { fun scan(classesDir: java.nio.file.Path): Map<String, SnapshotEntry> }
```
Recursively scan `*.class` files; key = JVM internal name from the facts; contentHash =
same hashing scheme over raw file bytes. Skip `META-INF`. Must handle the dir not
existing (return empty map).

### 3. `GradleCompiler.kt`
Wrapper over the Gradle Tooling API:
```kotlin
class GradleCompiler(projectDir: java.io.File) : AutoCloseable {
    fun compile(task: String): CompileResult  // e.g. ":app:compileDebugKotlin"
}
class CompileResult(val success: Boolean, val durationMs: Long, val output: String)
```
- Keep one `ProjectConnection` open for the object's lifetime (warm daemon = fast
  incremental compiles); close it in `close()`.
- Capture stdout+stderr into `output`; a failed build returns `success=false` with the
  output, it must NOT throw.
- Dependencies to add in `engine/build.gradle.kts`:
  `implementation("org.gradle:gradle-tooling-api:9.6.1")` and
  `runtimeOnly("org.slf4j:slf4j-nop:2.0.16")`.
  The tooling API lives in Gradle's own repo — in the ROOT `build.gradle.kts` subprojects
  repositories block add `maven("https://repo.gradle.org/gradle/libs-releases")`. That is
  the only permitted root-build change.

### 4. `DexCompiler.kt`
```kotlin
class DexCompiler(private val d8: java.nio.file.Path, private val minApi: Int = 30) {
    /** One class per dex: returns the classes.dex bytes for the single input class. */
    fun dexOne(classFile: java.nio.file.Path): ByteArray
}
```
Runs the external `d8` tool: `d8 --no-desugaring --min-api <minApi> --output <tmpdir> <classFile>`
(these exact flags — see docs/phase0-findings.md), reads `<tmpdir>/classes.dex`, cleans up
the temp dir (create it under `Files.createTempDirectory`). Non-zero exit → throw
`IllegalStateException` with d8's stderr.

### 5. `SourceWatcher.kt`
```kotlin
class SourceWatcher(roots: List<java.nio.file.Path>, private val onBatch: (Set<java.nio.file.Path>) -> Unit) : AutoCloseable {
    fun start()  // non-blocking; watcher runs on its own daemon thread
}
```
- Use `io.methvin:directory-watcher` (already a dependency).
- Only `*.kt` create/modify/delete events.
- Debounce: collect events until 150ms pass with no new event, then invoke `onBatch`
  once with the deduplicated set. Never invoke concurrently.

### 6. `Adb.kt`
```kotlin
class Adb(private val adb: java.nio.file.Path) {
    /** `adb forward tcp:0 localabstract:<socketName>` — returns the allocated local port. */
    fun forward(socketName: String): Int
    fun removeForward(localPort: Int)
}
```
(The device end is an abstract-namespace Unix socket, not TCP — see
`Protocol.deviceSocketName`. UPDATED 2026-07-04 mid-task: if you already implemented
`forward(devicePort: Int)` with `tcp:<port>`, switch to this signature.)
External process; parse the allocated port from stdout. Non-zero exit → IllegalStateException
with stderr (message must include a hint to check `adb devices`).

### 7. Tests (`engine/src/test/kotlin/dev/hotreload/engine/`)
- `FactsExtractorTest`: generate test classes in-memory with ASM's `ClassWriter` (do NOT
  check in .class fixtures):
  - methods/fields appear with correct ids and access; abstract method → bodyHash null
  - a method annotated with an invisible `Landroidx/compose/runtime/internal/FunctionKeyMeta;`
    (write it with `visitAnnotation(..., false)`, element `key`) → composeKey extracted;
    unannotated → null
  - two generated classes identical except for LineNumberTable content → equal ClassFacts
  - two generated classes differing in one method's instructions → that member's bodyHash
    differs, other members' hashes equal
- `ClassSnapshotTest`: write generated classes into a temp dir tree, scan, assert keys and
  that a byte change flips only that entry's contentHash. Missing dir → empty map.
- No tests for GradleCompiler/DexCompiler/Adb (external tools; compile-only).

## Out of scope
- Do not modify: `ClassFacts.kt`, `Classifier.kt`, `DeviceClient.kt`, `protocol/**`,
  `runtime-client/**`, `samples/**`, `cli/**` (orchestration comes later).
- No watch-loop orchestration, no CLI arg parsing, no config files.
- If a spec detail conflicts with reality (API rename etc.), implement the closest
  equivalent and flag it in the task's Status line — do not redesign.

## Acceptance
From repo root (JAVA_HOME via `source scripts/env.sh`):
```bash
source scripts/env.sh
./gradlew -q :engine:test && echo ENGINE-OK
./gradlew -q :cli:assemble && echo CLI-OK
```
Both markers must print; all new tests green.
