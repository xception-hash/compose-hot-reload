package dev.hotreload.engine

import java.nio.charset.StandardCharsets
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertTrue
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes

class DexCompilerTest {

    @Test
    fun `desugars interface default helper call to installed companion owner`() {
        val root = Files.createTempDirectory("dex-compiler-classes-")
        try {
            val programClasses = root.resolve("program").createDirectories()
            val dependencyClasses = root.resolve("dependency").createDirectories()
            val owner = "dev/hotreload/engine/dexfixture/Defaults"
            writeInterface(dependencyClasses, owner)
            val caller = writeCaller(programClasses, owner)

            val dex = DexCompiler(d8(), minApi = 23, classpath = listOf(dependencyClasses)).dexOne(caller)
            val dexText = String(dex, StandardCharsets.ISO_8859_1)

            assertTrue(
                "L$owner\$-CC;" in dexText,
                "D8 did not rewrite the interface helper call to the APK's desugared companion",
            )
            assertTrue(classDefCount(dex) == 1, "patch dex must contain exactly one class definition")

            val interfaceOutput = DexCompiler(
                d8(),
                minApi = 23,
                classpath = listOf(dependencyClasses),
            ).dexOneOutput(dependencyClasses.resolve("$owner.class"))
            assertTrue(
                classDefCount(interfaceOutput.primaryDex) == 1,
                "D8 synthetics must stay outside the single-class redefine payload",
            )
            assertTrue(
                "dev.hotreload.engine.dexfixture.Defaults\$-CC" in interfaceOutput.syntheticDexes,
                "desugaring companion must be returned for injection as an auxiliary dex",
            )
            assertTrue(interfaceOutput.syntheticDexes.values.all { classDefCount(it) == 1 })
        } finally {
            Files.walk(root).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    private fun writeInterface(root: Path, owner: String) {
        val writer = ClassWriter(0)
        writer.visit(
            Opcodes.V1_8,
            Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT or Opcodes.ACC_INTERFACE,
            owner,
            null,
            "java/lang/Object",
            null,
        )
        writer.visitMethod(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
            "label\$default",
            "(Ljava/lang/String;)Ljava/lang/String;",
            null,
            null,
        ).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(1, 1)
            visitEnd()
        }
        writer.visitEnd()
        writeClass(root, owner, writer.toByteArray())
    }

    private fun writeCaller(root: Path, interfaceOwner: String): Path {
        val owner = "dev/hotreload/engine/dexfixture/Caller"
        val writer = ClassWriter(0)
        writer.visit(
            Opcodes.V1_8,
            Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL,
            owner,
            null,
            "java/lang/Object",
            null,
        )
        writer.visitMethod(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
            "label",
            "()Ljava/lang/String;",
            null,
            null,
        ).apply {
            visitCode()
            visitLdcInsn("patched")
            visitMethodInsn(
                Opcodes.INVOKESTATIC,
                interfaceOwner,
                "label\$default",
                "(Ljava/lang/String;)Ljava/lang/String;",
                true,
            )
            visitInsn(Opcodes.ARETURN)
            visitMaxs(1, 0)
            visitEnd()
        }
        writer.visitEnd()
        return writeClass(root, owner, writer.toByteArray())
    }

    private fun writeClass(root: Path, internalName: String, bytes: ByteArray): Path {
        val file = root.resolve("$internalName.class")
        file.parent.createDirectories()
        file.writeBytes(bytes)
        return file
    }

    private fun d8(): Path {
        val explicit = System.getenv("D8")?.takeIf { it.isNotBlank() }?.let(Path::of)
        if (explicit != null && Files.isExecutable(explicit)) return explicit
        val sdk = System.getenv("ANDROID_HOME")?.takeIf { it.isNotBlank() }
            ?: error("ANDROID_HOME (or D8) is required for DexCompilerTest")
        return Path.of(sdk, "build-tools", "36.0.0", "d8").also {
            check(Files.isExecutable(it)) { "pinned d8 is missing: $it" }
        }
    }

    private fun classDefCount(dex: ByteArray): Int =
        ByteBuffer.wrap(dex, 96, Int.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN).int
}
