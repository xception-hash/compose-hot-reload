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

        /**
         * Would be [Rebuild], but every disqualifying change is a removal/signature/hierarchy
         * change on eligible (non-`<init>`/`<clinit>`) methods — so the edited method bodies can
         * be interpreted on-device instead (T27). [groupIds] = the class's composable keys.
         */
        data class Interpret(val groupIds: Set<Int>) : Verdict()

        /** Not hot-swappable; [reason] is shown to the user before the fallback build. */
        data class Rebuild(val reason: String) : Verdict()
    }

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

        // Reasons this edit can't be a plain/structural redefine, split by whether the
        // interpreter can absorb them. A single hard reason forces Rebuild; otherwise any
        // interpretable reason routes the whole class to the interpreter.
        val hard = mutableListOf<String>()
        val interpretable = mutableListOf<String>()

        if (old.superName != new.superName || old.interfaces != new.interfaces || old.access != new.access) {
            // Class-shape change: the interpreter evaluates method bodies regardless of the
            // (structurally-redefined) on-device shape, so it can carry a hierarchy/modifier edit.
            interpretable += "class hierarchy or modifiers changed"
        }

        for (id in removed) {
            if (isEligibleMethod(id)) interpretable += "member removed or signature changed: $id"
            else hard += "removed ${memberKind(id)}: $id"
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
                if (isEligibleMethod(id)) interpretable += "modifiers changed on $id"
                else hard += "modifiers changed on ${memberKind(id)} $id"
                continue
            }
            if (oldMember.bodyHash != newMember.bodyHash) {
                newMember.composeKey?.let { invalidate += it }
            }
        }

        // New members have no live groups yet; recomposition of their (changed) callers inserts
        // the new groups naturally (Experiment C). Their own keys are included anyway —
        // invalidating a nonexistent group is a no-op.
        added.forEach { newById.getValue(it).composeKey?.let { k -> invalidate += k } }

        if (hard.isNotEmpty()) {
            return Verdict.Rebuild("${new.fqcn}: ${(hard + interpretable).take(3).joinToString()}")
        }
        if (interpretable.isNotEmpty()) {
            // A removal / hierarchy change we could interpret — but ONLY if no method was ADDED.
            // A signature change is a removal PLUS an addition (the new-descriptor overload, plus
            // Kotlin's `$default` synthetic and, for composables, a regenerated restart lambda).
            // Those added methods have no presence on the primed baseline (structural redefine of
            // the OLD bytes) — they exist only in the interpreter's stored bytecode — so any
            // NON-interpreted caller (a redefined caller in another module; the restart lambda)
            // invokes them for real and hits NoSuchMethodError. Keep such edits a Rebuild.
            val addedMethods = added.filter { '(' in it }
            if (addedMethods.isEmpty()) {
                // Invalidate ONLY the changed composables' groups, not every composable in the
                // class — invalidateGroupsWithKey resets remember/rememberSaveable in a group's
                // subtree, so touching unchanged siblings (e.g. a Counter in the same file) would
                // needlessly drop their state. Unchanged primed methods re-interpret lazily on
                // their next recomposition. Empty ⇒ the client falls back to keyless whole-tree
                // invalidateAll (state-preserving).
                return Verdict.Interpret(invalidate)
            }
            return Verdict.Rebuild(
                "${new.fqcn}: signatures changed (adds ${addedMethods.sorted().first()}) — the " +
                    "interpreter can't back the new method for non-interpreted callers",
            )
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
            /** Composable keys to invalidate for the [interpret] classes. */
            val groupIds: Set<Int>,
        ) : PatchPlan()

        data class Rebuild(val reasons: List<String>) : PatchPlan()
    }

    /** Combine per-class verdicts for one save event into a single plan. */
    fun plan(changes: List<Pair<ClassFacts, Verdict>>): PatchPlan {
        val reasons = changes.mapNotNull { (_, v) -> (v as? Verdict.Rebuild)?.reason }
        if (reasons.isNotEmpty()) return PatchPlan.Rebuild(reasons)

        return PatchPlan.HotSwap(
            inject = changes.filter { it.second is Verdict.NewClass }.map { it.first.fqcn },
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
            interpret = changes.filter { it.second is Verdict.Interpret }.map { it.first.fqcn },
            groupIds = changes.flatMapTo(mutableSetOf()) { (_, v) -> (v as? Verdict.Interpret)?.groupIds ?: emptySet() },
        )
    }
}
