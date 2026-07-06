package dev.hotreload.engine

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldNode
import org.objectweb.asm.tree.InsnList
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.IntInsnNode
import org.objectweb.asm.tree.JumpInsnNode
import org.objectweb.asm.tree.LabelNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.TypeInsnNode
import org.objectweb.asm.tree.VarInsnNode

/**
 * Host-side (JVM-bytecode) port of AOSP's dex-level `stub_transform.cc`. Rewrites a class's
 * `.class` bytes so that, once the class is structurally redefined on device and the interpreter
 * dex is present, every method's entry can be diverted into the LiveEdit bytecode interpreter.
 *
 * The transform, per AOSP semantics ([third_party/.../transform/stub_transform.cc]):
 *  - adds `public Object $liveEditBytecode` to every non-interface class;
 *  - prepends to every non-`<clinit>`, non-`<init>`, non-abstract/native method the prologue:
 *      ```
 *      Object bc = LiveEditStubs.getClassBytecode("pkg/Cls");        // static
 *      Object bc = LiveEditStubs.getInstanceBytecode("pkg/Cls", this); // instance
 *      if (bc != null)
 *          return LiveEditStubs.stub<T>(bc, "name", "(desc)T", new Object[]{null, this|null, args…});
 *      // else fall through to the original body
 *      ```
 *    `stub<T>` is picked by the method's return descriptor (`stubV/Z/B/C/S/I/J/F/D/L`); reference
 *    returns come back as `Object` and are `checkcast`-ed to the declared type.
 *  - appends `LiveEditStubs.stubConstructor("pkg/Cls", this)` before each RETURN of every `<init>`.
 *
 * Unlike AOSP (which runs this on-device in dex via slicer), we own the original `.class` files and
 * run it host-side in ASM, then d8 → structural redefine — the path already proven for our engine.
 *
 * The `doStub` params array layout is `[null, this|null, arg0, arg1, …]` (params[1] = receiver,
 * params[2..] = boxed arguments) — byte-identical to what the AOSP interpreter unpacks.
 */
object StubTransform {

    private const val STUBS = "com/android/tools/deploy/liveedit/LiveEditStubs"
    private const val BYTECODE_FIELD = "\$liveEditBytecode"
    private const val OBJECT = "java/lang/Object"

    /**
     * @param classBytes baseline `.class` bytes to prime.
     * @param frameLoader classloader used only for [ClassWriter] stack-map frame computation
     *   (common-superclass resolution). Must be able to load the class being transformed and any
     *   types its method bodies reference (i.e. the module's compile+runtime classpath). Defaults
     *   to this class's loader, which is enough for self-contained fixtures in tests.
     * @return transformed `.class` bytes with valid stack-map frames.
     */
    fun transform(
        classBytes: ByteArray,
        frameLoader: ClassLoader = StubTransform::class.java.classLoader,
    ): ByteArray {
        val node = ClassNode()
        ClassReader(classBytes).accept(node, 0)

        val isInterface = node.access and Opcodes.ACC_INTERFACE != 0
        // Interfaces can't hold instance fields and don't carry primable composable bodies; leave
        // them untouched (the engine never primes an interface).
        if (isInterface) return classBytes

        addBytecodeField(node)

        val owner = node.name
        for (method in node.methods ?: emptyList()) {
            if (method.name == "<clinit>") continue
            if (method.access and (Opcodes.ACC_ABSTRACT or Opcodes.ACC_NATIVE) != 0) continue

            if (method.name == "<init>") {
                appendConstructorTail(owner, method)
            } else {
                prependStubPrologue(owner, method)
            }
        }

        // COMPUTE_FRAMES also recomputes maxStack/maxLocals and ignores any pre-existing frames.
        val writer = FrameClassWriter(ClassWriter.COMPUTE_FRAMES, frameLoader)
        node.accept(writer)
        return writer.toByteArray()
    }

