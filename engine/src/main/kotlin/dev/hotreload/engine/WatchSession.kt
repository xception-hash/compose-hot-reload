package dev.hotreload.engine

import dev.hotreload.protocol.ClassDex
import dev.hotreload.protocol.Protocol
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import kotlin.io.path.extension

/**
 * The `hotreload watch` happy path: watch sources → incremental compile → diff the
 * class output → classify → dex → push to the device → targeted invalidation.
 * Multi-module (docs/multi-module-design.md): all modules are watched and snapshotted;
 * every save compiles the app module's task and lets Gradle run exactly the upstream
 * compiles it needs (ABI fan-out included), then the merged re-scan picks up whatever
 * changed anywhere. Anything the [Classifier] can't hot-swap prints a rebuild notice.
 */
class WatchSession(private val config: Config) {

    class Config(
        val projectDir: Path,
        /** Watched Gradle modules; the FIRST holds the app (applicationId, APK, resources). */
        val moduleNames: List<String>,
        val applicationId: String,
        val d8: Path,
        val adb: Path,
        /**
         * Live-literals fast path (T24, `--literals`): literal-only edits are pushed in
         * place through Compose live literals before the normal compile+swap runs. Requires
         * the app built with `-Photreload.liveLiterals=true` (verified at startup).
         */
        val literals: Boolean = false,
    ) {
        init {
            require(moduleNames.isNotEmpty()) { "at least one module (the app module) is required" }
        }
    }

    /** The app module is AGP by definition, so its compile task is known before probing. */
    private val compileTask = ":${config.moduleNames.first().replace('/', ':')}:compileDebugKotlin"

    /** Resolved after the initial build (layout probing needs compiled output). */
    private lateinit var modules: List<ModuleSpec>
    private val classesDirs get() = modules.map { it.classesDir }
    private val resDirs get() = modules.mapNotNull { it.resDir }

    /** Live-literals state (T24); populated only when [Config.literals]. */
    private var sourceRoots: List<Path> = emptyList()
    private var literalTable: LiteralTable = LiteralTable.empty()
    private val baselineText = HashMap<Path, String>()

    /** Blocks until the process is killed. */
    fun run() {
        val adb = Adb(config.adb)
        val localPort = adb.forward(Protocol.deviceSocketName(config.applicationId))
        val dexer = DexCompiler(config.d8)

        DeviceClient(port = localPort).use { device ->
            val caps = device.ping()
            check(caps.protocolVersion == Protocol.VERSION) {
                "protocol version mismatch (device ${caps.protocolVersion} != engine ${Protocol.VERSION}) — reinstall the app (stale runtime-client)"
            }
            println(
                "device: api=${caps.apiLevel} protocol=${caps.protocolVersion} " +
                    "Compose runtime version: ${caps.composeVersion} " +
                    "redefine=${caps.canRedefine} structural=${caps.canStructural} " +
                    "inject=${caps.canInjectFile} compose=${caps.composeBridgeOk}",
            )
            check(caps.canRedefine && caps.composeBridgeOk) {
                "device is not hot-swappable — is the app a debuggable build with the runtime-client?"
            }

            val gradleArgs = if (config.literals) listOf("-Photreload.liveLiterals=true") else emptyList()
            GradleCompiler(config.projectDir.toFile(), gradleArgs).use { gradle ->
                print("initial build... ")
                val initial = gradle.compile(compileTask)
                check(initial.success) { "initial compile failed:\n${initial.output}" }
                println("ok (${initial.durationMs}ms)")

                modules = config.moduleNames.map { ModuleSpec.probe(config.projectDir, it) }
                check(modules.first().layout == ModuleSpec.Layout.AGP) {
                    "app module '${modules.first().name}' is not an AGP module (no built-in-kotlinc output)"
                }
                println("modules: " + modules.joinToString { "${it.gradlePath}: ${it.layout} (${it.classesDir})" })

                var snapshot = ClassSnapshot.scan(classesDirs)
                check(snapshot.isNotEmpty()) { "no classes found under ${classesDirs.joinToString()}" }

                val resources = ResourceSwapper(modules.first(), resDirs, config.applicationId, gradle, adb, device)

                // Registering a nonexistent root makes DirectoryWatcher fail (asynchronously).
                sourceRoots = modules.flatMap { it.sourceRoots }.filter { Files.isDirectory(it) }
                check(sourceRoots.isNotEmpty()) { "no source roots exist among ${modules.flatMap { it.sourceRoots }.joinToString()}" }

                if (config.literals) initLiterals()
                // res/values value edits are hot-swapped too, but only if the dir exists.
                val roots = sourceRoots + resDirs.filter { Files.isDirectory(it) }
                println("watching ${roots.joinToString()} (${snapshot.size} classes)")

                SourceWatcher(roots) { changedSources ->
                    snapshot = onSave(changedSources, gradle, dexer, device, resources, snapshot)
                }.use { watcher ->
                    watcher.start()
                    CountDownLatch(1).await() // run until Ctrl-C
                }
            }
        }
    }

