package dev.hotreload.engine

/**
 * Decides how a set of recompiled classes can be applied to the running app.
 * Rules derived from Phase 0 (docs/phase0-findings.md):
 *  - unchanged member set + unchanged group keys        → plain RedefineClasses (tier 1)
 *  - members strictly added                             → ART structural redefine (tier 1)
 *  - brand-new class                                    → file-based dex injection
 *  - anything else (removals, signature changes, key
 *    renumbering, hierarchy edits)                      → fall back to a full rebuild
 */
object Classifier {

    sealed class Verdict {
        /** Plain RedefineClasses suffices: same members, same keys, only bodies differ. */
        data class BodyOnly(val invalidateKeys: Set<Int>) : Verdict()

        /** Members were added (none removed/changed): needs the structural extension. */
        data class Structural(val invalidateKeys: Set<Int>) : Verdict()

        /** Class does not exist in the snapshot: inject into the app classloader. */
        object NewClass : Verdict()

        /** Not hot-swappable; [reason] is shown to the user before the fallback build. */
        data class Rebuild(val reason: String) : Verdict()
    }

    /**
     * Classify one recompiled class. [old] is the snapshot facts, null when the class
     * is brand new. Compose group keys of changed composable bodies are collected for
     * targeted invalidation.
     */
    fun classify(old: ClassFacts?, new: ClassFacts): Verdict {
        if (old == null) return Verdict.NewClass

        if (old.superName != new.superName || old.interfaces != new.interfaces || old.access != new.access) {
            return Verdict.Rebuild("${new.fqcn}: class hierarchy or modifiers changed")
        }

        val oldById = old.members.associateBy { it.id }
        val newById = new.members.associateBy { it.id }

        val removed = oldById.keys - newById.keys
        if (removed.isNotEmpty()) {
            return Verdict.Rebuild("${new.fqcn}: members removed or signatures changed: ${removed.sorted().take(3).joinToString()}")
        }

        val invalidate = mutableSetOf<Int>()
        for ((id, oldMember) in oldById) {
            val newMember = newById.getValue(id)
            if (oldMember.access != newMember.access) {
                return Verdict.Rebuild("${new.fqcn}: modifiers changed on $id")
            }
            // Group-key renumbering invalidates the whole key map — never hot-swap it.
            if (oldMember.composeKey != newMember.composeKey) {
                return Verdict.Rebuild("${new.fqcn}: Compose group key changed on $id (group structure edit)")
            }
            if (oldMember.bodyHash != newMember.bodyHash) {
                newMember.composeKey?.let { invalidate += it }
            }
        }

        val added = newById.keys - oldById.keys
        if (added.isNotEmpty()) {
            // New members have no live groups yet; recomposition of their (changed)
            // callers inserts the new groups naturally (Experiment C). Their own keys
            // are included anyway — invalidating a nonexistent group is a no-op.
            added.forEach { newById.getValue(it).composeKey?.let { k -> invalidate += k } }
            return Verdict.Structural(invalidate)
        }

        return Verdict.BodyOnly(invalidate)
    }

    /** Ready-to-send plan for one save event, or the reason we must rebuild instead. */
    sealed class PatchPlan {
        data class HotSwap(
            /** FQCNs to inject (new classes), before any redefine. */
            val inject: List<String>,
            /** FQCNs to redefine, atomically in one batch. */
            val redefine: List<String>,
            /** The ART structural extension handles unchanged-structure classes too, so
             *  one added member anywhere upgrades the whole batch (keeps it atomic). */
            val structural: Boolean,
            val invalidateKeys: Set<Int>,
        ) : PatchPlan()

        data class Rebuild(val reasons: List<String>) : PatchPlan()
    }

    /** Combine per-class verdicts for one save event into a single plan. */
    fun plan(changes: List<Pair<ClassFacts, Verdict>>): PatchPlan {
        val reasons = changes.mapNotNull { (_, v) -> (v as? Verdict.Rebuild)?.reason }
        if (reasons.isNotEmpty()) return PatchPlan.Rebuild(reasons)

        return PatchPlan.HotSwap(
            inject = changes.filter { it.second is Verdict.NewClass }.map { it.first.fqcn },
            redefine = changes.filter { it.second !is Verdict.NewClass }.map { it.first.fqcn },
            structural = changes.any { it.second is Verdict.Structural },
            invalidateKeys = changes.flatMapTo(mutableSetOf()) { (_, v) ->
                when (v) {
                    is Verdict.BodyOnly -> v.invalidateKeys
                    is Verdict.Structural -> v.invalidateKeys
                    else -> emptySet()
                }
            },
        )
    }
}
