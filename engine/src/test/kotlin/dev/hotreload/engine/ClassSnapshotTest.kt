package dev.hotreload.engine

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ClassSnapshotTest {

    private fun generateClass(name: String, bodyValue: Int = 42): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES)
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER, name, null, "java/lang/Object", null)
        val mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "foo", "()V", null, null)
        mv.visitCode()
        mv.visitLdcInsn(bodyValue)
        mv.visitInsn(Opcodes.POP)
        mv.visitInsn(Opcodes.RETURN)
        mv.visitMaxs(1, 1)
        mv.visitEnd()
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun writeClass(root: Path, internalName: String, bytes: ByteArray): Path {
        val file = root.resolve("$internalName.class")
        file.parent.createDirectories()
        file.writeBytes(bytes)
        return file
    }

    @Test
    fun `scan finds classes and keys by internal name`() {
        val tmpDir = Files.createTempDirectory("snapshot-test-")
        try {
            val bytes1 = generateClass("com/example/Foo")
            val bytes2 = generateClass("com/example/Bar")
            writeClass(tmpDir, "com/example/Foo", bytes1)
            writeClass(tmpDir, "com/example/Bar", bytes2)

            val snapshot = ClassSnapshot.scan(tmpDir)
            assertEquals(2, snapshot.size)
            assertTrue("com/example/Foo" in snapshot)
            assertTrue("com/example/Bar" in snapshot)
        } finally {
            Files.walk(tmpDir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    @Test
    fun `byte change flips only that entry's contentHash`() {
        val tmpDir = Files.createTempDirectory("snapshot-test-")
        try {
            val fooBytes = generateClass("com/example/Foo", bodyValue = 1)
            val barBytes = generateClass("com/example/Bar", bodyValue = 2)
            writeClass(tmpDir, "com/example/Foo", fooBytes)
            writeClass(tmpDir, "com/example/Bar", barBytes)

            val snapshot1 = ClassSnapshot.scan(tmpDir)
            val fooHash1 = snapshot1.getValue("com/example/Foo").contentHash
            val barHash1 = snapshot1.getValue("com/example/Bar").contentHash

            // Modify only Foo
            val fooBytes2 = generateClass("com/example/Foo", bodyValue = 999)
            writeClass(tmpDir, "com/example/Foo", fooBytes2)

            val snapshot2 = ClassSnapshot.scan(tmpDir)
            val fooHash2 = snapshot2.getValue("com/example/Foo").contentHash
            val barHash2 = snapshot2.getValue("com/example/Bar").contentHash

            assertNotEquals(fooHash1, fooHash2, "Foo's contentHash should change")
            assertEquals(barHash1, barHash2, "Bar's contentHash should be unchanged")
        } finally {
            Files.walk(tmpDir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    @Test
    fun `missing directory returns empty map`() {
        val nonExistent = Path.of("/tmp/definitely-does-not-exist-${System.nanoTime()}")
        val snapshot = ClassSnapshot.scan(nonExistent)
        assertTrue(snapshot.isEmpty(), "missing dir should produce empty map")
    }
}
