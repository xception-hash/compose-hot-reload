package dev.hotreload.protocol

/**
 * Wire protocol between the host engine and the on-device runtime-client.
 *
 * Transport: TCP. The runtime-client listens on loopback [DEVICE_PORT]; the engine
 * reaches it via `adb forward tcp:<local> tcp:46464`. The engine is the only caller;
 * every request gets exactly one response, matched by [requestId]. Requests are
 * processed strictly in order by the client, so pipelining is safe.
 *
 * Frame format (all integers big-endian, via DataOutput):
 *
 *   u32 frameLength   — byte count of everything after this field
 *   u8  opcode        — see [Opcode]
 *   u32 requestId     — echoed verbatim in the response
 *   ...opcode-specific fields:
 *       string     = DataOutput.writeUTF
 *       bytes      = u32 length + raw bytes
 *       list<T>    = u32 count + entries
 *
 * Dependency-free by design: these sources are compiled into both the JVM engine
 * (as the :protocol module) and the Android runtime-client AAR (via srcDir sharing),
 * so they must use only the Java/Kotlin stdlib.
 */
object Protocol {
    const val VERSION: Int = 1

    /** Device-side loopback port the runtime-client PatchServer binds. */
    const val DEVICE_PORT: Int = 46464

    /** Upper bound on a single frame; anything larger is a corrupt stream. */
    const val MAX_FRAME_BYTES: Int = 64 * 1024 * 1024
}

object Opcode {
    // Requests (engine -> client)
    const val PING: Int = 0x01
    const val REDEFINE: Int = 0x02
    const val INJECT_DEX: Int = 0x03
    const val INVALIDATE: Int = 0x04
    const val RESET: Int = 0x05

    // Responses (client -> engine)
    const val CAPABILITIES: Int = 0x81
    const val ACK: Int = 0x82
    const val FAILURE: Int = 0x83
}

/** One class's replacement bytecode: single-class dex bytes (d8 --no-desugaring output). */
class ClassDex(val className: String, val dexBytes: ByteArray)

sealed class Request(val requestId: Int) {
    /** Handshake + capability probe. Response: [Capabilities]. */
    class Ping(requestId: Int) : Request(requestId)

    /**
     * Redefine [classes] atomically (single JVMTI call over the whole array).
     * [structural] routes to ART's structurally_redefine_classes extension
     * (required when members were added; plain RedefineClasses fails with error 63).
     * Response: [Ack] or [Failure] with the JVMTI error and last_error_message.
     */
    class Redefine(requestId: Int, val structural: Boolean, val classes: List<ClassDex>) : Request(requestId)

    /**
     * Make brand-new classes (new files, new lambdas) loadable: the client writes the
     * dex read-only into codeCacheDir/[name] and calls the file-based
     * add_to_dex_class_loader extension on the app classloader. Must be sent BEFORE
     * the Redefine of any class referencing them. Response: [Ack]/[Failure].
     */
    class InjectDex(requestId: Int, val name: String, val dexBytes: ByteArray) : Request(requestId)

    /**
     * Tier-1 invalidation: Recomposer.invalidateGroupsWithKey for each FunctionKeyMeta
     * key, on the main thread. Preserves all composition state. Response: [Ack]/[Failure].
     */
    class Invalidate(requestId: Int, val keys: IntArray) : Request(requestId)

    /**
     * Tier-2: HotReloader-style save/dispose/load reset. Rebuilds every composition
     * without process death; composition state (remember/rememberSaveable) is LOST.
     * Response: [Ack]/[Failure].
     */
    class Reset(requestId: Int) : Request(requestId)
}

sealed class Response(val requestId: Int) {
    class Capabilities(
        requestId: Int,
        val protocolVersion: Int,
        val apiLevel: Int,
        val canRedefine: Boolean,
        val canStructural: Boolean,
        val canInjectFile: Boolean,
        val composeBridgeOk: Boolean,
    ) : Response(requestId)

    class Ack(requestId: Int) : Response(requestId)

    /** [code] is the JVMTI error when applicable, or -1 for client-side failures. */
    class Failure(requestId: Int, val code: Int, val message: String) : Response(requestId)
}
