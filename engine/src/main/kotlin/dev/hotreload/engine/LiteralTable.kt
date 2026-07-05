package dev.hotreload.engine

import dev.hotreload.protocol.LiteralType
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Type
import org.objectweb.asm.tree.ClassNode
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension

/**
 * One live-literal, extracted from a compiled `LiveLiterals$<File>Kt` helper (T24).
 * [key] is the `@LiveLiteralInfo(key=…)` string handed verbatim to the runtime's
 * `updateLiveLiteralValue`; [offset] is the 0-based source char offset of the literal
 * token's first character (the opening quote for strings, the first digit for numbers);
 * [type] is the [LiteralType] tag derived from the getter's return descriptor;
 * [helperClass] is the owning `LiveLiterals$*Kt` FQCN, whose per-file `enabled` flag the
 * runtime must set (live-literals v2 gates each getter on that flag, not just the global).
 */
data class LiteralEntry(val key: String, val offset: Int, val type: Int, val helperClass: String)

/**
 * Per-source-file map of live literals, built by scanning the compiled `LiveLiterals$*Kt`
 * helper classes the Compose compiler emits when `-Photreload.liveLiterals=true` is set.
 *
 * We NEVER re-derive Compose's key-naming algorithm: the generated helpers already carry
 * `@LiveLiteralInfo(key, offset)` on each literal getter, so the source offset is the
 * lookup and the key is opaque. Files are keyed by their source-root-relative path
 * (e.g. `dev/hotreload/sample/MainActivity.kt`), derived from the helper's internal name
 * `dev/hotreload/sample/LiveLiterals$MainActivityKt`.
 */
class LiteralTable private constructor(private val byFile: Map<String, List<LiteralEntry>>) {

    val isEmpty: Boolean get() = byFile.isEmpty()

    /** Entries for the given source-root-relative path (e.g. `pkg/dir/File.kt`), or empty. */
    fun entriesFor(sourceRelativePath: String): List<LiteralEntry> =
        byFile[sourceRelativePath.replace('\\', '/')] ?: emptyList()

    companion object {
        private const val LIVE_LITERAL_INFO_DESC = "Landroidx/compose/runtime/internal/LiveLiteralInfo;"
        private const val HELPER_PREFIX = "LiveLiterals\$"

        fun empty(): LiteralTable = LiteralTable(emptyMap())

        /** Walk all `LiveLiterals$*Kt` class files under [classesDirs] and build the table. */
        fun scan(classesDirs: List<Path>): LiteralTable {
            val byFile = HashMap<String, MutableList<LiteralEntry>>()
            for (dir in classesDirs) {
                if (!Files.isDirectory(dir)) continue
                Files.walk(dir).use { paths ->
                    paths.filter { it.extension == "class" && it.fileName.toString().startsWith(HELPER_PREFIX) }
                        .forEach { file ->
                            val entries = extract(Files.readAllBytes(file)) ?: return@forEach
                            val key = entries.first
                            if (entries.second.isNotEmpty()) {
                                byFile.getOrPut(key) { mutableListOf() }.addAll(entries.second)
                            }
                        }
                }
            }
            return LiteralTable(byFile)
        }

        /** (source-relative-path, entries) for one helper class, or null if it isn't one. */
        private fun extract(classBytes: ByteArray): Pair<String, List<LiteralEntry>>? {
            val node = ClassNode()
            ClassReader(classBytes).accept(node, ClassReader.SKIP_CODE or ClassReader.SKIP_FRAMES)
            val internalName = node.name ?: return null
            val simple = internalName.substringAfterLast('/')
            if (!simple.startsWith(HELPER_PREFIX)) return null
            val helperClass = internalName.replace('/', '.')

            val entries = ArrayList<LiteralEntry>()
            for (method in node.methods ?: emptyList()) {
                val ann = method.visibleAnnotations?.firstOrNull { it.desc == LIVE_LITERAL_INFO_DESC } ?: continue
                var key: String? = null
                var offset: Int? = null
                val values = ann.values ?: continue
                for (i in values.indices step 2) {
                    when (values[i]) {
                        "key" -> key = values[i + 1] as? String
                        "offset" -> offset = values[i + 1] as? Int
                    }
                }
                if (key == null || offset == null) continue
                val type = literalTypeOf(Type.getReturnType(method.desc)) ?: continue
                entries += LiteralEntry(key, offset, type, helperClass)
            }

            // pkg/dir/LiveLiterals$MainActivityKt -> pkg/dir/MainActivity.kt
            val pkgDir = internalName.substringBeforeLast('/', "")
            val fileBase = simple.removePrefix(HELPER_PREFIX).removeSuffix("Kt")
            val sourcePath = if (pkgDir.isEmpty()) "$fileBase.kt" else "$pkgDir/$fileBase.kt"
            return sourcePath to entries
        }

        /** Map a getter return type to a [LiteralType] tag, or null for unsupported types. */
        private fun literalTypeOf(returnType: Type): Int? = when (returnType.descriptor) {
            "Ljava/lang/String;" -> LiteralType.STRING
            "I" -> LiteralType.INT
            "J" -> LiteralType.LONG
            "F" -> LiteralType.FLOAT
            "D" -> LiteralType.DOUBLE
            "Z" -> LiteralType.BOOLEAN
            "C" -> LiteralType.CHAR
            else -> null
        }
    }
}
