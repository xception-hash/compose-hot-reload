package dev.hotreload.engine

import dev.hotreload.protocol.ClassBytes
import dev.hotreload.protocol.ClassDex
import dev.hotreload.protocol.Protocol
import java.net.URLClassLoader
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
        val project: ProjectConfig,
        val d8: Path,
        val adb: Path,
    )

    /** The app module is AGP by definition, so its compile task is known before probing. */
    private val compileTask = config.project.modules.first().let { app ->
        config.project.moduleMetadata[app.gradlePath]?.compileTask
            ?: "${app.gradlePath}:compile${(app.variant ?: config.project.variant).taskSegment()}Kotlin"
    }

    /** Resolved after the initial build (layout probing needs compiled output). */
    private lateinit var modules: List<ModuleSpec>
    private val classesDirs get() = modules.map { it.classesDir }
    private val resDirs get() = modules.flatMap { it.resDirs }.filter { Files.isDirectory(it) }

    /** Live-literals state (T24); populated only when [Config.literals]. */
    private var sourceRoots: List<Path> = emptyList()
    private var literalTable: LiteralTable = LiteralTable.empty()
    private val baselineText = HashMap<Path, String>()

    // ---- interpreter fast path (T27) ----
    /** Internal names of classes primed (stub-transformed + structurally redefined) this session. */
    private val primedClasses = mutableSetOf<String>()
    /** interp.dex is injected into the app classloader once, on the first interpreted edit. */
    private var interpDexInjected = false

    /** The committed interpreter runtime dex, shipped as an engine resource (T27 step 2). */
    private val interpDex: ByteArray by lazy {
        javaClass.getResourceAsStream("/dev/hotreload/interp.dex")?.use { it.readBytes() }
            ?: error("interp.dex resource missing from the engine jar")
    }

    /**
     * Classloader used only for StubTransform stack-map frame computation. The module output dirs
     * resolve the app's own types; framework/Compose types fall back to Object (the on-device
     * verifier is the real backstop, per StubTransform).
     */
    private val frameLoader: ClassLoader by lazy {
        URLClassLoader(classesDirs.map { it.toUri().toURL() }.toTypedArray(), javaClass.classLoader)
    }

    /**
     * If the app is not running on the device, launch it and poll until it's alive.
     * Delegates to the extracted [AppLauncher] (T33 phase 6) — behavior is unchanged.
     */
    private fun ensureAppRunning(adb: Adb, appId: String) =
        AppLauncher.ensureRunning(adb, appId, config.project.launchActivity)

    /** Blocks until the process is killed. */
    fun run() {
        val adb = Adb(config.adb, config.project.deviceSerial)
        val appId = config.project.applicationId
        ensureAppRunning(adb, appId)
        val localPort = adb.forward(Protocol.deviceSocketName(appId))
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

            GradleInvocation.open(config.project).use { invocation ->
                GradleCompiler(
                    config.project.projectDir.toFile(),
                    invocation.arguments,
                    config.project.projectJavaHome?.toFile(),
                ).use { gradle ->
                print("initial build... ")
                val initial = gradle.compile(compileTask)
                check(initial.success) { "initial compile failed:\n${initial.output}" }
                println("ok (${initial.durationMs}ms)")

                modules = config.project.modules.map { ModuleSpec.probe(config.project.projectDir, it, config.project.variant, config.project.moduleMetadata[it.gradlePath]) }
                check(modules.first().layout != ModuleSpec.Layout.JVM) {
                    "app module '${modules.first().name}' is not an AGP module"
                }
                println("modules: " + modules.joinToString { "${it.gradlePath}: ${it.layout} (${it.classesDir})" })

                var snapshot = ClassSnapshot.scan(classesDirs)
                check(snapshot.isNotEmpty()) { "no classes found under ${classesDirs.joinToString()}" }

                val dexdump = config.d8.resolveSibling("dexdump").takeIf { Files.isRegularFile(it) }
                if (dexdump == null) println("warning: no dexdump next to ${config.d8} — bitmap edits will overlay without invalidating")
                val resources = ResourceSwapper(
                    modules.first(), resDirs, config.project.applicationId, gradle, adb, device,
                    dexdump, config.project.integrationMode,
                )

                // Registering a nonexistent root makes DirectoryWatcher fail (asynchronously).
                sourceRoots = modules.flatMap { it.sourceRoots }.filter { Files.isDirectory(it) }
                check(sourceRoots.isNotEmpty()) { "no source roots exist among ${modules.flatMap { it.sourceRoots }.joinToString()}" }

                if (config.project.literals) initLiterals()
                // res/values value edits are hot-swapped too, but only if the dir exists.
                val roots = sourceRoots + resDirs.filter { Files.isDirectory(it) }

                SourceWatcher(roots) { changedSources ->
                    snapshot = onSave(changedSources, gradle, dexer, device, resources, snapshot)
                }.use { watcher ->
                    watcher.start()
                    // AFTER start(): "watching" is the e2e/IDE readiness gate — printing it
                    // before registration completes loses saves made in that window.
                    println("watching ${roots.joinToString()} (${snapshot.size} classes)")
                    CountDownLatch(1).await() // run until Ctrl-C
                }
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

    /** Whether the pending resource batch includes a bitmap (`.png`/`.webp`/`.jpg`/`.jpeg`) — see [ResourceSwapper.swap]. */
    private var pendingBitmapSwap = false

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
            println("ignored (hot-reloads .kt and res/**/*.{xml,png,webp,jpg,jpeg} only): ${ignored.joinToString { it.fileName.toString() }}")
        }

        var newSnapshot = snapshot
        var invalidated = false
        if (resChanges.isNotEmpty()) {
            pendingResourceSwap = true
            if (resChanges.any { it.extension in BITMAP_EXTENSIONS }) pendingBitmapSwap = true
        }

        // Live-literals fast path (T24): a single .kt save that only changes one constant
        // is pushed in place NOW.  A successful push already recomposes the enclosing
        // group; recompiling it can produce a keyless LiveLiterals helper and reset state.
        val literalPushed = if (config.project.literals && ktChanges.size == 1 && resChanges.isEmpty()) {
            tryLiteralFastPath(ktChanges.single(), device, snapshot, t0)
        } else false

        // Code first, then resources (spec): a mixed batch redefines classes before the
        // resource overlay so both are in place when the tree recomposes.
        if (ktChanges.isNotEmpty() && !literalPushed) {
            val result = swapClasses(gradle, dexer, device, snapshot, t0)
                // Compile failed or rebuild required: keep the old baseline. The resource
                // swap is skipped too (assembleDebug shares the failing compile) but stays
                // pending, so the fix-and-save retries it.
                ?: return snapshot
            newSnapshot = result.snapshot
            invalidated = invalidated || result.invalidated
            if (config.project.literals) refreshLiterals(ktChanges)
        }

        if (pendingResourceSwap && resources.swap(t0, pendingBitmapSwap)) {
            pendingResourceSwap = false
            pendingBitmapSwap = false
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

        val verdicts = changed.map { (name, entry) ->
            var verdict = Classifier.classify(snapshot[name]?.facts, entry.facts)
            // Once primed, a class's stub prologue intercepts every method entry, so a plain
            // redefine would fight the interpreter — route ALL its later edits through the
            // interpreter (even body-only ones), keeping the same changed-only invalidate keys the
            // classifier already computed (so unchanged siblings keep their state).
            if (name in primedClasses) {
                // Rebuild stays Rebuild; NewClass can't apply to a primed class; SupportClass
                // stays support — a primed lambda whose ctor later changes must take the proxy
                // path (re-priming a changed ctor would trip BytecodeValidator, and the proxy
                // replaces its instances anyway).
                val keys = when (verdict) {
                    is Classifier.Verdict.BodyOnly -> verdict.invalidateKeys
                    is Classifier.Verdict.Structural -> verdict.invalidateKeys
                    is Classifier.Verdict.Interpret -> verdict.groupIds
                    else -> null
                }
                if (keys != null) verdict = Classifier.Verdict.Interpret(keys)
            }
            entry.facts to verdict
        }
        return when (val plan = Classifier.plan(verdicts)) {
            is Classifier.PatchPlan.Rebuild -> {
                plan.reasons.forEach { println("cannot hot-swap: $it") }
                if (config.project.integrationMode == IntegrationMode.ZERO_TOUCH) {
                    println("run 'hotreload prepare --zero-touch' with the same project options, then restart watch")
                } else {
                    println("run a full install (e.g. ./gradlew ${modules.first().installTask}), relaunch, then restart watch")
                }
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
                // Redefine-path invalidation (skip when this batch is pure-interpret — the
                // interpreter path recomposes itself below). No compose keys among the changed
                // bodies (e.g. a pure-Kotlin module's helper edit) → whole-tree invalidateAll,
                // keyless and state-preserving (T16).
                val redefinedAnything = plan.inject.isNotEmpty() || plan.redefine.isNotEmpty()
                val wholeTree = plan.invalidateKeys.isEmpty()
                if (redefinedAnything) {
                    if (wholeTree) device.invalidateAll() else device.invalidate(plan.invalidateKeys.toIntArray())
                }

                if (plan.interpret.isNotEmpty() || plan.support.isNotEmpty()) {
                    applyInterpret(plan.interpret, plan.support, plan.groupIds, changed, snapshot, dexer, device, t0)
                }

                val what = buildList {
                    if (plan.inject.isNotEmpty()) add("${plan.inject.size} injected")
                    if (plan.redefine.isNotEmpty()) add("${plan.redefine.size} redefined${if (plan.structural) " (structural)" else ""}")
                    if (redefinedAnything) add(if (wholeTree) "whole tree invalidated (no compose keys)" else "${plan.invalidateKeys.size} groups invalidated")
                }
                if (what.isNotEmpty()) println("hot-swapped: ${what.joinToString()} in ${elapsedMs(t0)}ms")
                ClassSwapResult(newSnapshot, invalidated = true)
            }
        }
    }

    /**
     * Route [interpret] classes (FQCNs) through the on-device LiveEdit interpreter (T27 step 5):
     *  1. prime any class first seen this session — stub-transform its BASELINE (pre-edit,
     *     on-device) bytes, dex, and structural-redefine so its method entries divert to the
     *     interpreter (logs `primed:`);
     *  2. inject interp.dex into the app classloader once per process;
     *  3. hand the edited `.class` bytes to the interpreter + recompose the class's composables.
     * Ordering matches the client contract (inject interp.dex → redefine primed originals →
     * LiveEditClasses); here priming precedes the inject only because ART resolves LiveEditStubs
     * lazily (not until the primed method next runs, which is after the interpret invalidate).
     */
    private fun applyInterpret(
        interpret: List<String>,
        support: List<String>,
        groupIds: Set<Int>,
        changed: Map<String, SnapshotEntry>,
        baseline: Map<String, SnapshotEntry>,
        dexer: DexCompiler,
        device: DeviceClient,
        t0: Long,
    ) {
        if (!interpDexInjected) {
            device.injectDex(INTERP_DEX_NAME, interpDex)
            interpDexInjected = true
        }
        for (fqcn in interpret) {
            val internal = fqcn.replace('.', '/')
            if (internal in primedClasses) continue
            val baselineBytes = baseline[internal]?.bytes
                ?: error("no baseline bytes to prime $fqcn (class absent from the pre-edit snapshot)")
            val stub = StubTransform.transform(baselineBytes, frameLoader)
            device.redefine(listOf(ClassDex(fqcn, dexer.dexBytes(stub))), structural = true)
            primedClasses += internal
            println("primed: $fqcn")
        }
        fun bytesOf(fqcns: List<String>) = fqcns.map { fqcn ->
            val internal = fqcn.replace('.', '/')
            ClassBytes(internal, changed.getValue(internal).bytes)
        }
        // Support classes (T28) are NOT primed — the interpreter registers them proxy-flagged
        // and interpreted NEW replaces their instances with Proxies.* VM proxies.
        device.liveEditClasses(bytesOf(interpret), bytesOf(support), primedDexName = null, groupIds = groupIds.toList())
        if (support.isNotEmpty()) println("support: ${support.joinToString()}")
        println("interpreted: ${interpret.joinToString()} (${elapsedMs(t0)}ms)")
    }

    /**
     * A watched resource file (`.xml`, `.png`, `.webp`, `.jpg`, `.jpeg`) under any `res/` subdirectory
     * of any AGP module. The overlay mechanism is uniform (whole-APK resource table + the (type,name)
     * guard), so values AND file-based resources (drawable/color/anim/...) all route to the swapper;
     * what actually refreshes on screen is governed by the runtime's cache clearing.
     */
    private fun isResourceFile(path: Path): Boolean {
        val ext = path.extension
        if (ext != "xml" && ext !in BITMAP_EXTENSIONS) return false
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
    ): Boolean {
        val baseline = baselineText[ktFile] ?: return false
        val rel = sourceRelative(ktFile) ?: return false
        val entries = literalTable.entriesFor(rel)
        if (entries.isEmpty()) return false
        val updated = runCatching { Files.readString(ktFile) }.getOrNull() ?: return false
        val push = LiteralFastPath.detect(baseline, updated, entries) ?: return false
        try {
            device.literalUpdate(push.key, push.helperClass, enclosingKey(push, snapshot), push.type, push.value)
            println("literal-pushed: ${push.key} = ${push.value} in ${elapsedMs(t0)}ms")
            baselineText[ktFile] = updated
            return true
        } catch (t: Throwable) {
            println("literal fast path failed (${t.message}) — falling back to full swap")
            return false
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

        /** InjectDex filename for the interpreter runtime (PatchServer allows `.`, `-`). */
        const val INTERP_DEX_NAME = "hotreload-interp.dex"

        /**
         * Raster image extensions that trigger [ResourceSwapper]'s bitmap-remember bash
         * (T23, extended T30 item 4): overlaying alone can't surface these since
         * `painterResource`'s bitmap branch remembers on the intra-APK path string, which
         * an overlay never changes. Nine-patch (`.9.png`) files carry the plain `png`
         * extension (`Path.extension` of `foo.9.png` is `png`), so they're covered already.
         */
        val BITMAP_EXTENSIONS = setOf("png", "webp", "jpg", "jpeg")
    }
}