    /** Outcome of a class-swap attempt: the new baseline + whether the device recomposed. */
    private class ClassSwapResult(val snapshot: Map<String, SnapshotEntry>, val invalidated: Boolean)

    /**
     * A values edit whose batch couldn't complete (broken .kt in the same save → the shared
     * compile fails) is NOT re-delivered by the watcher on the fix-and-save — the .xml sits
     * unchanged on disk. So resource swaps stay pending until one succeeds.
     */
    private var pendingResourceSwap = false

    private fun onSave(
        changedSources: Set<Path>,
        gradle: GradleCompiler,
        dexer: DexCompiler,
        device: DeviceClient,
        resources: ResourceSwapper,
        snapshot: Map<String, SnapshotEntry>,
    ): Map<String, SnapshotEntry> {
        val t0 = System.nanoTime()
        val ktChanges = changedSources.filter { it.extension == "kt" }
        val resChanges = changedSources.filter { isResourceFile(it) }
        println("\nchanged: ${changedSources.joinToString { it.fileName.toString() }}")
        val ignored = changedSources - ktChanges.toSet() - resChanges.toSet()
        if (ignored.isNotEmpty()) {
            println("ignored (hot-reloads .kt and res/**/*.{xml,png,webp} only): ${ignored.joinToString { it.fileName.toString() }}")
        }

        var newSnapshot = snapshot
        var invalidated = false
        if (resChanges.isNotEmpty()) pendingResourceSwap = true

        // Live-literals fast path (T24): a single .kt save that only changes one constant
        // is pushed in place NOW — it wins the latency race. The normal compile+swap below
        // still runs and re-applies it idempotently (keeping the baseline/table truthful).
        if (config.literals && ktChanges.size == 1 && resChanges.isEmpty()) {
            tryLiteralFastPath(ktChanges.single(), device, snapshot, t0)
        }

        // Code first, then resources (spec): a mixed batch redefines classes before the
        // resource overlay so both are in place when the tree recomposes.
        if (ktChanges.isNotEmpty()) {
            val result = swapClasses(gradle, dexer, device, snapshot, t0)
                // Compile failed or rebuild required: keep the old baseline. The resource
                // swap is skipped too (assembleDebug shares the failing compile) but stays
                // pending, so the fix-and-save retries it.
                ?: return snapshot
            newSnapshot = result.snapshot
            invalidated = invalidated || result.invalidated
            if (config.literals) refreshLiterals(ktChanges)
        }

        if (pendingResourceSwap && resources.swap(t0)) {
            pendingResourceSwap = false
            invalidated = true
        }

        if (invalidated) verifyRecomposition(device)
        return newSnapshot
    }

    /** Returns null when the batch cannot be applied (compile failed / needs reinstall). */
    private fun swapClasses(
        gradle: GradleCompiler,
        dexer: DexCompiler,
        device: DeviceClient,
        snapshot: Map<String, SnapshotEntry>,
        t0: Long,
    ): ClassSwapResult? {
        // Always the app module's task: Gradle's task graph runs exactly the upstream
        // compiles the edit requires (a :core ABI change recompiles :feature too) and
        // up-to-date-skips the rest — never re-implement that fan-out here (design D3).
        val compile = gradle.compile(compileTask)
        if (!compile.success) {
            println(compile.output.lineSequence().filter { "error:" in it || "e: " in it }.joinToString("\n").ifEmpty { compile.output })
            println("compile failed — fix and save again")
            return null
        }

        val newSnapshot = ClassSnapshot.scan(classesDirs)
        val changed = newSnapshot.filter { (name, entry) -> snapshot[name]?.contentHash != entry.contentHash }
        if (changed.isEmpty()) {
            println("no bytecode changes (${elapsedMs(t0)}ms)")
            return ClassSwapResult(newSnapshot, invalidated = false)
        }

        val verdicts = changed.map { (name, entry) -> entry.facts to Classifier.classify(snapshot[name]?.facts, entry.facts) }
        return when (val plan = Classifier.plan(verdicts)) {
            is Classifier.PatchPlan.Rebuild -> {
                plan.reasons.forEach { println("cannot hot-swap: $it") }
                println("run a full install (e.g. ./gradlew ${modules.first().gradlePath}:installDebug), relaunch, then restart watch")
                null // keep the old baseline: these changes were NOT applied
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
                // No compose keys among the changed bodies (e.g. a pure-Kotlin module's
                // helper edit): the redefine landed but nothing would recompose, so the
                // UI would stay stale until the next natural state change. Whole-tree
                // invalidateAll is keyless and preserves remember/rememberSaveable (T16).
                val wholeTree = plan.invalidateKeys.isEmpty()
                if (wholeTree) {
                    device.invalidateAll()
                } else {
                    device.invalidate(plan.invalidateKeys.toIntArray())
                }
                val what = buildList {
                    if (plan.inject.isNotEmpty()) add("${plan.inject.size} injected")
                    if (plan.redefine.isNotEmpty()) add("${plan.redefine.size} redefined${if (plan.structural) " (structural)" else ""}")
                    add(if (wholeTree) "whole tree invalidated (no compose keys)" else "${plan.invalidateKeys.size} groups invalidated")
                }
                println("hot-swapped: ${what.joinToString()} in ${elapsedMs(t0)}ms")
                ClassSwapResult(newSnapshot, invalidated = true)
            }
        }
    }

