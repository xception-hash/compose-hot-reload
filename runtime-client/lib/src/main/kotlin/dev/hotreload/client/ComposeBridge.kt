package dev.hotreload.client

import android.util.Log

/**
 * Reflection shims into Compose runtime internals (surface documented in
 * docs/phase0-findings.md, proven at Compose BOM 2026.06.01). Lookup is by name
 * prefix to tolerate the `$runtime` internal-visibility mangling suffix.
 */
object ComposeBridge {
    private const val TAG = "ComposeBridge"

    private val recomposerCompanion: Any? by lazy {
        try {
            val recomposer = Class.forName("androidx.compose.runtime.Recomposer")
            val field = recomposer.getDeclaredField("Companion").apply { isAccessible = true }
            field.get(null)
        } catch (t: Throwable) {
            Log.e(TAG, "Recomposer.Companion unavailable", t)
            null
        }
    }

    /** True when the Compose runtime reflection surface resolved (capability handshake). */
    val available: Boolean
        get() = recomposerCompanion != null

    private fun method(prefix: String) = recomposerCompanion?.let { companion ->
        companion.javaClass.declaredMethods
            .firstOrNull { it.name.startsWith(prefix) }
            ?.apply { isAccessible = true }
    }

    /** Must run before any composition exists so compositions register hot-reload state. */
    fun enableHotReloadMode() {
        try {
            // NB: Method.invoke on a void method returns null, so its result can never
            // be used to detect "method missing" — check for the lookup miss explicitly.
            val m = method("setHotReloadEnabled")
            if (m == null) {
                Log.e(TAG, "setHotReloadEnabled not found")
                return
            }
            m.invoke(recomposerCompanion, true)
            Log.i(TAG, "setHotReloadEnabled(true) OK")
        } catch (t: Throwable) {
            Log.e(TAG, "setHotReloadEnabled failed", t)
        }
    }

    /** Tier-1 targeted invalidation; preserves all composition state. */
    fun invalidateGroupsWithKey(key: Int): Boolean {
        return try {
            val m = method("invalidateGroupsWithKey") ?: return false
            m.invoke(recomposerCompanion, key)
            Log.i(TAG, "invalidateGroupsWithKey($key) OK")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "invalidateGroupsWithKey($key) failed", t)
            false
        }
    }

    /**
     * Keyless whole-tree recomposition used for pure resource edits (which have no
     * edited-function key): recompose EVERY known composition while preserving both
     * `remember` and `rememberSaveable`. This is the only state-preserving whole-tree
     * refresh (T16 docs/resource-invalidation-experiment.md) — `invalidateGroupsWithKey`
     * would reset the keyed subtree's `remember`, and the tier-2 reset loses all state.
     *
     * Route (confirmed live at Compose BOM 2026.06.01): the static field
     * `Recomposer._runningRecomposers` (a MutableStateFlow) → its value (Set of
     * RecomposerInfoImpl) → each element's synthetic `this$0` (the owning Recomposer) →
     * the Recomposer's `_knownCompositions` (List<ControlledComposition>) →
     * `invalidateAll()` on each. Field names carry the underscore prefix and the
     * composition list lives on the Recomposer INSTANCE, not the Companion. Must run on
     * the main thread.
     */
    fun invalidateAllCompositions(): Boolean {
        return try {
            val recomposer = Class.forName("androidx.compose.runtime.Recomposer")
            val running = declaredField(recomposer, "_runningRecomposers")
                ?: run { Log.e(TAG, "_runningRecomposers not found"); return false }
            val stateFlow = running.get(null) ?: run { Log.e(TAG, "_runningRecomposers is null"); return false }
            val value = stateFlow.javaClass.methods
                .firstOrNull { it.name == "getValue" && it.parameterCount == 0 }
                ?.apply { isAccessible = true }?.invoke(stateFlow)
            val infos = value as? Set<*> ?: run { Log.e(TAG, "runningRecomposers value not a Set"); return false }

            // One composition can be reached via multiple recomposers; invalidate once.
            val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
            var count = 0
            for (info in infos) {
                if (info == null) continue
                val outer = declaredField(info.javaClass, "this\$0")?.get(info)
                    ?: run { Log.e(TAG, "RecomposerInfoImpl.this\$0 not found"); return false }
                val known = declaredField(outer.javaClass, "_knownCompositions")
                    ?: run { Log.e(TAG, "_knownCompositions not found"); return false }
                val compositions = known.get(outer) as? List<*> ?: continue
                for (c in compositions) {
                    if (c == null || !seen.add(c)) continue
                    val m = c.javaClass.methods
                        .firstOrNull { it.name == "invalidateAll" && it.parameterCount == 0 }
                        ?.apply { isAccessible = true }
                        ?: run { Log.e(TAG, "invalidateAll not found on ${c.javaClass.name}"); return false }
                    m.invoke(c)
                    count++
                }
            }
            Log.i(TAG, "invalidateAll on $count compositions OK")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "invalidateAllCompositions failed", t)
            false
        }
    }

    /** Declared field on [cls] whose name starts with [prefix] (tolerates name mangling). */
    private fun declaredField(cls: Class<*>, prefix: String) =
        cls.declaredFields.firstOrNull { it.name.startsWith(prefix) }?.apply { isAccessible = true }

    /** One error the Recomposer captured during recomposition (hot-reload mode). */
    class CapturedError(val message: String, val recoverable: Boolean)

    /**
     * Errors Compose captured while recomposing (it restores the last-good frame and
     * keeps running, so a broken swap is invisible without this). Null = reflection
     * surface missing; empty = healthy. [clear] resets the captured list afterwards.
     */
    fun currentErrors(clear: Boolean): List<CapturedError>? {
        return try {
            val get = method("getCurrentErrors") ?: return null
            val raw = get.invoke(recomposerCompanion) as? List<*> ?: return null
            val errors = raw.filterNotNull().map { info ->
                fun call(name: String) = info.javaClass.methods
                    .firstOrNull { it.name == name }?.apply { isAccessible = true }?.invoke(info)
                val cause = call("getCause") as? Throwable
                val where = cause?.stackTrace?.firstOrNull()?.let { " at $it" } ?: ""
                CapturedError(
                    (cause?.toString() ?: info.toString()) + where,
                    call("getRecoverable") as? Boolean ?: false,
                )
            }
            if (clear) method("clearErrors")?.invoke(recomposerCompanion)
            Log.i(TAG, "currentErrors -> ${errors.size} (clear=$clear)")
            errors
        } catch (t: Throwable) {
            Log.e(TAG, "currentErrors failed", t)
            null
        }
    }

    /**
     * Tier-2: recreate every composition in place (save/dispose/load). No process
     * death, but composition state (remember AND rememberSaveable) resets.
     */
    fun hotReloadReset(): Boolean {
        return try {
            val save = method("saveStateAndDisposeForHotReload") ?: return false
            val load = method("loadStateAndComposeForHotReload") ?: return false
            val token = save.invoke(recomposerCompanion)
            load.invoke(recomposerCompanion, token)
            Log.i(TAG, "hot reload reset OK")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "hot reload reset failed", t)
            false
        }
    }
}
