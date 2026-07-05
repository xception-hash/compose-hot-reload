package dev.hotreload.protocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WireTest {

    private fun roundTripRequest(r: Request): Request {
        val buf = ByteArrayOutputStream()
        Wire.write(buf, r)
        return Wire.readRequest(ByteArrayInputStream(buf.toByteArray()))!!
    }

    private fun roundTripResponse(r: Response): Response {
        val buf = ByteArrayOutputStream()
        Wire.write(buf, r)
        return Wire.readResponse(ByteArrayInputStream(buf.toByteArray()))!!
    }

    @Test
    fun ping() {
        val r = roundTripRequest(Request.Ping(7)) as Request.Ping
        assertEquals(7, r.requestId)
    }

    @Test
    fun redefine() {
        val dex1 = byteArrayOf(0x64, 0x65, 0x78, 0x0A) // "dex\n"
        val dex2 = ByteArray(100_000) { it.toByte() }
        val sent = Request.Redefine(
            42, structural = true,
            classes = listOf(ClassDex("dev.hotreload.sample.MainActivityKt", dex1), ClassDex("a.B", dex2)),
        )
        val got = roundTripRequest(sent) as Request.Redefine
        assertEquals(42, got.requestId)
        assertTrue(got.structural)
        assertEquals(2, got.classes.size)
        assertEquals("dev.hotreload.sample.MainActivityKt", got.classes[0].className)
        assertContentEquals(dex1, got.classes[0].dexBytes)
        assertContentEquals(dex2, got.classes[1].dexBytes)
    }

    @Test
    fun injectDex() {
        val got = roundTripRequest(Request.InjectDex(3, "patch-7.dex", byteArrayOf(1, 2, 3))) as Request.InjectDex
        assertEquals("patch-7.dex", got.name)
        assertContentEquals(byteArrayOf(1, 2, 3), got.dexBytes)
    }

    @Test
    fun invalidate() {
        val keys = intArrayOf(796097800, -96578675, -2116809900.toInt())
        val got = roundTripRequest(Request.Invalidate(9, keys)) as Request.Invalidate
        assertContentEquals(keys, got.keys)
    }

    @Test
    fun reset() {
        val r = roundTripRequest(Request.Reset(11)) as Request.Reset
        assertEquals(11, r.requestId)
    }

    @Test
    fun loadResources() {
        val got = roundTripRequest(Request.LoadResources(13, "hotreload-overlay-4")) as Request.LoadResources
        assertEquals(13, got.requestId)
        assertEquals("hotreload-overlay-4", got.overlayDir)
    }

    @Test
    fun invalidateAll() {
        val r = roundTripRequest(Request.InvalidateAll(21)) as Request.InvalidateAll
        assertEquals(21, r.requestId)
    }

    @Test
    fun literalUpdate() {
        val str = roundTripRequest(
            Request.LiteralUpdate(1, "String\$arg-0\$call-Text", "p.LiveLiterals\$FooKt", -123456, LiteralType.STRING, "hello \$world"),
        ) as Request.LiteralUpdate
        assertEquals("String\$arg-0\$call-Text", str.key)
        assertEquals("p.LiveLiterals\$FooKt", str.helperClass)
        assertEquals(-123456, str.invalidateKey)
        assertEquals(LiteralType.STRING, str.type)
        assertEquals("hello \$world", str.value)

        val i = roundTripRequest(Request.LiteralUpdate(2, "k", "H", 7, LiteralType.INT, 42)) as Request.LiteralUpdate
        assertEquals(7, i.invalidateKey)
        assertEquals(42, i.value)
        val l = roundTripRequest(Request.LiteralUpdate(3, "k", "H", 0, LiteralType.LONG, 9_000_000_000L)) as Request.LiteralUpdate
        assertEquals(9_000_000_000L, l.value)
        val f = roundTripRequest(Request.LiteralUpdate(4, "k", "H", 0, LiteralType.FLOAT, 1.5f)) as Request.LiteralUpdate
        assertEquals(1.5f, f.value)
        val d = roundTripRequest(Request.LiteralUpdate(5, "k", "H", 0, LiteralType.DOUBLE, 3.14)) as Request.LiteralUpdate
        assertEquals(3.14, d.value)
        val b = roundTripRequest(Request.LiteralUpdate(6, "k", "H", 0, LiteralType.BOOLEAN, true)) as Request.LiteralUpdate
        assertEquals(true, b.value)
        val c = roundTripRequest(Request.LiteralUpdate(7, "k", "H", 0, LiteralType.CHAR, 'x')) as Request.LiteralUpdate
        assertEquals('x', c.value)
    }

    @Test
    fun capabilities() {
        val got = roundTripResponse(
            Response.Capabilities(1, Protocol.VERSION, 36, true, true, false, true, 10700),
        ) as Response.Capabilities
        assertEquals(Protocol.VERSION, got.protocolVersion)
        assertEquals(36, got.apiLevel)
        assertTrue(got.canRedefine)
        assertTrue(got.canStructural)
        assertEquals(false, got.canInjectFile)
        assertTrue(got.composeBridgeOk)
        assertEquals(10700, got.composeVersion)
    }

    @Test
    fun ackAndFailure() {
        val ack = roundTripResponse(Response.Ack(5)) as Response.Ack
        assertEquals(5, ack.requestId)
        val fail = roundTripResponse(Response.Failure(6, 63, "method count changed")) as Response.Failure
        assertEquals(63, fail.code)
        assertEquals("method count changed", fail.message)
    }

    @Test
    fun pipelinedFramesAndCleanEof() {
        val buf = ByteArrayOutputStream()
        Wire.write(buf, Request.Ping(1))
        Wire.write(buf, Request.Reset(2))
        val input = ByteArrayInputStream(buf.toByteArray())
        assertTrue(Wire.readRequest(input) is Request.Ping)
        assertTrue(Wire.readRequest(input) is Request.Reset)
        assertNull(Wire.readRequest(input))
    }
}