    /**
     * A watched resource file (`.xml`, `.png`, `.webp`) under any `res/` subdirectory of any AGP module.
     * The overlay mechanism is uniform (whole-APK resource table + the (type,name) guard), so values
     * AND file-based resources (drawable/color/anim/...) all route to the swapper;
     * what actually refreshes on screen is governed by the runtime's cache clearing.
     */
    private fun isResourceFile(path: Path): Boolean {
        val ext = path.extension
        if (ext != "xml" && ext != "png" && ext != "webp") return false
        val parent = path.parent ?: return false
        return resDirs.any { path.startsWith(it) } && parent.parent?.let { p ->
            resDirs.any { it == p }
        } == true
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

    // ---- live-literals fast path (T24) ----

    /**
     * After the initial build: extract the live-literal table from the compiled
     * `LiveLiterals$*Kt` helpers and snapshot every watched `.kt` file's text (the
     * baseline offsets are relative to). Fails loud if `--literals` was requested but the
     * app wasn't built with `-Photreload.liveLiterals=true` (no helpers → nothing to push).
     */
    private fun initLiterals() {
        literalTable = LiteralTable.scan(classesDirs)
        check(!literalTable.isEmpty) {
            "--literals requires the app compiled with -Photreload.liveLiterals=true, but no " +
                "LiveLiterals\$*Kt classes were found in ${classesDirs.joinToString()}. Rebuild with " +
                "the property (see README) or drop --literals."
        }
        for (root in sourceRoots) cacheBaseline(root)
        println("live-literals fast path enabled")
    }

    /** Re-scan the table and advance the baseline text for the files just compiled. */
    private fun refreshLiterals(changedKt: List<Path>) {
        literalTable = LiteralTable.scan(classesDirs)
        for (path in changedKt) runCatching { baselineText[path] = Files.readString(path) }
    }

    private fun cacheBaseline(root: Path) {
        Files.walk(root).use { paths ->
            paths.filter { it.extension == "kt" }.forEach { p -> runCatching { baselineText[p] = Files.readString(p) } }
        }
    }

    /**
     * If [ktFile]'s save is a clean single-literal edit, push the new value in place through
     * Compose live literals (Compose recomposes from the state write itself) and log
     * `literal-pushed:`. Any non-match — or send failure — silently defers to the normal
     * compile+swap that follows.
     */
    private fun tryLiteralFastPath(
        ktFile: Path,
        device: DeviceClient,
        snapshot: Map<String, SnapshotEntry>,
        t0: Long,
    ) {
        val baseline = baselineText[ktFile] ?: return
        val rel = sourceRelative(ktFile) ?: return
        val entries = literalTable.entriesFor(rel)
        if (entries.isEmpty()) return
        val updated = runCatching { Files.readString(ktFile) }.getOrNull() ?: return
        val push = LiteralFastPath.detect(baseline, updated, entries) ?: return
        try {
            device.literalUpdate(push.key, push.helperClass, enclosingKey(push, snapshot), push.type, push.value)
            println("literal-pushed: ${push.key} = ${push.value} in ${elapsedMs(t0)}ms")
        } catch (t: Throwable) {
            println("literal fast path failed (${t.message}) — falling back to full swap")
        }
    }

    /**
     * The FunctionKeyMeta compose key of the composable that encloses a live literal — the
     * runtime needs it to wake the Recomposer (a liveLiterals build compiles the literal
     * into a keyless helper). The enclosing composable's name is the last `$fun-<name>`
     * segment of the live-literal key; its key lives on the facade class (the helper
     * `dev.pkg.LiveLiterals$FooKt` ⇒ facade `dev/pkg/FooKt`). 0 if it can't be resolved
     * (the runtime then falls back to a best-effort whole-tree invalidate).
     */
    private fun enclosingKey(push: LiteralPush, snapshot: Map<String, SnapshotEntry>): Int {
        val fnName = push.key.substringAfterLast("\$fun-", "").ifEmpty { return 0 }
        val facadeInternal = push.helperClass.replace('.', '/').replace("LiveLiterals\$", "")
        val members = snapshot[facadeInternal]?.facts?.members ?: return 0
        return members.firstOrNull { it.id.substringBefore('(') == fnName }?.composeKey ?: 0
    }

    /** Source-root-relative path (matches [LiteralTable] keys), or null if outside all roots. */
    private fun sourceRelative(path: Path): String? {
        val root = sourceRoots.firstOrNull { path.startsWith(it) } ?: return null
        return root.relativize(path).toString().replace('\\', '/')
    }

    private fun elapsedMs(t0: Long) = (System.nanoTime() - t0) / 1_000_000

    private companion object {
        /** Recomposition happens on a later frame than the Invalidate ack. */
        const val RECOMPOSE_SETTLE_MS = 300L
    }
}
