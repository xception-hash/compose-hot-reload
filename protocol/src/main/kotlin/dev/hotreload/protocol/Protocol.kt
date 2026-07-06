package dev.hotreload.protocol

/**
 * Wire protocol between the host engine and the on-device runtime-client.
 *
 * Transport: the runtime-client listens on an abstract-namespace Unix domain socket
 * ([deviceSocketName] — no INTERNET permission needed, unlike any TCP bind, and no
 * port collisions between apps); the engine reaches it via
 * `adb forward tcp:<local> localabstract:<name>` and speaks plain TCP locally.
 * The engine is the only caller; every request gets exactly one response, matched by
 * [requestId]. Requests are processed strictly in order by the client, so pipelining
 * is safe.
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
    const val VERSION: Int = 7

    /** Abstract-namespace socket the runtime-client PatchServer binds, per app. */
    fun deviceSocketName(applicationId: String): String = "hotreload-$applicationId"

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
    const val GET_ERRORS: Int = 0x06
    const val LOAD_RESOURCES: Int = 0x07
    const val INVALIDATE_ALL: Int = 0x08
    const val LITERAL_UPDATE: Int = 0x09
    const val LIVE_EDIT_CLASSES: Int = 0x0A

    // Responses (client -> engine)
    const val CAPABILITIES: Int = 0x81
    const val ACK: Int = 0x82
    const val FAILURE: Int = 0x83
    const val COMPOSE_ERRORS: Int = 0x84
}

/** One class's replacement bytecode: single-class dex bytes (d8 --no-desugaring output). */
class ClassDex(val className: String, val dexBytes: ByteArray)

/**
 * One class to hand to the on-device interpreter ([Request.LiveEditClasses]): raw JVM `.class`
 * bytes (NOT dex — the interpreter evaluates classfile bytecode directly). [internalName] is the
 * slash-separated internal name (e.g. `dev/hotreload/sample/MainActivityKt`), used for logging;
 * the interpreter re-derives it from [classBytes] anyway.
 */
class ClassBytes(val internalName: String, val classBytes: ByteArray)

/**
 * Wire type tags for a live-literal value ([Request.LiteralUpdate]). The order matches
 * the byte written on the wire; the engine derives the tag from the `LiveLiterals$*Kt`
 * getter's return descriptor and the runtime-client boxes it back to the same JVM type
 * before handing it to `LiveLiteralKt.updateLiveLiteralValue`.
 */
object LiteralType {
    const val STRING: Int = 0
    const val INT: Int = 1
    const val LONG: Int = 2
    const val FLOAT: Int = 3
    const val DOUBLE: Int = 4
    const val BOOLEAN: Int = 5
    const val CHAR: Int = 6
}

/** One recomposition error Compose captured (and silently recovered from) on device. */
class ComposeErrorInfo(val message: String, val recoverable: Boolean)

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

    /**
     * Fetch errors the Recomposer captured during recomposition (it restores the
     * last-good frame and keeps running, so a broken swap is otherwise invisible).
     * [clear] also clears the captured list so the next query starts clean.
     * Response: [ComposeErrors].
     */
    class GetErrors(requestId: Int, val clear: Boolean) : Request(requestId)

    /**
     * Overlay a rebuilt resource table onto the running app via `ResourcesLoader`
     * (no reinstall). [overlayDir] is a directory RELATIVE to the app's codeCacheDir
     * (same file-based convention as [InjectDex]) holding `resources.arsc` + `res/`.
     * The client attaches a provider for it and then recomposes every composition with
     * state preserved (`ControlledComposition.invalidateAll`, T16), so value resources
     * (string/color) surface without losing `remember`/`rememberSaveable`. Value-only:
     * the engine guarantees IDs are unchanged, so only edited values appear.
     * Response: [Ack]/[Failure].
     */
    class LoadResources(requestId: Int, val overlayDir: String) : Request(requestId)

    /**
     * Tier-1 whole-tree invalidation: `ControlledComposition.invalidateAll()` on every
     * known composition (the T16 mechanism [LoadResources] uses internally). Keyless and
     * state-preserving (`remember`/`rememberSaveable` survive) — used when a redefined
     * class has no Compose group keys to target (e.g. a pure-Kotlin module's body edit),
     * so the change would otherwise not surface until the next natural recomposition.
     * Response: [Ack]/[Failure].
     */
    class InvalidateAll(requestId: Int) : Request(requestId)

    /**
     * Live-literals fast path (T24): update a single Compose live-literal in place. [key]
     * is the `@LiveLiteralInfo(key=…)` string on the generated `LiveLiterals$*Kt` getter;
     * [helperClass] is that generated class's FQCN (live-literals v2 gates each getter on a
     * per-file `enabled` flag on it, which the client must flip); [type] is a [LiteralType]
     * tag; [value] is the boxed constant of that JVM type; [invalidateKey] is the
     * FunctionKeyMeta compose key of the ENCLOSING composable (0 if unknown). The client
     * sets `enabled`, calls `LiveLiteralKt.updateLiveLiteralValue(key, value)` (the value
     * lives in a Compose `MutableState`), then recomposes via
     * `Recomposer.invalidateGroupsWithKey(invalidateKey)` — a liveLiterals build compiles
     * the literal into a keyless helper, so a plain whole-tree invalidate does not wake the
     * Recomposer; the enclosing composable's key is what pumps it. Requires the app compiled
     * with `-Photreload.liveLiterals=true`. Response: [Ack]/[Failure].
     */
    class LiteralUpdate(
        requestId: Int,
        val key: String,
        val helperClass: String,
        val invalidateKey: Int,
        val type: Int,
        val value: Any,
    ) : Request(requestId)

    /**
     * Interpreter fast path (T27): hand [classes] (raw JVM `.class` bytes) to the on-device
     * LiveEdit bytecode interpreter so edits the classifier would otherwise `Rebuild` (member
     * removal, signature change, hierarchy change) hot-apply by interpreting the edited method
     * bodies instead of redefining. The client, on first use per process, registers the
     * interpreter JNI natives and calls `LiveEditStubs.init` (interp.dex is delivered separately
     * via the existing [InjectDex] op earlier in the same batch — no new transport); every time it
     * reflectively calls `LiveEditStubs.addClasses(classes, [], structural=false)`, then the
     * invalidate tail: `invalidateGroupsWithKey` per [groupIds] when non-empty, else
     * `invalidateAllCompositions()`. [primedDexName] is informational (the InjectDex name of the
     * batch's primed-originals dex, or null) — the engine orchestrates redefine/inject ordering.
     * Response: [Ack]/[Failure].
     */
    class LiveEditClasses(
        requestId: Int,
        val classes: List<ClassBytes>,
        val primedDexName: String?,
        val groupIds: List<Int>,
    ) : Request(requestId)
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
        val composeVersion: Int = 0,
    ) : Response(requestId)

    class Ack(requestId: Int) : Response(requestId)

    /** [code] is the JVMTI error when applicable, or -1 for client-side failures. */
    class Failure(requestId: Int, val code: Int, val message: String) : Response(requestId)

    /** Captured recomposition errors; empty list = composition is healthy. */
    class ComposeErrors(requestId: Int, val errors: List<ComposeErrorInfo>) : Response(requestId)
}