    private fun addBytecodeField(node: ClassNode) {
        val existing = node.fields?.any { it.name == BYTECODE_FIELD } ?: false
        if (existing) return
        if (node.fields == null) node.fields = mutableListOf()
        node.fields.add(FieldNode(Opcodes.ACC_PUBLIC, BYTECODE_FIELD, "L$OBJECT;", null, null))
    }

    private fun prependStubPrologue(owner: String, method: MethodNode) {
        val isStatic = method.access and Opcodes.ACC_STATIC != 0
        val argTypes = Type.getArgumentTypes(method.desc)
        val returnType = Type.getReturnType(method.desc)
        // A slot past every original local is guaranteed collision-free; COMPUTE_FRAMES recomputes
        // maxLocals regardless.
        val bcSlot = method.maxLocals

        val p = InsnList()
        val original = LabelNode()

        // Object bc = getClassBytecode(owner) | getInstanceBytecode(owner, this)
        p.add(LdcInsnNode(owner))
        if (isStatic) {
            p.add(MethodInsnNode(Opcodes.INVOKESTATIC, STUBS, "getClassBytecode",
                "(Ljava/lang/String;)L$OBJECT;", false))
        } else {
            p.add(VarInsnNode(Opcodes.ALOAD, 0))
            p.add(MethodInsnNode(Opcodes.INVOKESTATIC, STUBS, "getInstanceBytecode",
                "(Ljava/lang/String;L$OBJECT;)L$OBJECT;", false))
        }
        p.add(VarInsnNode(Opcodes.ASTORE, bcSlot))
        p.add(VarInsnNode(Opcodes.ALOAD, bcSlot))
        p.add(JumpInsnNode(Opcodes.IFNULL, original))

        // return stub<T>(bc, name, desc, new Object[]{null, this|null, args…});
        p.add(VarInsnNode(Opcodes.ALOAD, bcSlot))
        p.add(LdcInsnNode(method.name))
        p.add(LdcInsnNode(method.desc))
        buildParamsArray(p, isStatic, argTypes)
        emitStubCallAndReturn(p, returnType)

        // Fall-through target for the null case: the original body follows this label.
        p.add(original)

        method.instructions.insert(p)
    }

    /** Pushes `new Object[]{null, this|null, boxed args…}` onto the stack. */
    private fun buildParamsArray(p: InsnList, isStatic: Boolean, argTypes: Array<Type>) {
        val n = 2 + argTypes.size
        pushInt(p, n)
        p.add(TypeInsnNode(Opcodes.ANEWARRAY, OBJECT))

        // [0] = null (unused padding slot the interpreter expects)
        p.add(InsnNode(Opcodes.DUP)); pushInt(p, 0); p.add(InsnNode(Opcodes.ACONST_NULL)); p.add(InsnNode(Opcodes.AASTORE))
        // [1] = receiver (this) or null for static
        p.add(InsnNode(Opcodes.DUP)); pushInt(p, 1)
        if (isStatic) p.add(InsnNode(Opcodes.ACONST_NULL)) else p.add(VarInsnNode(Opcodes.ALOAD, 0))
        p.add(InsnNode(Opcodes.AASTORE))

        // [2 + i] = boxed argument i, read from its local slot
        var slot = if (isStatic) 0 else 1
        for (i in argTypes.indices) {
            val t = argTypes[i]
            p.add(InsnNode(Opcodes.DUP)); pushInt(p, 2 + i)
            p.add(VarInsnNode(t.getOpcode(Opcodes.ILOAD), slot))
            box(p, t)
            p.add(InsnNode(Opcodes.AASTORE))
            slot += t.size
        }
    }

