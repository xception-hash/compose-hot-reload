package dev.hotreload.engine

/**
 * Decides how a set of recompiled classes can be applied to the running app.
 * Rules derived from Phase 0 (docs/phase0-findings.md):
 *  - unchanged member set + unchanged group keys        → plain RedefineClasses (tier 1)
 *  - members strictly added                             → ART structural redefine (tier 1)
 *  - brand-new class                                    → file-based dex injection
 *  - removals / signature changes / hierarchy edits
 *    on regular classes                                 → on-device interpreter (T27/T28)
 *  - ctor/capture changes on lambda-shaped classes
 *    (regenerated restart lambdas)                      → interpreter support class (T28 proxies)
 *  - anything else (key renumbering, `<init>`/`<clinit>`
 *    edits, field changes on regular classes)           → fall back to a full rebuild
 */
object Classifier {

    sealed class Verdict {
        /** Plain RedefineClasses suffices: same members, same keys, only bodies differ. */
        data class BodyOnly(val invalidateKeys: Set<Int>) : Verdict()

        /** Members were added (none removed/changed): needs the structural extension. */
        data class Structural(val invalidateKeys: Set<Int>) : Verdict()

        /** Class does not exist in the snapshot: inject into the app classloader. */
        object NewClass : Verdict()

        /**
         * Would be [Rebuild], but every disqualifying change is a removal/signature/hierarchy
         * change on eligible (non-`<init>`/`<clinit>`) methods — so the edited method bodies can
         * be interpreted on-device instead (T27). [groupIds] = the class's composable keys.
         */
        data class Interpret(val groupIds: Set<Int>) : Verdict()

        /**
         * A lambda-shaped class (Kotlin lambda/function-reference base) whose constructor or
         * captures changed — the shape Compose regenerates for a composable signature change
         * (T28). It can't be primed (ctor interpretation is unsupported) but it doesn't need to
         * be: sent as a LiveEditClasses SUPPORT class, interpreted `NEW` creates a generated
         * `Proxies.*` VM proxy backed by the new bytes; the stale loaded class is abandoned.
         */
        data class SupportClass(val groupIds: Set<Int>) : Verdict()

        /** Not hot-swappable; [reason] is shown to the user before the fallback build. */
        data class Rebuild(val reason: String) : Verdict()
    }

    /**
     * Superclasses the generated `Proxies` table can stand in for (interpreter proxy path).
     * Mirrors LambdaGenerator's proxy specs minus the suspend bases — suspend lambdas are out of
     * T28 scope (continuation plumbing untested live), so they keep the regular-class rules.
     */
    private val LAMBDA_BASES = setOf(
        "kotlin/jvm/internal/Lambda",
        "kotlin/jvm/internal/FunctionReference",
        "kotlin/jvm/internal/FunctionReferenceImpl",
        "kotlin/jvm/internal/AdaptedFunctionReference",
    )

    /**
     * A method member (id contains a descriptor's `(`) that is neither a constructor nor the
     * static initializer — the only members whose removal/signature change the interpreter can
     * absorb (`<init>`/`<clinit>` interpretation is unsupported; fields aren't code).
     */
    private fun isEligibleMethod(id: String): Boolean =
        '(' in id && !id.startsWith("<init>") && !id.startsWith("<clinit>")

    private fun memberKind(id: String): String = if ('(' in id) "method" else "field"

