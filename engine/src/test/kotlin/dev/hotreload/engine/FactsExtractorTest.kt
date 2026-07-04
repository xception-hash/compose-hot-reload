package dev.hotreload.engine

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FactsExtractorTest {

    /**
     * Generates a simple class with the given methods and fields using ASM ClassWriter.
     * Returns raw .class bytes.
     */
    private fun generateClass(
        name: String = "com/example/Test",
        superName: String = "java/lang/Object",
        methods: List<MethodSpec> = emptyList(),
        fields: List<FieldSpec> = emptyList(),
        addLineNumbers: Boolean = false,
        lineNumberStart: Int = 1,
    ): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES)
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER, name, null, superName, null)

        for (field in fields) {
            cw.visitField(field.access, field.name, field.desc, null, null).visitEnd()
        }

        for (method in methods) {
            val mv = cw.visitMethod(method.access, method.name, method.desc, null, null)

            // Add compose key annotation if specified
            if (method.composeKey != null) {
                val av = mv.visitAnnotation(
                    "Landroidx/compose/runtime/internal/FunctionKeyMeta;",
                    false, // invisible annotation (CLASS retention)
                )
                av.visit("key", method.composeKey)
                av.visit("startOffset", 0)
                av.visit("endOffset", 10)
                av.visitEnd()
            }

            if (!method.isAbstract) {
                mv.visitCode()

                if (addLineNumbers) {
                    val startLabel = org.objectweb.asm.Label()
                    mv.visitLabel(startLabel)
                    mv.visitLineNumber(lineNumberStart, startLabel)
                }

                // Method body: execute instructions
                for (insn in method.instructions) {
                    insn(mv)
                }

                mv.visitInsn(Opcodes.RETURN)
                mv.visitMaxs(1, 1)
            }
            mv.visitEnd()
        }

        cw.visitEnd()
        return cw.toByteArray()
    }

    private data class MethodSpec(
        val name: String,
        val desc: String,
        val access: Int = Opcodes.ACC_PUBLIC,
        val isAbstract: Boolean = false,
        val composeKey: Int? = null,
        val instructions: List<(org.objectweb.asm.MethodVisitor) -> Unit> = emptyList(),
    )

    private data class FieldSpec(
        val name: String,
        val desc: String,
        val access: Int = Opcodes.ACC_PRIVATE,
    )

    @Test
    fun `methods and fields appear with correct ids and access`() {
        val bytes = generateClass(
            methods = listOf(
                MethodSpec("foo", "(I)V", Opcodes.ACC_PUBLIC),
                MethodSpec("bar", "()Ljava/lang/String;", Opcodes.ACC_PRIVATE,
                    instructions = listOf { mv -> mv.visitInsn(Opcodes.ACONST_NULL) }),
            ),
            fields = listOf(
                FieldSpec("count", "I", Opcodes.ACC_PRIVATE),
                FieldSpec("name", "Ljava/lang/String;", Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL),
            ),
        )

        val facts = FactsExtractor.extract(bytes)
        assertEquals("com/example/Test", facts.internalName)
        assertEquals("java/lang/Object", facts.superName)

        val memberIds = facts.members.map { it.id }.toSet()
        assertTrue("foo(I)V" in memberIds, "method foo(I)V should be present")
        assertTrue("bar()Ljava/lang/String;" in memberIds, "method bar should be present")
        assertTrue("count:I" in memberIds, "field count:I should be present")
        assertTrue("name:Ljava/lang/String;" in memberIds, "field name should be present")

        // Check access values
        val fooMember = facts.members.first { it.id == "foo(I)V" }
        assertEquals(Opcodes.ACC_PUBLIC, fooMember.access)

        val countField = facts.members.first { it.id == "count:I" }
        assertEquals(Opcodes.ACC_PRIVATE, countField.access)

        // Fields should have null bodyHash
        assertNull(countField.bodyHash)
    }

    @Test
    fun `abstract method has null bodyHash`() {
        val bytes = generateClass(
            name = "com/example/AbstractClass",
            methods = listOf(
                MethodSpec("doStuff", "()V", Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT, isAbstract = true),
            ),
        )

        // Abstract class needs ACC_ABSTRACT
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT, "com/example/AbstractClass", null, "java/lang/Object", null)
        val mv = cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT, "doStuff", "()V", null, null)
        mv.visitEnd()
        cw.visitEnd()

        val facts = FactsExtractor.extract(cw.toByteArray())
        val doStuff = facts.members.first { it.id == "doStuff()V" }
        assertNull(doStuff.bodyHash, "abstract method should have null bodyHash")
    }

    @Test
    fun `compose key is extracted from invisible FunctionKeyMeta annotation`() {
        val bytes = generateClass(
            methods = listOf(
                MethodSpec("Counter", "(Landroidx/compose/runtime/Composer;I)V", Opcodes.ACC_PUBLIC,
                    composeKey = -96578675),
            ),
        )

        val facts = FactsExtractor.extract(bytes)
        val counter = facts.members.first { it.id == "Counter(Landroidx/compose/runtime/Composer;I)V" }
        assertEquals(-96578675, counter.composeKey)
    }

    @Test
    fun `unannotated method has null composeKey`() {
        val bytes = generateClass(
            methods = listOf(
                MethodSpec("helper", "()V"),
            ),
        )

        val facts = FactsExtractor.extract(bytes)
        val helper = facts.members.first { it.id == "helper()V" }
        assertNull(helper.composeKey, "unannotated method should have null composeKey")
    }

    @Test
    fun `identical classes differing only in LineNumberTable produce equal ClassFacts`() {
        val class1 = generateClass(
            methods = listOf(
                MethodSpec("foo", "()V", instructions = listOf { mv ->
                    mv.visitLdcInsn(42)
                    mv.visitInsn(Opcodes.POP)
                }),
            ),
            addLineNumbers = true,
            lineNumberStart = 10,
        )

        val class2 = generateClass(
            methods = listOf(
                MethodSpec("foo", "()V", instructions = listOf { mv ->
                    mv.visitLdcInsn(42)
                    mv.visitInsn(Opcodes.POP)
                }),
            ),
            addLineNumbers = true,
            lineNumberStart = 99,
        )

        val facts1 = FactsExtractor.extract(class1)
        val facts2 = FactsExtractor.extract(class2)

        assertEquals(facts1, facts2, "ClassFacts should be equal when only line numbers differ")
    }

    @Test
    fun `classes differing in one method's instructions have different bodyHash for that method only`() {
        val class1 = generateClass(
            methods = listOf(
                MethodSpec("unchanged", "()V", instructions = listOf { mv ->
                    mv.visitLdcInsn(1)
                    mv.visitInsn(Opcodes.POP)
                }),
                MethodSpec("changed", "()V", instructions = listOf { mv ->
                    mv.visitLdcInsn(100)
                    mv.visitInsn(Opcodes.POP)
                }),
            ),
        )

        val class2 = generateClass(
            methods = listOf(
                MethodSpec("unchanged", "()V", instructions = listOf { mv ->
                    mv.visitLdcInsn(1)
                    mv.visitInsn(Opcodes.POP)
                }),
                MethodSpec("changed", "()V", instructions = listOf { mv ->
                    mv.visitLdcInsn(999)
                    mv.visitInsn(Opcodes.POP)
                }),
            ),
        )

        val facts1 = FactsExtractor.extract(class1)
        val facts2 = FactsExtractor.extract(class2)

        val unchanged1 = facts1.members.first { it.id == "unchanged()V" }
        val unchanged2 = facts2.members.first { it.id == "unchanged()V" }
        assertEquals(unchanged1.bodyHash, unchanged2.bodyHash, "unchanged method's bodyHash should be equal")

        val changed1 = facts1.members.first { it.id == "changed()V" }
        val changed2 = facts2.members.first { it.id == "changed()V" }
        assertNotEquals(changed1.bodyHash, changed2.bodyHash, "changed method's bodyHash should differ")
    }
}
