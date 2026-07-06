package dev.hotreload.engine

import com.android.tools.deploy.liveedit.LiveEditStubs
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Verifies [StubTransform] against the [dev.hotreload.engine.stubfixture.Fixture] compiled on the
 * test classpath: reads its `.class` bytes, transforms them, loads the result in a child
 * classloader that links against the fake [LiveEditStubs] recorder, then drives the transformed
 * methods and asserts the prologue behavior. Loading the transformed class also runs the JVM
 * verifier, so any malformed frame/bytecode fails the test with a VerifyError.
 */
class StubTransformTest {

    private val fixtureName = "dev.hotreload.engine.stubfixture.Fixture"
    private val fixtureInternal = "dev/hotreload/engine/stubfixture/Fixture"

    private lateinit var cls: Class<*>

    @BeforeTest
    fun setUp() {
        val original = javaClass.classLoader
            .getResourceAsStream("$fixtureInternal.class")!!
            .use { it.readBytes() }
        val transformed = StubTransform.transform(original, javaClass.classLoader)
        cls = SingleClassLoader(javaClass.classLoader, fixtureName, transformed)
            .loadClass(fixtureName)
        LiveEditStubs.reset()
    }

    @AfterTest
    fun tearDown() = LiveEditStubs.reset()

    @Test
    fun `null bytecode falls through to the original body`() {
        LiveEditStubs.nextBytecode = null
        val result = cls.getMethod("add", Int::class.java, Int::class.java).invoke(null, 2, 3)

        assertEquals(5, result, "original body should run when no bytecode is present")
        assertNull(LiveEditStubs.lastStubName, "no stub should be invoked on the fall-through path")
        assertEquals(fixtureInternal, LiveEditStubs.lastGetClassName,
            "getClassBytecode must still be consulted with the internal class name")
    }

    @Test
    fun `static int method routes to stubI with padded, boxed params`() {
        LiveEditStubs.nextBytecode = Any()
        LiveEditStubs.retI = 999
        val result = cls.getMethod("add", Int::class.java, Int::class.java).invoke(null, 2, 3)

        assertEquals(999, result, "return value should come from the interpreter stub")
        assertEquals("stubI", LiveEditStubs.lastStubName)
        assertEquals("add", LiveEditStubs.lastMethodName)
        assertEquals("(II)I", LiveEditStubs.lastMethodDesc)
        val p = LiveEditStubs.lastParams
        assertEquals(4, p.size, "params = [null, this|null, arg0, arg1]")
        assertNull(p[0], "params[0] is the interpreter's padding slot")
        assertNull(p[1], "params[1] is the receiver — null for a static method")
        assertEquals(2, p[2]); assertEquals(3, p[3])
    }

    @Test
    fun `instance reference-returning method uses getInstanceBytecode and checkcasts the result`() {
        val fix = cls.getDeclaredConstructor().newInstance()
        LiveEditStubs.reset()
        LiveEditStubs.nextBytecode = Any()
        LiveEditStubs.retL = "STUBBED"

        val result = cls.getMethod("greet", String::class.java).invoke(fix, "hello")

        assertEquals("STUBBED", result)
        assertEquals("stubL", LiveEditStubs.lastStubName)
        assertEquals(fixtureInternal, LiveEditStubs.lastGetInstanceName)
        assertSame(fix, LiveEditStubs.lastGetInstance, "getInstanceBytecode receives the receiver")
        val p = LiveEditStubs.lastParams
        assertEquals(3, p.size)
        assertNull(p[0])
        assertSame(fix, p[1], "params[1] is the receiver for an instance method")
        assertEquals("hello", p[2])
    }

    @Test
    fun `void method stub path skips the original body, null path runs it`() {
        val fix = cls.getDeclaredConstructor().newInstance()
        val touched = { cls.getField("touched").getBoolean(fix) }
        val touch = cls.getMethod("touch")

        // Stub path: original body (touched = true) must NOT run.
        LiveEditStubs.reset()
        LiveEditStubs.nextBytecode = Any()
        touch.invoke(fix)
        assertEquals("stubV", LiveEditStubs.lastStubName)
        assertEquals(false, touched(), "void stub path must not execute the original body")

        // Fall-through: original body runs.
        LiveEditStubs.reset()
        LiveEditStubs.nextBytecode = null
        touch.invoke(fix)
        assertNull(LiveEditStubs.lastStubName)
        assertEquals(true, touched(), "null path must execute the original body")
    }

    @Test
    fun `more than four args pack correctly including two-slot long and double`() {
        LiveEditStubs.nextBytecode = Any()
        LiveEditStubs.retI = 1234
        val marker = Any()
        val result = cls.getMethod(
            "many",
            Int::class.java, Long::class.java, String::class.java,
            Int::class.java, Double::class.java, Any::class.java,
        ).invoke(null, 10, 20L, "s", 30, 4.5, marker)

        assertEquals(1234, result)
        val p = LiveEditStubs.lastParams
        assertContentEquals(
            arrayOf(null, null, 10, 20L, "s", 30, 4.5, marker), p,
            "long/double must consume two local slots without corrupting later args",
        )
    }

    @Test
    fun `primitive return kinds select the matching stub`() {
        cases().forEach { (method, stub, canned, expected) ->
            LiveEditStubs.reset()
            LiveEditStubs.nextBytecode = Any()
            canned()
            val result = cls.getMethod(method).invoke(null)
            assertEquals(expected, result, "$method should return the stub value")
            assertEquals(stub, LiveEditStubs.lastStubName, "$method should call $stub")
        }
    }

    private fun cases(): List<Case> = listOf(
        Case("lng", "stubJ", { LiveEditStubs.retJ = 42L }, 42L),
        Case("dbl", "stubD", { LiveEditStubs.retD = 3.25 }, 3.25),
        Case("flt", "stubF", { LiveEditStubs.retF = 2.5f }, 2.5f),
        Case("flag", "stubZ", { LiveEditStubs.retZ = true }, true),
        Case("ch", "stubC", { LiveEditStubs.retC = 'Z' }, 'Z'),
    )

    private data class Case(val method: String, val stub: String, val canned: () -> Unit, val expected: Any)

    @Test
    fun `constructor gets the stubConstructor tail and still runs its body`() {
        LiveEditStubs.reset()
        val fix = cls.getDeclaredConstructor().newInstance()

        assertEquals(7, cls.getField("id").getInt(fix), "original constructor body must still run")
        assertEquals(fixtureInternal, LiveEditStubs.lastCtorClass)
        assertSame(fix, LiveEditStubs.lastCtorInstance, "stubConstructor receives the new instance")
    }

    @Test
    fun `transformed class carries the liveEditBytecode field`() {
        assertTrue(
            cls.declaredFields.any { it.name == "\$liveEditBytecode" && it.type == Any::class.java },
            "transform must add a public Object \$liveEditBytecode field",
        )
    }

    /** Defines [className] from [bytes]; delegates every other class (incl. the fake stubs) to [parent]. */
    private class SingleClassLoader(
        parent: ClassLoader,
        private val className: String,
        private val bytes: ByteArray,
    ) : ClassLoader(parent) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            synchronized(getClassLoadingLock(name)) {
                if (name == className) {
                    val existing = findLoadedClass(name)
                    val c = existing ?: defineClass(name, bytes, 0, bytes.size)
                    if (resolve) resolveClass(c)
                    return c
                }
                return super.loadClass(name, resolve)
            }
        }
    }
}
