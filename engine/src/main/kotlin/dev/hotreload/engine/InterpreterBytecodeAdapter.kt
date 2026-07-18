package dev.hotreload.engine

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * Aligns raw JVM bytecode sent to the interpreter with the installed APK's D8 ABI.
 *
 * Below API 24, D8 moves static interface methods (including Kotlin `$default` helpers) to the
 * generated `$-CC` companion. Compiled patches already receive this rewrite in [DexCompiler], but
 * interpreted methods execute their JVM instructions directly and therefore need the equivalent
 * owner rewrite before they are sent to the device.
 */
internal object InterpreterBytecodeAdapter {

    fun adapt(classBytes: ByteArray, minApi: Int): ByteArray {
        require(minApi > 0) { "minApi must be positive: $minApi" }
        if (minApi >= 24) return classBytes

        val reader = ClassReader(classBytes)
        val writer = ClassWriter(reader, 0)
        reader.accept(
            object : ClassVisitor(Opcodes.ASM9, writer) {
                override fun visitMethod(
                    access: Int,
                    name: String?,
                    descriptor: String?,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor {
                    val delegate = super.visitMethod(access, name, descriptor, signature, exceptions)
                    return object : MethodVisitor(Opcodes.ASM9, delegate) {
                        override fun visitMethodInsn(
                            opcode: Int,
                            owner: String,
                            methodName: String,
                            methodDescriptor: String,
                            isInterface: Boolean,
                        ) {
                            if (opcode == Opcodes.INVOKESTATIC && isInterface) {
                                super.visitMethodInsn(
                                    opcode,
                                    "$owner\$-CC",
                                    methodName,
                                    methodDescriptor,
                                    false,
                                )
                            } else {
                                super.visitMethodInsn(opcode, owner, methodName, methodDescriptor, isInterface)
                            }
                        }
                    }
                }
            },
            0,
        )
        return writer.toByteArray()
    }
}
