package dev.hotreload.engine

import kotlin.test.*

class TomlTest {

    @Test
    fun stringAndEscapeRoundTrips() {
        val original = "Hello \"World\" \\ \n \t"
        val serialized = Toml.writeString(original)
        val parsedMap = Toml.parse("key = $serialized")
        assertEquals(original, parsedMap["key"])
    }

    @Test
    fun booleans() {
        val parsed = Toml.parse("key1 = true\nkey2 = false")
        assertEquals(true, parsed["key1"])
        assertEquals(false, parsed["key2"])
    }

    @Test
    fun integer() {
        val parsed = Toml.parse("key = 123456")
        assertEquals(123456L, parsed["key"])
    }

    @Test
    fun array() {
        val parsedEmpty = Toml.parse("key = []")
        assertEquals(emptyList<String>(), parsedEmpty["key"])

        val parsedSimple = Toml.parse("key = [\"a\", \"b\"]")
        assertEquals(listOf("a", "b"), parsedSimple["key"])

        val parsedTrailing = Toml.parse("key = [\"a\", \"b\",]")
        assertEquals(listOf("a", "b"), parsedTrailing["key"])
    }

    @Test
    fun fullLineCommentAndBlankLines() {
        val input = """
            # This is a comment
            
            key = "value"
            
            # Another comment
        """.trimIndent()
        val parsed = Toml.parse(input)
        assertEquals(1, parsed.size)
        assertEquals("value", parsed["key"])
    }

    @Test
    fun errorInlineCommentAfterValue() {
        val e = assertFailsWith<IllegalArgumentException> {
            Toml.parse("key = \"value\" # comment")
        }
        assertTrue(e.message!!.contains("line 1: unexpected trailing content"))
    }

    @Test
    fun errorTable() {
        val e = assertFailsWith<IllegalArgumentException> {
            Toml.parse("[table]\nkey = \"value\"")
        }
        assertTrue(e.message!!.contains("line 1: tables are not supported"))
    }

    @Test
    fun errorDuplicateKey() {
        val e = assertFailsWith<IllegalArgumentException> {
            Toml.parse("key = \"value1\"\nkey = \"value2\"")
        }
        assertTrue(e.message!!.contains("line 2: duplicate key 'key'"))
    }

    @Test
    fun errorUnknownEscape() {
        val e = assertFailsWith<IllegalArgumentException> {
            Toml.parse("key = \"value \\x\"")
        }
        assertTrue(e.message!!.contains("line 1: invalid escape sequence '\\x'"))
    }

    @Test
    fun errorMultiLineArray() {
        val e = assertFailsWith<IllegalArgumentException> {
            Toml.parse("key = [\n\"a\"\n]")
        }
        assertTrue(e.message!!.contains("line 1: arrays must be on one line"))
    }

    @Test
    fun errorNonStringArrayElement() {
        val e = assertFailsWith<IllegalArgumentException> {
            Toml.parse("key = [true]")
        }
        assertTrue(e.message!!.contains("line 1: non-string elements"))
    }

    @Test
    fun errorGarbageLine() {
        val e = assertFailsWith<IllegalArgumentException> {
            Toml.parse("garbage_line")
        }
        assertTrue(e.message!!.contains("line 1: invalid line format"))
    }
}
