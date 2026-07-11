package dev.hotreload.engine

import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.util.Textifier
import org.objectweb.asm.util.TraceMethodVisitor
import java.io.PrintWriter
import java.io.StringWriter
import java.security.MessageDigest

/**
 * Extracts [ClassFacts] from raw `.class` file bytes using ASM.
 */
object FactsExtractor {

    private const val FUNCTION_KEY_META_DESC = "Landroidx/compose/runtime/internal/FunctionKeyMeta;"

    fun extract(classBytes: ByteArray): ClassFacts {
        val node = ClassNode()
        ClassReader(classBytes).accept(node, ClassReader.SKIP_DEBUG)

        val members = mutableSetOf<MemberFacts>()
        val monitorMethods = mutableSetOf<String>()

        // Methods
        for (method in node.methods ?: emptyList()) {
            val id = "${method.name}${method.desc}"
            val bodyHash = computeBodyHash(method)
            val composeKey = extractComposeKey(method)
            members += MemberFacts(id, method.access, bodyHash, composeKey)
            if (method.instructions?.any { it.opcode == Opcodes.MONITORENTER } == true) {
                monitorMethods += id
            }
        }

        // Fields
        for (field in node.fields ?: emptyList()) {
            val id = "${field.name}:${field.desc}"
            members += MemberFacts(id, field.access, null, null)
        }

        return ClassFacts(
            internalName = node.name,
            superName = node.superName,
            interfaces = node.interfaces?.toSet() ?: emptySet(),
            access = node.access,
            members = members,
            monitorMethods = monitorMethods,
        )
    }

    private fun computeBodyHash(method: MethodNode): Long? {
        if (method.instructions == null || method.instructions.size() == 0) return null

        val textifier = Textifier()
        val visitor = TraceMethodVisitor(textifier)
        method.instructions.accept(visitor)

        val sw = StringWriter()
        textifier.print(PrintWriter(sw))
        val text = sw.toString()

        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(text.toByteArray(Charsets.UTF_8))

        // First 8 bytes as a Long (big-endian)
        var result = 0L
        for (i in 0 until 8) {
            result = (result shl 8) or (hash[i].toLong() and 0xFF)
        }
        return result
    }

    private fun extractComposeKey(method: MethodNode): Int? {
        // Check invisible annotations (CLASS retention)
        val annotations = method.invisibleAnnotations ?: return null
        for (ann in annotations) {
            if (ann.desc == FUNCTION_KEY_META_DESC) {
                val values = ann.values ?: continue
                for (i in values.indices step 2) {
                    if (values[i] == "key") {
                        return values[i + 1] as? Int
                    }
                }
            }
        }
        return null
    }
}
