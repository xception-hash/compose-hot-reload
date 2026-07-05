package dev.hotreload.engine

import dev.hotreload.protocol.LiteralType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LiteralFastPathTest {

    private val base = """
        fun demo() {
            val s = "hello"
            val n = 42
            val big = 9000000000L
            val d = 3.14
            val f = 1.5f
            val b = true
            val c = 'x'
        }
    """.trimIndent()

    /** Build the entry list from the baseline text by locating each literal token. */
    private val helper = "p.LiveLiterals\$DemoKt"
    private fun entries(): List<LiteralEntry> = listOf(
        LiteralEntry("S", base.indexOf("\"hello\""), LiteralType.STRING, helper),
        LiteralEntry("N", base.indexOf("42"), LiteralType.INT, helper),
        LiteralEntry("BIG", base.indexOf("9000000000L"), LiteralType.LONG, helper),
        LiteralEntry("D", base.indexOf("3.14"), LiteralType.DOUBLE, helper),
        LiteralEntry("F", base.indexOf("1.5f"), LiteralType.FLOAT, helper),
        LiteralEntry("B", base.indexOf("true"), LiteralType.BOOLEAN, helper),
        LiteralEntry("C", base.indexOf("'x'"), LiteralType.CHAR, helper),
    )

    private fun edit(from: String, to: String) =
        LiteralFastPath.detect(base, base.replace(from, to), entries())

    @Test fun stringContentEdit() {
        val push = edit("\"hello\"", "\"world\"")!!
        assertEquals("S", push.key)
        assertEquals(helper, push.helperClass)
        assertEquals(LiteralType.STRING, push.type)
        assertEquals("world", push.value)
    }

    @Test fun stringGrowsAndShrinks() {
        assertEquals("hello there", edit("\"hello\"", "\"hello there\"")!!.value)
        assertEquals("", edit("\"hello\"", "\"\"")!!.value)
    }

    @Test fun intEdit() {
        val push = edit("42", "1000")!!
        assertEquals("N", push.key)
        assertEquals(1000, push.value)
    }

    @Test fun longEdit() {
        assertEquals(9000000001L, edit("9000000000L", "9000000001L")!!.value)
    }

    @Test fun doubleAndFloatEdit() {
        assertEquals(2.71, edit("3.14", "2.71")!!.value)
        assertEquals(9.5f, edit("1.5f", "9.5f")!!.value)
    }

    @Test fun booleanEdit() {
        assertEquals(false, edit("true", "false")!!.value)
    }

    @Test fun charEdit() {
        assertEquals('z', edit("'x'", "'z'")!!.value)
    }

    @Test fun escapesAreUnescaped() {
        assertEquals("a\tb\n", edit("\"hello\"", "\"a\\tb\\n\"")!!.value)
    }

    @Test fun noChangeIsNull() {
        assertNull(LiteralFastPath.detect(base, base, entries()))
    }

    @Test fun editOutsideAnyLiteralIsNull() {
        // Rename the function: the change is in code, not inside a tracked literal.
        assertNull(edit("fun demo", "fun demo2"))
    }

    @Test fun introducingATemplateFallsBack() {
        assertNull(edit("\"hello\"", "\"hi \$name\""))
        assertNull(edit("\"hello\"", "\"hi \${name}\""))
    }

    @Test fun twoSeparateLiteralEditsFallBack() {
        val updated = base.replace("\"hello\"", "\"world\"").replace("42", "43")
        assertNull(LiteralFastPath.detect(base, updated, entries()))
    }

    @Test fun editThatMergesLiteralIntoCodeFallsBack() {
        // Delete the closing quote: the token no longer lexes as one string literal.
        assertNull(edit("\"hello\"", "\"hello"))
    }

    @Test fun emptyTableIsNull() {
        assertNull(LiteralFastPath.detect(base, base.replace("\"hello\"", "\"world\""), emptyList()))
    }
}
