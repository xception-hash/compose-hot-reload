package dev.hotreload.engine

import dev.hotreload.protocol.ClassDex
import dev.hotreload.protocol.Protocol
import java.nio.file.Path
import java.util.concurrent.CountDownLatch

/**
 * The `hotreload watch` happy path: watch sources → incremental compile → diff the
 * class output → classify → dex → push to the device → targeted invalidation.
 * Phase 1 scope: anything the [Classifier] can't hot-swap prints a rebuild notice.
 */
class WatchSession(private val config: Config) {

    class Config(
        val projectDir: Path,
        /** Gradle module holding the app, e.g. "app". */
        val module: String,
        val applicationId: String,
        val sourceRoots: List<Path>,
        /** AGP 9 built-in-Kotlin class output dir for the debug variant. */
        val classesDir: Path,
        val d8: Path,
        val adb: Path,
    ) {
        companion object {
            fun forProject(projectDir: Path, module: String, applicationId: String, d8: Path, adb: Path) = Config(
                projectDir = projectDir,
                module = module,
                applicationId = applicationId,
                sourceRoots = listOf(
                    projectDir.resolve("$module/src/main/kotlin"),
                    projectDir.resolve("$module/src/main/java"),
                ),
                classesDir = projectDir.resolve(
                    "$module/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes",
                ),
                d8 = d8,
                adb = adb,
            )
        }
    }

    private val compileTask = ":${config.module}:compileDebugKotlin"

    /** Blocks until the process is killed. */
    fun run() {
        val adb = Adb(config.adb)
        val localPort = adb.forward(Protocol.deviceSocketName(config.applicationId))
        val dexer = DexCompiler(config.d8)

        DeviceClient(port = localPort).use { device ->
            val caps = device.ping()
            println(
                "device: api=${caps.apiLevel} protocol=${caps.protocolVersion} " +
                    "redefine=${caps.canRedefine} structural=${caps.canStructural} " +
                    "inject=${caps.canInjectFile} compose=${caps.composeBridgeOk}",
            )
            check(caps.canRedefine && caps.composeBridgeOk) {
                "device is not hot-swappable — is the app a debuggable build with the runtime-client?"
            }

            GradleCompiler(config.projectDir.toFile()).use { gradle ->
                print("initial build... ")
                val initial = gradle.compile(compileTask)
                check(initial.success) { "initial compile failed:\n${initial.output}" }
                println("ok (${initial.durationMs}ms)")

                var snapshot = ClassSnapshot.scan(config.classesDir)
                check(snapshot.isNotEmpty()) { "no classes found under ${config.classesDir}" }

                // Registering a nonexistent root makes DirectoryWatcher fail (asynchronously).
                val roots = config.sourceRoots.filter { java.nio.file.Files.isDirectory(it) }
                check(roots.isNotEmpty()) { "no source roots exist among ${config.sourceRoots.joinToString()}" }
                println("watching ${roots.joinToString()} (${snapshot.size} classes)")

                SourceWatcher(roots) { changedSources ->
                    snapshot = onSave(changedSources, gradle, dexer, device, snapshot)
                }.use { watcher ->
                    watcher.start()
                    CountDownLatch(1).await() // run until Ctrl-C
                }
            }
        }
    }

    private fun onSave(
        changedSources: Set<Path>,
        gradle: GradleCompiler,
        dexer: DexCompiler,
        device: DeviceClient,
        snapshot: Map<String, SnapshotEntry>,
    ): Map<String, SnapshotEntry> {
        val t0 = System.nanoTime()
        println("\nchanged: ${changedSources.joinToString { it.fileName.toString() }}")

        val compile = gradle.compile(compileTask)
        if (!compile.success) {
            println(compile.output.lineSequence().filter { "error:" in it || "e: " in it }.joinToString("\n").ifEmpty { compile.output })
            println("compile failed — fix and save again")
            return snapshot
        }

        val newSnapshot = ClassSnapshot.scan(config.classesDir)
        val changed = newSnapshot.filter { (name, entry) -> snapshot[name]?.contentHash != entry.contentHash }
        if (changed.isEmpty()) {
            println("no bytecode changes (${elapsedMs(t0)}ms)")
            return newSnapshot
        }

        val verdicts = changed.map { (name, entry) -> entry.facts to Classifier.classify(snapshot[name]?.facts, entry.facts) }
        when (val plan = Classifier.plan(verdicts)) {
            is Classifier.PatchPlan.Rebuild -> {
                plan.reasons.forEach { println("cannot hot-swap: $it") }
                println("run a full install (e.g. ./gradlew :${config.module}:installDebug), relaunch, then restart watch")
                return snapshot // keep the old baseline: these changes were NOT applied
            }
            is Classifier.PatchPlan.HotSwap -> {
                for (fqcn in plan.inject) {
                    val entry = changed.getValue(fqcn.replace('.', '/'))
                    // The device's PatchServer only accepts [A-Za-z0-9_.-] filenames; '$'
                    // from nested/lambda classes must be sanitized (it's only a label).
                    device.injectDex("${fqcn.replace('.', '_').replace('$', '_')}.dex", dexer.dexOne(entry.file))
                }
                if (plan.redefine.isNotEmpty()) {
                    val batch = plan.redefine.map { fqcn ->
                        ClassDex(fqcn, dexer.dexOne(changed.getValue(fqcn.replace('.', '/')).file))
                    }
                    device.redefine(batch, plan.structural)
                }
                if (plan.invalidateKeys.isNotEmpty()) {
                    device.invalidate(plan.invalidateKeys.toIntArray())
                }
                val what = buildList {
                    if (plan.inject.isNotEmpty()) add("${plan.inject.size} injected")
                    if (plan.redefine.isNotEmpty()) add("${plan.redefine.size} redefined${if (plan.structural) " (structural)" else ""}")
                    add("${plan.invalidateKeys.size} groups invalidated")
                }
                println("hot-swapped: ${what.joinToString()} in ${elapsedMs(t0)}ms")
                if (plan.invalidateKeys.isNotEmpty()) verifyRecomposition(device)
            }
        }
        return newSnapshot
    }

    /**
     * A broken swap is invisible without this: the Recomposer captures the exception,
     * restores the last-good frame and keeps running (screen silently stays stale).
     * Recomposition runs a frame after Invalidate, so settle first, then query.
     * No automatic tier-2 reset: a deterministic error fails again on reset, which
     * destroys the last-good frame (blank screen) and all remember state for nothing —
     * fixing the source and saving again recovers in place (verified live).
     */
    private fun verifyRecomposition(device: DeviceClient) {
        Thread.sleep(RECOMPOSE_SETTLE_MS)
        val errors = device.composeErrors(clear = true)
        if (errors.isEmpty()) return

        errors.forEach { println("recomposition failed: ${it.message}") }
        println("the device is still showing the previous UI — fix the edit and save again")
    }

    private fun elapsedMs(t0: Long) = (System.nanoTime() - t0) / 1_000_000

    private companion object {
        /** Recomposition happens on a later frame than the Invalidate ack. */
        const val RECOMPOSE_SETTLE_MS = 300L
    }
}
