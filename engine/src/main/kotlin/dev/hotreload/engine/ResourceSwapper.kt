package dev.hotreload.engine

import org.w3c.dom.Element
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/**
 * Resource hot-swap for value edits (string/color/dimen/...): rebuild the app's resource
 * table with `assembleDebug`, extract `resources.arsc` + `res/` from the APK, push it into
 * the app's code_cache, and have the runtime-client overlay it via `ResourcesLoader` +
 * whole-tree `invalidateAll` (state preserved — T16 docs/resource-invalidation-experiment.md).
 *
 * Value-only guard: overlays only work while resource IDs are stable, which aapt2 keeps
 * across pure value edits but renumbers when the resource *set* changes. So an added /
 * removed / renamed resource is detected and reported as "reinstall required", not overlaid.
 */
class ResourceSwapper(
    /** The application module — its APK carries the merged resource table. */
    private val appModule: ModuleSpec,
    /** Every watched AGP module's res root; library values merge into the app table. */
    private val resDirs: List<Path>,
    private val applicationId: String,
    private val gradle: GradleCompiler,
    private val adb: Adb,
    private val device: DeviceClient,
    /** `dexdump` from build-tools (sibling of d8); null degrades bitmap edits to overlay-only. */
    private val dexdump: Path?,
) {
    private val assembleTask = "${appModule.gradlePath}:assembleDebug"
    private val apkDir = appModule.dir.resolve("build/outputs/apk/debug")
    private val seq = AtomicInteger(0)

    /**
     * Session-unique overlay-name component: `seq` restarts at 1 every watch session, but
     * code_cache survives watch restarts (it's only cleared on reinstall), and `cp -r` into
     * an existing `code_cache/hotreload-overlay-N` nests the copy — the STALE previous-session
     * `resources.arsc` stays on top and the new edit silently never surfaces.
     */
    private val sessionTag = System.currentTimeMillis().toString(radix = 36)

    /** The (type,name) set of every value resource at last-known-good; guards ID stability. */
    private var resourceIds: Set<String> = scanResourceIds()

    /** Lazily-extracted painterResource bitmap-branch key; resolved once per session. */
    private var painterKey: Int? = null
    private var painterKeyResolved = false

    /**
     * Overlay the current value resources onto the device. Returns true iff an overlay was
     * pushed and the whole-tree invalidation triggered (caller then verifies recomposition);
     * false if the swap was skipped (guard tripped, or build/extract failed — session continues).
     *
     * [bitmap]: the batch touched a `.png`/`.webp`. Overlaying alone can't surface those —
     * `painterResource` remembers the decoded bitmap keyed on the intra-APK path string,
     * which is identical in every overlay — so the matching remember groups are bashed via
     * `invalidateGroupsWithKey` (see [PainterKeyExtractor]).
     */
    fun swap(t0: Long, bitmap: Boolean = false): Boolean {
        val currentIds = scanResourceIds()
        if (currentIds != resourceIds) {
            val added = currentIds - resourceIds
            val removed = resourceIds - currentIds
            println(
                buildString {
                    append("resource set changed")
                    if (added.isNotEmpty()) append(" (added ${added.joinToString()})")
                    if (removed.isNotEmpty()) append(" (removed ${removed.joinToString()})")
                    append(" — value-only hot reload can't remap resource IDs")
                },
            )
            println("run a full install (e.g. ./gradlew ${appModule.gradlePath}:installDebug), relaunch, then restart watch")
            // Keep the old baseline: the message persists until they reinstall (or revert).
            return false
        }

        val build = gradle.compile(assembleTask)
        if (!build.success) {
            println(build.output.lineSequence().filter { "error:" in it || "e: " in it }.joinToString("\n").ifEmpty { build.output })
            println("resource build failed — fix and save again")
            return false
        }

        val apk = findApk()
        if (apk == null) {
            println("no debug APK under $apkDir — cannot overlay resources")
            return false
        }

        val overlayName = "hotreload-overlay-$sessionTag-${seq.incrementAndGet()}"
        val staging = extractOverlay(apk, overlayName)
        try {
            adb.push(staging, "/data/local/tmp/")
            adb.runAs(applicationId, "mkdir", "-p", "code_cache")
            // rm first: if the dest ever exists, cp -r would nest instead of overwrite.
            adb.runAs(applicationId, "rm", "-rf", "code_cache/$overlayName")
            adb.runAs(applicationId, "cp", "-r", "/data/local/tmp/$overlayName", "code_cache/$overlayName")
            adb.shell("rm", "-rf", "/data/local/tmp/$overlayName")
            device.loadResources(overlayName)
            if (bitmap) invalidateBitmapRemembers(apk)
        } finally {
            staging.toFile().deleteRecursively()
        }
        println("resource-swapped: $overlayName in ${(System.nanoTime() - t0) / 1_000_000}ms")
        return true
    }

    /** Bash every painterResource bitmap remember so the new overlay's bytes get re-decoded. */
    private fun invalidateBitmapRemembers(apk: Path) {
        if (!painterKeyResolved) {
            painterKeyResolved = true
            painterKey = dexdump?.let { PainterKeyExtractor.extract(apk, it) }
        }
        val key = painterKey
        if (key != null) {
            device.invalidate(intArrayOf(key))
            println("bitmap-invalidated: painterResource remember groups bashed (key=$key)")
        } else {
            println("bitmap overlaid, but the painterResource group key is unavailable — bitmaps stay stale until the activity recreates")
        }
    }

    /** Newest `*.apk` in the debug output dir (assembleDebug produces exactly one for the sample). */
    private fun findApk(): Path? {
        if (!apkDir.isDirectory()) return null
        return Files.list(apkDir).use { stream ->
            stream.filter { it.isRegularFile() && it.extension == "apk" }
                .max(Comparator.comparingLong { Files.getLastModifiedTime(it).toMillis() })
                .orElse(null)
        }
    }

    /** Copy just `resources.arsc` + `res/` from [apk] into a fresh temp dir named [overlayName]. */
    private fun extractOverlay(apk: Path, overlayName: String): Path {
        val root = Files.createTempDirectory("hotreload-").resolve(overlayName)
        Files.createDirectories(root)
        ZipFile(apk.toFile()).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory) continue
                if (entry.name != "resources.arsc" && !entry.name.startsWith("res/")) continue
                val out = root.resolve(entry.name).normalize()
                check(out.startsWith(root)) { "zip entry escapes overlay dir: ${entry.name}" }
                Files.createDirectories(out.parent)
                zip.getInputStream(entry).use { input -> Files.copy(input, out) }
            }
        }
        return root
    }

    /**
     * The set of `type/name` resources: values declared under `res/values*` PLUS file-based
     * resources (drawable/raw/xml/font/... — one ID per file stem). Two resources with the
     * same type+name in different config qualifiers (values-night/, drawable-hdpi/) are one
     * resource ID, so they collapse to one entry — exactly what governs ID stability. File
     * resources must be included: adding res/drawable/foo.xml renumbers IDs just like adding
     * a string, even though only value edits are ever overlaid.
     *
     * Multi-module: the UNION across all watched res roots, deduped — matching AGP's merge
     * semantics (an app resource overriding a library resource of the same type/name is one
     * merged entry), so this set tracks exactly what aapt2 numbers.
     */
    private fun scanResourceIds(): Set<String> {
        val ids = sortedSetOf<String>()
        for (resDir in resDirs) {
            if (!resDir.isDirectory()) continue
            Files.walk(resDir).use { stream ->
                stream.filter { it.isRegularFile() && !it.name.startsWith(".") && it.parent != resDir }
                    .forEach { file ->
                        val dir = file.parent.name
                        if (dir.startsWith("values")) {
                            if (file.extension == "xml") collectIds(file, ids)
                        } else {
                            // res/<type>[-qualifier]/<name>.<ext> → ID is (type, stem); the stem
                            // stops at the first '.' so foo.9.png collapses to foo.
                            ids.add("${dir.substringBefore('-')}/${file.name.substringBefore('.')}")
                        }
                    }
            }
        }
        return ids
    }

    private fun collectIds(file: Path, into: MutableSet<String>) {
        val doc = try {
            DocumentBuilderFactory.newInstance()
                .apply { isNamespaceAware = false }
                .newDocumentBuilder()
                .parse(file.toFile())
        } catch (t: Throwable) {
            // A mid-save half-written file can be unparseable; ignore this scan pass for it.
            System.err.println("could not parse ${file.fileName}: ${t.message}")
            return
        }
        val children = doc.documentElement?.childNodes ?: return
        for (i in 0 until children.length) {
            val node = children.item(i) as? Element ?: continue
            val name = node.getAttribute("name").ifEmpty { continue }
            // <item name= type=> declares an arbitrary type; every other tag IS the type.
            val type = if (node.tagName == "item") node.getAttribute("type").ifEmpty { "item" } else node.tagName
            into.add("$type/$name")
        }
    }
}