    /**
     * Classify one recompiled class. [old] is the snapshot facts, null when the class
     * is brand new. Compose group keys of changed composable bodies are collected for
     * targeted invalidation. Edits that would `Rebuild` but only touch eligible methods'
     * signatures/removals (or the class hierarchy) become [Interpret] (T27).
     */
    fun classify(old: ClassFacts?, new: ClassFacts): Verdict {
        if (old == null) return Verdict.NewClass

        val oldById = old.members.associateBy { it.id }
        val newById = new.members.associateBy { it.id }
        val removed = oldById.keys - newById.keys
        val added = newById.keys - oldById.keys

        // Lambda-shaped classes go the T28 proxy route: interpreted NEW replaces every instance
        // with a Proxies.* VM proxy backed by the new bytes, so ctor/capture/shape changes that
        // are hard for a regular class are absorbable here. `<clinit>` and composeKey rules stay
        // hard even for lambdas (INSTANCE singletons / slot-table edits are not proxy-fixable).
        val lambdaShaped = old.superName in LAMBDA_BASES && new.superName in LAMBDA_BASES

        // Reasons this edit can't be a plain/structural redefine, split by how they're absorbed:
        // hard forces Rebuild; supportable routes a lambda-shaped class to SupportClass;
        // interpretable routes a regular class to the interpreter.
        val hard = mutableListOf<String>()
        val interpretable = mutableListOf<String>()
        val supportable = mutableListOf<String>()

        // For a lambda-shaped class every non-hard reason is support-shaped (the proxy replaces
        // the whole instance; there is no primary-interpret route for it).
        fun soft(reason: String) = if (lambdaShaped) supportable.add(reason) else interpretable.add(reason)
        fun softOrHard(isSoft: Boolean, reason: String) =
            if (lambdaShaped || isSoft) soft(reason) else hard.add(reason)

        if (old.superName != new.superName || old.interfaces != new.interfaces || old.access != new.access) {
            // Class-shape change: the interpreter evaluates method bodies regardless of the
            // (structurally-redefined) on-device shape, so it can carry a hierarchy/modifier edit.
            soft("class hierarchy or modifiers changed")
        }

        for (id in removed) {
            when {
                id.startsWith("<clinit>") -> hard += "removed <clinit>"
                else -> softOrHard(isEligibleMethod(id), "removed ${memberKind(id)}: $id")
            }
        }

        val invalidate = mutableSetOf<Int>()
        for ((id, oldMember) in oldById) {
            val newMember = newById[id] ?: continue // removed: handled above
            // Group-key renumbering invalidates the whole key map — the interpreter can't fix the
            // slot table either, so it stays a hard Rebuild.
            if (oldMember.composeKey != newMember.composeKey) {
                hard += "Compose group key changed on $id (group structure edit)"
                continue
            }
            if (oldMember.access != newMember.access) {
                softOrHard(isEligibleMethod(id), "modifiers changed on ${memberKind(id)} $id")
                continue
            }
            if (oldMember.bodyHash != newMember.bodyHash) {
                newMember.composeKey?.let { invalidate += it }
            }
        }

        // Added `<clinit>` (e.g. a lambda flipping capturing → capture-less gains INSTANCE):
        // interpreted GETSTATIC would miss on the loaded class and static-field fallback is
        // untested — keep it a Rebuild everywhere.
        if (added.any { it.startsWith("<clinit>") }) hard += "added <clinit>"
        // Added ctors on a REGULAR class ride along with structural adds only when nothing else
        // disqualifies; if the class already routes to interpret, a new ctor can only be reached
        // through interpreted callers via Constructor.newInstance on the loaded class — which
        // doesn't have it. Lambda-shaped classes take the proxy route instead.
        if (!lambdaShaped && added.any { it.startsWith("<init>") } &&
            (interpretable.isNotEmpty() || removed.any { it.startsWith("<init>") })
        ) {
            hard += "constructor signature changed"
        }

        // New members have no live groups yet; recomposition of their (changed) callers inserts
        // the new groups naturally (Experiment C). Their own keys are included anyway —
        // invalidating a nonexistent group is a no-op.
        added.forEach { newById.getValue(it).composeKey?.let { k -> invalidate += k } }

        if (hard.isNotEmpty()) {
            return Verdict.Rebuild("${new.fqcn}: ${(hard + supportable + interpretable).take(3).joinToString()}")
        }
        if (supportable.isNotEmpty()) return Verdict.SupportClass(invalidate)
        if (interpretable.isNotEmpty()) {
            // T28 lifted the old no-added-method restriction: a signature change is a removal
            // PLUS an addition (the new-descriptor overload + Kotlin's `$default` synthetic), and
            // those added methods exist only in the interpreter's stored bytecode — safe now
            // because plan() routes the WHOLE batch through the interpreter (every edited caller
            // interprets, and ProxyClassEval's methodLookup miss-fallback resolves added statics
            // for interpreted callers), and regenerated restart lambdas ride the proxy path.
            // Callers of a new signature are by definition edited themselves, so they're in the
            // batch; unedited compiled callers keep hitting the old descriptor, which still
            // exists on the primed (old-shape) class.
            //
            // Invalidate ONLY the changed composables' groups, not every composable in the
            // class — invalidateGroupsWithKey resets remember/rememberSaveable in a group's
            // subtree, so touching unchanged siblings (e.g. a Counter in the same file) would
            // needlessly drop their state. Unchanged primed methods re-interpret lazily on
            // their next recomposition. Empty ⇒ the client falls back to keyless whole-tree
            // invalidateAll (state-preserving).
            return Verdict.Interpret(invalidate)
        }

        if (added.isNotEmpty()) return Verdict.Structural(invalidate)

        return Verdict.BodyOnly(invalidate)
    }

