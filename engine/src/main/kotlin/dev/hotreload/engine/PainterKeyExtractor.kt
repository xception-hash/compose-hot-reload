package dev.hotreload.engine

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

/**
 * Extracts the Compose-compiler group key of `painterResource`'s bitmap branch — the
 * `startReplaceGroup(<key>)` in `androidx.compose.ui.res.PainterResources_androidKt`
 * that wraps `remember(path, id, theme) { loadImageBitmapResource(...) }`.
 *
 * Why: a bitmap (png/webp/jpg/jpeg) overlay swap leaves that `remember` stale. Its keys are the
 * *intra-APK* file path string (identical in every overlay we build), the resource id,
 * and the theme — none change when an overlay lands, so no amount of recomposition ever
 * re-decodes the bitmap. `invalidateGroupsWithKey(<key>)` "bashes" exactly the matching
 * groups (rewrites their key so the next recomposition rebuilds them from scratch,
 * discarding the remembered ImageBitmap) at every painterResource call site. The group
 * contains nothing but the painter computation, so user state elsewhere is untouched.
 *
 * The key is a per-ui-version compiler constant, so it is read from the dex actually
 * shipped in the app's APK (version-proof, no gradle-cache guessing) via `dexdump`,
 * which sits next to `d8` in build-tools.
 */
object PainterKeyExtractor {
    private const val CLASS_DESCRIPTOR = "'Landroidx/compose/ui/res/PainterResources_androidKt;'"
    private const val START_GROUP = "Landroidx/compose/runtime/Composer;.startReplaceGroup:(I)V"
    private const val DECODE_CALL = "PainterResources_androidKt;.loadImageBitmapResource:"

    /**
     * Only full-width `const vN` can hold a 32-bit compiler group key; the narrow forms
     * (`const/4`, `const/16`, `const/high16`, `const-string`, ...) never feed one.
     */
    private val CONST = Regex("""\bconst v(\d+), .* // #([0-9a-f]{1,8})$""")
    private val INVOKE_START_GROUP = Regex("""invoke-interface \{v\d+, v(\d+)}""")

    /**
     * The bitmap-branch group key, or null when it can't be determined (class not in the
     * APK, dexdump failed, or the ui bytecode shape changed). Callers degrade gracefully.
     */
    fun extract(apk: Path, dexdump: Path): Int? {
        val tmp = Files.createTempDirectory("hotreload-dexdump")
        try {
            // The defining dex is the only one whose string pool holds the *private*
            // method name — a byte search picks it without disassembling every dex.
            val needle = "loadImageBitmapResource".toByteArray(Charsets.US_ASCII)
            val candidates = mutableListOf<Path>()
            ZipFile(apk.toFile()).use { zip ->
                for (entry in zip.entries().asSequence()) {
                    if (!entry.name.matches(Regex("""classes\d*\.dex"""))) continue
                    val out = tmp.resolve(entry.name)
                    zip.getInputStream(entry).use { Files.copy(it, out) }
                    if (contains(Files.readAllBytes(out), needle)) candidates.add(out)
                }
            }
            return candidates.firstNotNullOfOrNull { scan(it, dexdump) }
        } catch (t: Throwable) {
            System.err.println("painterResource key extraction failed: ${t.message}")
            return null
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    /**
     * Streams `dexdump -d` and returns the argument of the last `startReplaceGroup` that
     * precedes the `loadImageBitmapResource` call inside PainterResources_androidKt —
     * i.e. the group the bitmap branch opens before remembering the decoded bitmap.
     */
    private fun scan(dex: Path, dexdump: Path): Int? {
        val proc = ProcessBuilder(dexdump.toString(), "-d", dex.toString())
            .redirectErrorStream(true)
            .start()
        try {
            proc.inputStream.bufferedReader().useLines { lines ->
                var inClass = false
                val consts = HashMap<Int, Int>()
                var lastGroupKey: Int? = null
                for (line in lines) {
                    if ("Class descriptor" in line) {
                        if (inClass) return null // left the class without hitting the call
                        inClass = CLASS_DESCRIPTOR in line
                        continue
                    }
                    if (!inClass) continue
                    val const = CONST.find(line)
                    if (const != null) {
                        consts[const.groupValues[1].toInt()] = const.groupValues[2].toUInt(16).toInt()
                        continue
                    }
                    if (START_GROUP in line) {
                        val reg = INVOKE_START_GROUP.find(line)?.groupValues?.get(1)?.toInt()
                        lastGroupKey = consts[reg] ?: lastGroupKey
                        continue
                    }
                    if (DECODE_CALL in line && "invoke-static" in line) return lastGroupKey
                }
            }
            return null
        } finally {
            proc.destroyForcibly()
        }
    }

    private fun contains(haystack: ByteArray, needle: ByteArray): Boolean {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return true
        }
        return false
    }
}
