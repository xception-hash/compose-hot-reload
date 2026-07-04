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
    fun capabilities() {
        val got = roundTripResponse(
            Response.Capabilities(1, Protocol.VERSION, 36, true, true, false, true),
        ) as Response.Capabilities
        assertEquals(Protocol.VERSION, got.protocolVersion)
        assertEquals(36, got.apiLevel)
        assertTrue(got.canRedefine)
        assertTrue(got.canStructural)
        assertEquals(false, got.canInjectFile)
        assertTrue(got.composeBridgeOk)
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
