package dev.hotreload.engine

import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodInsnNode
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

        // Compose emits source-location calls into every affected enclosing lambda. A leaf edit
        // changes their marker text and group-id operands even when the enclosing lambda's
        // executable code is unchanged. Hashing those compiler-debug operands made us redefine
        // and invalidate parent groups, which resets their remembered state. Copy before
        // normalization so the facts used for annotations and monitor detection remain raw.
        val normalized = MethodNode(
            method.access,
            method.name,
            method.desc,
            method.signature,
            method.exceptions?.toTypedArray(),
        )
        method.accept(normalized)
        normalizeComposeSourceMarkers(normalized)

        val textifier = Textifier()
        val visitor = TraceMethodVisitor(textifier)
        normalized.instructions.accept(visitor)

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

    /**
     * Make Compose's compiler-only source locations hash-stable without masking user literals.
     *
     * `sourceInformation(composer, text)` and
     * `sourceInformationMarkerStart(composer, key, text)` are no-op debugging hooks. Their
     * changing operands describe offsets in the source file, not runtime behavior. We normalize
     * only the immediate constants passed to those exact calls; every other instruction,
     * including the text literal rendered by a composable, remains part of the hash.
     */
    private fun normalizeComposeSourceMarkers(method: MethodNode) {
        for (instruction in method.instructions.toArray()) {
            val call = instruction as? MethodInsnNode ?: continue
            if (call.owner != COMPOSER_KT) continue
            when (call.name) {
                "sourceInformation" -> {
                    val text = previousRealInstruction(call) as? LdcInsnNode ?: continue
                    if (text.cst is String) text.cst = COMPOSE_SOURCE_TEXT
                }
                "sourceInformationMarkerStart" -> {
                    val text = previousRealInstruction(call) as? LdcInsnNode ?: continue
                    if (text.cst !is String) continue
                    text.cst = COMPOSE_SOURCE_TEXT
                    val key = previousRealInstruction(text) as? LdcInsnNode ?: continue
                    if (key.cst is Number) key.cst = 0
                }
            }
        }
    }

    private fun previousRealInstruction(instruction: AbstractInsnNode): AbstractInsnNode? {
        var previous = instruction.previous
        while (previous != null && previous.opcode < 0) previous = previous.previous
        return previous
    }

    private fun extractComposeKey(method: MethodNode): Int? {
        // Compose runtime versions have emitted FunctionKeyMeta with both CLASS and RUNTIME
        // retention. ASM exposes those as invisible and visible annotations respectively.
        val annotations = buildList {
            method.invisibleAnnotations?.let(::addAll)
            method.visibleAnnotations?.let(::addAll)
        }
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

    private const val COMPOSER_KT = "androidx/compose/runtime/ComposerKt"
    private const val COMPOSE_SOURCE_TEXT = "<hotreload-compose-source>"
}
