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
    fun `removed method routes to interpret with the class's composable keys`() {
        val v = Classifier.classify(cls(members = arrayOf(counter, greeting)), cls(members = arrayOf(counter)))
        val interpret = assertIs<Verdict.Interpret>(v)
        assertEquals(setOf(-96578675), interpret.groupIds)
    }

    @Test
    fun `changed signature is remove-plus-add, routes to interpret`() {
        val renamed = composable("Greeting(ILandroidx/compose/runtime/Composer;I)V", -2116809900, 2L)
        val v = Classifier.classify(cls(members = arrayOf(greeting)), cls(members = arrayOf(renamed)))
        val interpret = assertIs<Verdict.Interpret>(v)
        assertEquals(setOf(-2116809900.toInt()), interpret.groupIds)
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
    fun `plan separates interpret classes from redefines and unions their group keys`() {
        val plan = Classifier.plan(
            listOf(
                cls(members = arrayOf(counter)) to Verdict.BodyOnly(setOf(-96578675)),
                cls("dev/hotreload/sample/OtherKt") to Verdict.Interpret(setOf(2, 3)),
            ),
        )
        val hot = assertIs<Classifier.PatchPlan.HotSwap>(plan)
        assertEquals(listOf("dev.hotreload.sample.MainActivityKt"), hot.redefine)
        assertEquals(listOf("dev.hotreload.sample.OtherKt"), hot.interpret)
        assertEquals(setOf(2, 3), hot.groupIds)
        assertEquals(setOf(-96578675), hot.invalidateKeys)
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
}