    /** Ready-to-send plan for one save event, or the reason we must rebuild instead. */
    sealed class PatchPlan {
        data class HotSwap(
            /** FQCNs to inject (new classes), before any redefine. */
            val inject: List<String>,
            /** FQCNs to redefine, atomically in one batch (excludes [interpret] classes). */
            val redefine: List<String>,
            /** The ART structural extension handles unchanged-structure classes too, so
             *  one added member anywhere upgrades the whole batch (keeps it atomic). */
            val structural: Boolean,
            val invalidateKeys: Set<Int>,
            /** FQCNs whose edited bodies are interpreted on-device (T27). */
            val interpret: List<String>,
            /** FQCNs sent as interpreter SUPPORT classes (proxy path, never primed — T28). */
            val support: List<String>,
            /** Composable keys to invalidate for the [interpret]/[support] classes. */
            val groupIds: Set<Int>,
        ) : PatchPlan()

        data class Rebuild(val reasons: List<String>) : PatchPlan()
    }

    /**
     * Combine per-class verdicts for one save event into a single plan.
     *
     * Whole-batch rule (T28, research §7.4): if ANY class routes to the interpreter, EVERY
     * changed class in the batch does — mixing hot-swap and interpret was T27's live
     * `NoSuchMethodError` (a redefined caller invoking a method that exists only in the
     * interpreter's stored bytecode). New classes still inject (real classes; interpreted `NEW`
     * resolves them via Constructor.newInstance) and lambda-shaped ctor changes ride as support.
     */
    fun plan(changes: List<Pair<ClassFacts, Verdict>>): PatchPlan {
        val reasons = changes.mapNotNull { (_, v) -> (v as? Verdict.Rebuild)?.reason }
        if (reasons.isNotEmpty()) return PatchPlan.Rebuild(reasons)

        val inject = changes.filter { it.second is Verdict.NewClass }.map { it.first.fqcn }
        val anyInterpret = changes.any { it.second is Verdict.Interpret || it.second is Verdict.SupportClass }

        if (anyInterpret) {
            return PatchPlan.HotSwap(
                inject = inject,
                redefine = emptyList(),
                structural = false,
                invalidateKeys = emptySet(),
                interpret = changes.filter {
                    it.second is Verdict.Interpret || it.second is Verdict.BodyOnly || it.second is Verdict.Structural
                }.map { it.first.fqcn },
                support = changes.filter { it.second is Verdict.SupportClass }.map { it.first.fqcn },
                // Pulled-in BodyOnly/Structural classes keep their changed-only invalidate keys —
                // they recompose through the interpreter now, but the state-preservation contract
                // (only changed groups reset) is identical.
                groupIds = changes.flatMapTo(mutableSetOf()) { (_, v) ->
                    when (v) {
                        is Verdict.Interpret -> v.groupIds
                        is Verdict.SupportClass -> v.groupIds
                        is Verdict.BodyOnly -> v.invalidateKeys
                        is Verdict.Structural -> v.invalidateKeys
                        else -> emptySet()
                    }
                },
            )
        }

        return PatchPlan.HotSwap(
            inject = inject,
            redefine = changes.filter { it.second is Verdict.BodyOnly || it.second is Verdict.Structural }
                .map { it.first.fqcn },
            structural = changes.any { it.second is Verdict.Structural },
            invalidateKeys = changes.flatMapTo(mutableSetOf()) { (_, v) ->
                when (v) {
                    is Verdict.BodyOnly -> v.invalidateKeys
                    is Verdict.Structural -> v.invalidateKeys
                    else -> emptySet()
                }
            },
            interpret = emptyList(),
            support = emptyList(),
            groupIds = emptySet(),
        )
    }
}