    /** Emits the `stub<T>` call for [returnType] plus the matching return (checkcast for refs). */
    private fun emitStubCallAndReturn(p: InsnList, returnType: Type) {
        val argsDesc = "(L$OBJECT;Ljava/lang/String;Ljava/lang/String;[L$OBJECT;)"
        when (returnType.sort) {
            Type.VOID -> {
                p.add(MethodInsnNode(Opcodes.INVOKESTATIC, STUBS, "stubV", "${argsDesc}V", false))
                p.add(InsnNode(Opcodes.RETURN))
            }
            Type.OBJECT, Type.ARRAY -> {
                p.add(MethodInsnNode(Opcodes.INVOKESTATIC, STUBS, "stubL", "${argsDesc}L$OBJECT;", false))
                p.add(TypeInsnNode(Opcodes.CHECKCAST, returnType.internalName))
                p.add(InsnNode(Opcodes.ARETURN))
            }
            else -> {
                // Z/C/B/S/I/J/F/D → stub<char>, primitive return, matching x-return opcode.
                val d = returnType.descriptor // single char for primitives
                p.add(MethodInsnNode(Opcodes.INVOKESTATIC, STUBS, "stub$d", "$argsDesc$d", false))
                p.add(InsnNode(returnType.getOpcode(Opcodes.IRETURN)))
            }
        }
    }

    private fun appendConstructorTail(owner: String, method: MethodNode) {
        // Insert stubConstructor(owner, this) before every RETURN (a <init> is always void).
        val returns = mutableListOf<AbstractInsnNode>()
        var insn = method.instructions.first
        while (insn != null) {
            if (insn.opcode == Opcodes.RETURN) returns.add(insn)
            insn = insn.next
        }
        for (ret in returns) {
            val tail = InsnList()
            tail.add(LdcInsnNode(owner))
            tail.add(VarInsnNode(Opcodes.ALOAD, 0))
            tail.add(MethodInsnNode(Opcodes.INVOKESTATIC, STUBS, "stubConstructor",
                "(Ljava/lang/String;L$OBJECT;)V", false))
            method.instructions.insertBefore(ret, tail)
        }
    }

    private fun box(p: InsnList, t: Type) {
        val owner = when (t.sort) {
            Type.BOOLEAN -> "java/lang/Boolean"
            Type.CHAR -> "java/lang/Character"
            Type.BYTE -> "java/lang/Byte"
            Type.SHORT -> "java/lang/Short"
            Type.INT -> "java/lang/Integer"
            Type.FLOAT -> "java/lang/Float"
            Type.LONG -> "java/lang/Long"
            Type.DOUBLE -> "java/lang/Double"
            else -> return // OBJECT/ARRAY: already a reference
        }
        p.add(MethodInsnNode(Opcodes.INVOKESTATIC, owner, "valueOf", "(${t.descriptor})L$owner;", false))
    }

    private fun pushInt(p: InsnList, value: Int) {
        when {
            value in -1..5 -> p.add(InsnNode(Opcodes.ICONST_0 + value))
            value in Byte.MIN_VALUE..Byte.MAX_VALUE -> p.add(IntInsnNode(Opcodes.BIPUSH, value))
            value in Short.MIN_VALUE..Short.MAX_VALUE -> p.add(IntInsnNode(Opcodes.SIPUSH, value))
            else -> p.add(LdcInsnNode(value))
        }
    }

    /**
     * [ClassWriter] whose common-superclass resolution uses [loader] instead of the writer's own
     * classloader, so frames can be computed for app/framework types not on the engine's classpath.
     * Falls back to `java/lang/Object` when a type can't be resolved — an imperfect frame there is
     * caught by the on-device (and, in tests, the host JVM) verifier rather than passing silently.
     */
    private class FrameClassWriter(flags: Int, private val loader: ClassLoader) : ClassWriter(flags) {
        override fun getClassLoader(): ClassLoader = loader

        override fun getCommonSuperClass(type1: String, type2: String): String {
            return try {
                val c1 = Class.forName(type1.replace('/', '.'), false, loader)
                val c2 = Class.forName(type2.replace('/', '.'), false, loader)
                when {
                    c1.isAssignableFrom(c2) -> type1
                    c2.isAssignableFrom(c1) -> type2
                    c1.isInterface || c2.isInterface -> OBJECT
                    else -> {
                        var c = c1
                        do { c = c.superclass } while (!c.isAssignableFrom(c2))
                        c.name.replace('.', '/')
                    }
                }
            } catch (_: Throwable) {
                OBJECT
            }
        }
    }
}
