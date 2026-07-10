package dev.hotreload.engine

/**
 * Everything the [Classifier] needs to know about one compiled class, extracted
 * host-side from `.class` files with ASM (never from dex: D8 strips FunctionKeyMeta,
 * which has CLASS retention).
 *
 * Extraction contract (implemented by FactsExtractor, see tasks/T04):
 *  - [members]: one entry per declared method AND field.
 *  - [MemberFacts.bodyHash]: stable hash of a method's Code (instructions rendered via
 *    ASM Textifier, constant pool indices resolved — so an unchanged body hashes equal
 *    across recompilations). Null for fields and abstract/native methods.
 *  - [MemberFacts.composeKey]: the FunctionKeyMeta key
 *    (`androidx.compose.runtime.internal.FunctionKeyMeta`, key/startOffset/endOffset)
 *    when present on the method; offsets are dropped — they shift on every edit and
 *    must not affect classification.
 */
data class ClassFacts(
    /** JVM internal name, e.g. `dev/hotreload/sample/MainActivityKt`. */
    val internalName: String,
    val superName: String?,
    val interfaces: Set<String>,
    val access: Int,
    val members: Set<MemberFacts>,
    /**
     * Ids of methods whose Code contains MONITORENTER (`synchronized` blocks). A class with any
     * such method may never be delivered to the on-device interpreter: `JNI.enterMonitor` returns
     * to ART holding the monitor, which CheckJNI (force-enabled on every debuggable app — i.e.
     * all our targets) aborts as "Still holding a locked object on JNI end" (T30 item 1, live
     * SIGABRT). `synchronized` METHODS are fine — ART acquires their monitor on the real frame's
     * invocation; only explicit monitor opcodes inside interpreted bodies are fatal.
     */
    val monitorMethods: Set<String> = emptySet(),
) {
    val fqcn: String get() = internalName.replace('/', '.')
}

data class MemberFacts(
    /** name + descriptor, e.g. `Counter(Landroidx/compose/runtime/Composer;I)V` or `count:I`. */
    val id: String,
    val access: Int,
    val bodyHash: Long?,
    val composeKey: Int?,
)
