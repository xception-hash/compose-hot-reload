package dev.hotreload.engine

import dev.hotreload.protocol.ClassDex
import dev.hotreload.protocol.Request
import dev.hotreload.protocol.Response
import dev.hotreload.protocol.Wire
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Engine-side connection to the on-device PatchServer (via `adb forward`).
 * Synchronous request/response; the server processes strictly in order.
 */
class DeviceClient(host: String = "127.0.0.1", port: Int) : Closeable {
    private val socket = Socket().apply {
        tcpNoDelay = true
        connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
    }
    private val input = BufferedInputStream(socket.getInputStream())
    private val output = BufferedOutputStream(socket.getOutputStream())
    private var nextId = 1

    fun ping(): Response.Capabilities = exchange { Request.Ping(it) } as Response.Capabilities

    /** Throws [DeviceCallException] on device-reported failure. */
    fun redefine(classes: List<ClassDex>, structural: Boolean) {
        expectAck(exchange { Request.Redefine(it, structural, classes) })
    }

    fun injectDex(name: String, dexBytes: ByteArray) {
        expectAck(exchange { Request.InjectDex(it, name, dexBytes) })
    }

    fun invalidate(keys: IntArray) {
        expectAck(exchange { Request.Invalidate(it, keys) })
    }

    fun reset() {
        expectAck(exchange { Request.Reset(it) })
    }

    private fun exchange(build: (requestId: Int) -> Request): Response {
        val request = build(nextId++)
        Wire.write(output, request)
        val response = Wire.readResponse(input)
            ?: throw IOException("device closed connection mid-request")
        check(response.requestId == request.requestId) {
            "response id ${response.requestId} != request id ${request.requestId}"
        }
        return response
    }

    private fun expectAck(response: Response) {
        if (response is Response.Failure) {
            throw DeviceCallException(response.code, response.message)
        }
    }

    override fun close() {
        socket.close()
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 5_000
    }
}

/** A request the device received and rejected ([code] = JVMTI error, or -1 client-side). */
class DeviceCallException(val code: Int, message: String) : IOException("device error $code: $message")
