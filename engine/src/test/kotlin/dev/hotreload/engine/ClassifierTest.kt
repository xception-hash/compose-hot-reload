package dev.hotreload.engine

import dev.hotreload.engine.Classifier.Verdict
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ClassifierTest {

    private fun cls(name: String = "dev/hotreload/sample/MainActivityKt", vararg members: MemberFacts) =
        ClassFacts(name, "java/lang/Object", emptySet(), 0x21, members.toSet())

    private fun composable(id: String, key: Int, bodyHash: Long) =
        MemberFacts(id, 0x19, bodyHash, key)

    private fun method(id: String, bodyHash: Long) = MemberFacts(id, 0x19, bodyHash, null)

    /** A Kotlin lambda class (restart-lambda shape): extends kotlin.jvm.internal.Lambda. */
    private fun lambda(vararg members: MemberFacts) = ClassFacts(
        "dev/hotreload/sample/MainActivityKt\$Greeting\$1",
        "kotlin/jvm/internal/Lambda",
        setOf("kotlin/jvm/functions/Function2"),
        0x30,
        members.toSet(),
    )

    private val counter = composable("Counter(Landroidx/compose/runtime/Composer;I)V", -96578675, 1L)
    private val greeting = composable("Greeting(Ljava/lang/String;Landroidx/compose/runtime/Composer;I)V", -2116809900, 2L)

    @Test
    fun `unchanged class is body-only with nothing to invalidate`() {
        val v = Classifier.classify(cls(members = arrayOf(counter, greeting)), cls(members = arrayOf(counter, greeting)))
        assertEquals(Verdict.BodyOnly(emptySet()), v)
    }

    @Test
    fun `body edit invalidates only the edited composable's key`() {
        val old = cls(members = arrayOf(counter, greeting))
        val new = cls(members = arrayOf(counter.copy(bodyHash = 99L), greeting))
        assertEquals(Verdict.BodyOnly(setOf(-96578675)), Classifier.classify(old, new))
    }

    @Test
    fun `changed non-composable body swaps without invalidation`() {
        val helper = method("helper()V", 5L)
        val v = Classifier.classify(cls(members = arrayOf(counter, helper)), cls(members = arrayOf(counter, helper.copy(bodyHash = 6L))))
        assertEquals(Verdict.BodyOnly(emptySet()), v)
    }

    @Test
    fun `added composable routes to structural and keeps changed-caller keys`() {
        val badge = composable("Badge(Landroidx/compose/runtime/Composer;I)V", 12345, 7L)
        val old = cls(members = arrayOf(counter, greeting))
        val new = cls(members = arrayOf(counter, greeting.copy(bodyHash = 22L), badge))
        val v = assertIs<Verdict.Structural>(Classifier.classify(old, new))
        assertEquals(setOf(-2116809900.toInt(), 12345), v.invalidateKeys)
    }

    @Test
    fun `removed method routes to interpret, invalidating only changed groups`() {
        // Only greeting removed; the surviving counter is untouched, so nothing is invalidated
        // (the client then falls back to keyless whole-tree invalidateAll, state-preserving).
        val v = Classifier.classify(cls(members = arrayOf(counter, greeting)), cls(members = arrayOf(counter)))
        val interpret = assertIs<Verdict.Interpret>(v)
        assertEquals(emptySet(), interpret.groupIds)
    }

    @Test
    fun `changed signature routes to interpret (T28 lifted the added-method rebuild)`() {
        // remove-plus-add: safe since plan() interprets the whole batch (every edited caller
        // interprets; ProxyClassEval resolves added statics for interpreted callers) and
        // regenerated restart lambdas ride the proxy support path.
        val renamed = composable("Greeting(ILandroidx/compose/runtime/Composer;I)V", -2116809900, 2L)
        val v = Classifier.classify(cls(members = arrayOf(greeting)), cls(members = arrayOf(renamed)))
        val interpret = assertIs<Verdict.Interpret>(v)
        assertEquals(setOf(-2116809900.toInt()), interpret.groupIds) // the added descriptor's key
    }

    @Test
    fun `interpret invalidates a changed sibling but not an unchanged one`() {
        // Remove a helper (interpret trigger) while editing counter's body; only counter's key
        // should be invalidated — greeting (unchanged) keeps its state.
        val helper = method("helper()V", 5L)
        val old = cls(members = arrayOf(counter, greeting, helper))
        val new = cls(members = arrayOf(counter.copy(bodyHash = 42L), greeting))
        val interpret = assertIs<Verdict.Interpret>(Classifier.classify(old, new))
        assertEquals(setOf(-96578675), interpret.groupIds)
    }

    @Test
    fun `hierarchy change routes to interpret`() {
        val old = cls(members = arrayOf(counter))
        val new = old.copy(superName = "android/app/Activity")
        assertIs<Verdict.Interpret>(Classifier.classify(old, new))
    }

    @Test
    fun `group-key renumbering forces rebuild even with same members`() {
        val renumbered = greeting.copy(composeKey = 42)
        val v = Classifier.classify(cls(members = arrayOf(greeting)), cls(members = arrayOf(renumbered)))
        assertIs<Verdict.Rebuild>(v)
    }

    @Test
    fun `removed constructor forces rebuild (interpreter can't absorb it)`() {
        val ctor = method("<init>()V", 1L)
        val v = Classifier.classify(cls(members = arrayOf(counter, ctor)), cls(members = arrayOf(counter)))
        assertIs<Verdict.Rebuild>(v)
    }

    @Test
    fun `removed field forces rebuild (not code the interpreter runs)`() {
        val field = MemberFacts("count:I", 0x2, null, null)
        val v = Classifier.classify(cls(members = arrayOf(counter, field)), cls(members = arrayOf(counter)))
        assertIs<Verdict.Rebuild>(v)
    }

    @Test
    fun `a hard reason wins over an interpretable one`() {
        // Signature change (interpretable) AND a group-key renumber (hard) → Rebuild.
        val renamed = composable("Greeting(ILandroidx/compose/runtime/Composer;I)V", -2116809900, 2L)
        val renumbered = counter.copy(composeKey = 42)
        val v = Classifier.classify(cls(members = arrayOf(counter, greeting)), cls(members = arrayOf(renumbered, renamed)))
        assertIs<Verdict.Rebuild>(v)
    }

    @Test
    fun `new class is inject`() {
        assertIs<Verdict.NewClass>(Classifier.classify(null, cls("dev/hotreload/sample/NewFeatureKt")))
    }

    @Test
    fun `plan upgrades whole batch to structural and orders inject before redefine`() {
        val newCls = cls("dev/hotreload/sample/NewFeatureKt")
        val plan = Classifier.plan(
            listOf(
                cls(members = arrayOf(counter)) to Verdict.BodyOnly(setOf(-96578675)),
                cls("dev/hotreload/sample/OtherKt") to Verdict.Structural(setOf(12345)),
                newCls to Verdict.NewClass,
            ),
        )
        val hot = assertIs<Classifier.PatchPlan.HotSwap>(plan)
        assertTrue(hot.structural)
        assertEquals(listOf("dev.hotreload.sample.NewFeatureKt"), hot.inject)
        assertEquals(listOf("dev.hotreload.sample.MainActivityKt", "dev.hotreload.sample.OtherKt"), hot.redefine)
        assertEquals(setOf(-96578675, 12345), hot.invalidateKeys)
    }

    @Test
    fun `any interpret verdict pulls the whole batch through the interpreter (T28 batch rule)`() {
        // Mixing hot-swap and interpret was T27's live NoSuchMethodError: a redefined caller
        // invoking a method that exists only in the interpreter's stored bytecode. The BodyOnly
        // class must now interpret too, its invalidate keys folded into groupIds.
        val plan = Classifier.plan(
            listOf(
                cls(members = arrayOf(counter)) to Verdict.BodyOnly(setOf(-96578675)),
                cls("dev/hotreload/sample/OtherKt") to Verdict.Interpret(setOf(2, 3)),
            ),
        )
        val hot = assertIs<Classifier.PatchPlan.HotSwap>(plan)
        assertEquals(emptyList(), hot.redefine)
        assertEquals(listOf("dev.hotreload.sample.MainActivityKt", "dev.hotreload.sample.OtherKt"), hot.interpret)
        assertEquals(setOf(-96578675, 2, 3), hot.groupIds)
        assertEquals(emptySet(), hot.invalidateKeys)
    }

    @Test
    fun `lambda ctor and capture change routes to support class (T28)`() {
        // The shape Compose regenerates for a composable signature change: restart lambda gains
        // a param — ctor descriptor changes, capture fields change.
        val old = lambda(
            method("<init>(Ljava/lang/String;I)V", 1L),
            method("invoke(Landroidx/compose/runtime/Composer;I)V", 2L),
            MemberFacts("\$name:Ljava/lang/String;", 0x12, null, null),
        )
        val new = lambda(
            method("<init>(Ljava/lang/String;Ljava/lang/String;I)V", 1L),
            method("invoke(Landroidx/compose/runtime/Composer;I)V", 3L),
            MemberFacts("\$name:Ljava/lang/String;", 0x12, null, null),
            MemberFacts("\$suffix:Ljava/lang/String;", 0x12, null, null),
        )
        assertIs<Verdict.SupportClass>(Classifier.classify(old, new))
    }

    @Test
    fun `lambda plain body edit stays body-only`() {
        val invoke = method("invoke(Landroidx/compose/runtime/Composer;I)V", 2L)
        val ctor = method("<init>(Ljava/lang/String;I)V", 1L)
        val v = Classifier.classify(lambda(ctor, invoke), lambda(ctor, invoke.copy(bodyHash = 9L)))
        assertEquals(Verdict.BodyOnly(emptySet()), v)
    }

    @Test
    fun `non-lambda constructor signature change still forces rebuild`() {
        // Regular classes have no proxy path: interpreted callers construct via
        // Constructor.newInstance on the loaded class, which lacks the new ctor.
        val old = cls(members = arrayOf(counter, method("<init>(I)V", 1L)))
        val new = cls(members = arrayOf(counter.copy(bodyHash = 5L), method("<init>(II)V", 1L)))
        assertIs<Verdict.Rebuild>(Classifier.classify(old, new))
    }

    @Test
    fun `suspend lambda base is not proxy-eligible, ctor change rebuilds`() {
        val base = "kotlin/coroutines/jvm/internal/SuspendLambda"
        val old = lambda(method("<init>(I)V", 1L)).copy(superName = base)
        val new = lambda(method("<init>(II)V", 1L)).copy(superName = base)
        assertIs<Verdict.Rebuild>(Classifier.classify(old, new))
    }

    @Test
    fun `plan partitions support classes and keeps inject (T28)`() {
        val plan = Classifier.plan(
            listOf(
                cls(members = arrayOf(greeting)) to Verdict.Interpret(setOf(-2116809900.toInt())),
                lambda() to Verdict.SupportClass(emptySet()),
                cls("dev/hotreload/sample/NewFeatureKt") to Verdict.NewClass,
            ),
        )
        val hot = assertIs<Classifier.PatchPlan.HotSwap>(plan)
        assertEquals(listOf("dev.hotreload.sample.NewFeatureKt"), hot.inject)
        assertEquals(listOf("dev.hotreload.sample.MainActivityKt"), hot.interpret)
        assertEquals(listOf("dev.hotreload.sample.MainActivityKt\$Greeting\$1"), hot.support)
        assertEquals(emptyList(), hot.redefine)
        assertEquals(setOf(-2116809900.toInt()), hot.groupIds)
    }

    @Test
    fun `any rebuild verdict makes the whole plan a rebuild`() {
        val plan = Classifier.plan(
            listOf(
                cls(members = arrayOf(counter)) to Verdict.BodyOnly(setOf(1)),
                cls("a/B") to Verdict.Rebuild("a.B: members removed"),
            ),
        )
        assertIs<Classifier.PatchPlan.Rebuild>(plan)
    }

    /** MONITORENTER in a class bound for the interpreter: fatal under CheckJNI (T30 item 1). */
    private fun monitorCls(name: String = "dev/hotreload/sample/MainActivityKt", vararg members: MemberFacts) =
        cls(name, *members).copy(monitorMethods = setOf("lockedCompute(I)I"))

    @Test
    fun `interpret verdict on a monitor-bearing class rebuilds the plan (T30)`() {
        val plan = Classifier.plan(
            listOf(monitorCls(members = arrayOf(counter)) to Verdict.Interpret(emptySet())),
        )
        val rebuild = assertIs<Classifier.PatchPlan.Rebuild>(plan)
        assertTrue(rebuild.reasons.single().contains("monitorenter"), rebuild.reasons.single())
    }

    @Test
    fun `monitor-bearing class pulled into an interpret batch rebuilds the plan (T30)`() {
        // The monitor class itself is only BodyOnly, but the sibling's Interpret verdict would
        // pull its bytes through addClasses — where ALL its methods interpret on next run.
        val plan = Classifier.plan(
            listOf(
                cls("a/EditedKt", greeting) to Verdict.Interpret(emptySet()),
                monitorCls("a/HelperKt", counter) to Verdict.BodyOnly(setOf(1)),
            ),
        )
        assertIs<Classifier.PatchPlan.Rebuild>(plan)
    }

    @Test
    fun `monitor-bearing class hot-swaps normally outside interpret batches (T30)`() {
        // Redefine/inject paths run compiled code — synchronized blocks are only fatal when the
        // body is interpreted, so a plain batch must NOT regress to rebuild.
        val plan = Classifier.plan(
            listOf(monitorCls(members = arrayOf(counter)) to Verdict.BodyOnly(setOf(1))),
        )
        val hot = assertIs<Classifier.PatchPlan.HotSwap>(plan)
        assertEquals(listOf("dev.hotreload.sample.MainActivityKt"), hot.redefine)
    }

    @Test
    fun `new monitor-bearing class still injects inside an interpret batch (T30)`() {
        // Injected classes are real loaded classes (interpreted NEW resolves them via
        // Constructor.newInstance) — their synchronized blocks run compiled, so no rebuild.
        val plan = Classifier.plan(
            listOf(
                cls("a/EditedKt", greeting) to Verdict.Interpret(emptySet()),
                monitorCls("a/FreshKt") to Verdict.NewClass,
            ),
        )
        val hot = assertIs<Classifier.PatchPlan.HotSwap>(plan)
        assertEquals(listOf("a.FreshKt"), hot.inject)
    }
}
