package dev.hotreload.protocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Codec for [Request]/[Response] frames. Symmetric and framed: each message is
 * serialized into one length-prefixed frame, so a reader can never desynchronize
 * on a partially understood payload.
 */
object Wire {

    // ---- writing ----

    fun write(out: OutputStream, request: Request) {
        writeFrame(out) { d ->
            when (request) {
                is Request.Ping -> header(d, Opcode.PING, request.requestId)
                is Request.Redefine -> {
                    header(d, Opcode.REDEFINE, request.requestId)
                    d.writeBoolean(request.structural)
                    d.writeInt(request.classes.size)
                    for (c in request.classes) {
                        d.writeUTF(c.className)
                        writeBytes(d, c.dexBytes)
                    }
                }
                is Request.InjectDex -> {
                    header(d, Opcode.INJECT_DEX, request.requestId)
                    d.writeUTF(request.name)
                    writeBytes(d, request.dexBytes)
                }
                is Request.Invalidate -> {
                    header(d, Opcode.INVALIDATE, request.requestId)
                    d.writeInt(request.keys.size)
                    for (k in request.keys) d.writeInt(k)
                }
                is Request.Reset -> header(d, Opcode.RESET, request.requestId)
                is Request.GetErrors -> {
                    header(d, Opcode.GET_ERRORS, request.requestId)
                    d.writeBoolean(request.clear)
                }
                is Request.LoadResources -> {
                    header(d, Opcode.LOAD_RESOURCES, request.requestId)
                    d.writeUTF(request.overlayDir)
                }
            }
        }
    }

    fun write(out: OutputStream, response: Response) {
        writeFrame(out) { d ->
            when (response) {
                is Response.Capabilities -> {
                    header(d, Opcode.CAPABILITIES, response.requestId)
                    d.writeInt(response.protocolVersion)
                    d.writeInt(response.apiLevel)
                    d.writeBoolean(response.canRedefine)
                    d.writeBoolean(response.canStructural)
                    d.writeBoolean(response.canInjectFile)
                    d.writeBoolean(response.composeBridgeOk)
                }
                is Response.Ack -> header(d, Opcode.ACK, response.requestId)
                is Response.Failure -> {
                    header(d, Opcode.FAILURE, response.requestId)
                    d.writeInt(response.code)
                    d.writeUTF(response.message)
                }
                is Response.ComposeErrors -> {
                    header(d, Opcode.COMPOSE_ERRORS, response.requestId)
                    d.writeInt(response.errors.size)
                    for (e in response.errors) {
                        d.writeUTF(e.message)
                        d.writeBoolean(e.recoverable)
                    }
                }
            }
        }
    }

    // ---- reading ----

    /** Blocks for the next request frame; returns null on clean end-of-stream. */
    fun readRequest(input: InputStream): Request? = readFrame(input) { d ->
        val opcode = d.readUnsignedByte()
        val id = d.readInt()
        when (opcode) {
            Opcode.PING -> Request.Ping(id)
            Opcode.REDEFINE -> {
                val structural = d.readBoolean()
                val classes = List(readCount(d)) { ClassDex(d.readUTF(), readBytes(d)) }
                Request.Redefine(id, structural, classes)
            }
            Opcode.INJECT_DEX -> Request.InjectDex(id, d.readUTF(), readBytes(d))
            Opcode.INVALIDATE -> Request.Invalidate(id, IntArray(readCount(d)) { d.readInt() })
            Opcode.RESET -> Request.Reset(id)
            Opcode.GET_ERRORS -> Request.GetErrors(id, d.readBoolean())
            Opcode.LOAD_RESOURCES -> Request.LoadResources(id, d.readUTF())
            else -> throw IOException("unknown request opcode 0x${opcode.toString(16)}")
        }
    }

    /** Blocks for the next response frame; returns null on clean end-of-stream. */
    fun readResponse(input: InputStream): Response? = readFrame(input) { d ->
        val opcode = d.readUnsignedByte()
        val id = d.readInt()
        when (opcode) {
            Opcode.CAPABILITIES -> Response.Capabilities(
                id, d.readInt(), d.readInt(),
                d.readBoolean(), d.readBoolean(), d.readBoolean(), d.readBoolean(),
            )
            Opcode.ACK -> Response.Ack(id)
            Opcode.FAILURE -> Response.Failure(id, d.readInt(), d.readUTF())
            Opcode.COMPOSE_ERRORS -> Response.ComposeErrors(
                id, List(readCount(d)) { ComposeErrorInfo(d.readUTF(), d.readBoolean()) },
            )
            else -> throw IOException("unknown response opcode 0x${opcode.toString(16)}")
        }
    }

    // ---- framing primitives ----

    private fun header(d: DataOutputStream, opcode: Int, requestId: Int) {
        d.writeByte(opcode)
        d.writeInt(requestId)
    }

    private inline fun writeFrame(out: OutputStream, body: (DataOutputStream) -> Unit) {
        val buf = ByteArrayOutputStream()
        body(DataOutputStream(buf))
        val d = DataOutputStream(out)
        d.writeInt(buf.size())
        buf.writeTo(d)
        d.flush()
    }

    private inline fun <T> readFrame(input: InputStream, body: (DataInputStream) -> T): T? {
        val d = DataInputStream(input)
        val length = try {
            d.readInt()
        } catch (e: EOFException) {
            return null // clean close between frames
        }
        if (length < 0 || length > Protocol.MAX_FRAME_BYTES) {
            throw IOException("bad frame length $length")
        }
        val frame = ByteArray(length)
        d.readFully(frame)
        return body(DataInputStream(ByteArrayInputStream(frame)))
    }

    private fun writeBytes(d: DataOutputStream, bytes: ByteArray) {
        d.writeInt(bytes.size)
        d.write(bytes)
    }

    private fun readBytes(d: DataInputStream): ByteArray {
        val len = readCount(d)
        return ByteArray(len).also { d.readFully(it) }
    }

    private fun readCount(d: DataInputStream): Int {
        val n = d.readInt()
        if (n < 0 || n > Protocol.MAX_FRAME_BYTES) throw IOException("bad count $n")
        return n
    }
}
