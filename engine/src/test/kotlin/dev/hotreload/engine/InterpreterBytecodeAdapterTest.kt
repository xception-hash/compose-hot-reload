package dev.hotreload.engine

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

class InterpreterBytecodeAdapterTest {

    @Test
    fun `rewrites static interface calls to the desugared companion below API 24`() {
        val adapted = InterpreterBytecodeAdapter.adapt(fixtureClass(), minApi = 23)

        assertEquals(
            listOf(
                Call(Opcodes.INVOKESTATIC, "fixture/Defaults\$-CC", "item\$default", false),
                Call(Opcodes.INVOKESTATIC, "fixture/Helpers", "label", false),
                Call(Opcodes.INVOKEINTERFACE, "fixture/Defaults", "item", true),
            ),
            callsIn(adapted),
        )
    }

    @Test
    fun `keeps JVM interface owners at API 24 and above`() {
        val original = fixtureClass()

        assertContentEquals(original, InterpreterBytecodeAdapter.adapt(original, minApi = 24))
    }

    private fun fixtureClass(): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "fixture/Caller", null, "java/lang/Object", null)
        writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "call", "(Lfixture/Defaults;)V", null, null).apply {
            visitCode()
            visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "fixture/Defaults",
                "item\$default",
                "()V",
                true,
            )
            visitMethodInsn(Opcodes.INVOKESTATIC, "fixture/Helpers", "label", "()V", false)
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKEINTERFACE, "fixture/Defaults", "item", "()V", true)
            visitInsn(Opcodes.RETURN)
            visitMaxs(1, 1)
            visitEnd()
        }
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun callsIn(classBytes: ByteArray): List<Call> {
        val calls = mutableListOf<Call>()
        ClassReader(classBytes).accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(
                    access: Int,
                    name: String?,
                    descriptor: String?,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor = object : MethodVisitor(Opcodes.ASM9) {
                    override fun visitMethodInsn(
                        opcode: Int,
                        owner: String,
                        name: String,
                        descriptor: String,
                        isInterface: Boolean,
                    ) {
                        calls += Call(opcode, owner, name, isInterface)
                    }
                }
            },
            ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
        )
        return calls
    }

    private data class Call(val opcode: Int, val owner: String, val name: String, val isInterface: Boolean)
}
