package dev.hotreload.client

import android.util.Log
import java.lang.reflect.Modifier

internal fun setLiveLiteralBackingField(helper: Class<*>, key: String, value: Any) {
    val enabled = helper.declaredFields
        .firstOrNull { it.name == "enabled" && it.type == Boolean::class.javaPrimitiveType }
        ?.apply { isAccessible = true }
        ?: error("no boolean 'enabled' field on ${helper.name}")
    require(Modifier.isStatic(enabled.modifiers)) { "'enabled' is not static on ${helper.name}" }

    val backing = helper.declaredFields
        .firstOrNull { it.name == key }
        ?.apply { isAccessible = true }
        ?: error("no backing field '$key' on ${helper.name}")
    require(Modifier.isStatic(backing.modifiers)) { "backing field '$key' is not static" }
    require(!Modifier.isFinal(backing.modifiers)) { "backing field '$key' is final" }

    // Keep the generated getter on its direct backing-field branch. Enabling the helper would
    // switch it to a lazily-created State that the existing composition has never observed.
    enabled.setBoolean(null, false)
    backing.set(null, value)
}

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

    /** Compose runtime version read from androidx.compose.runtime.ComposeVersion.version via reflection (0 when absent). */
    val composeVersion: Int
        get() = try {
            val cls = Class.forName("androidx.compose.runtime.ComposeVersion")
            val field = try { cls.getDeclaredField("version").apply { isAccessible = true } } catch (_: Throwable) { null }
            if (field != null) {
                field.getInt(null)
            } else {
                val method = cls.declaredMethods.firstOrNull { it.name == "getVersion" || it.name == "version" }?.apply { isAccessible = true }
                (method?.invoke(null) as? Int) ?: 0
            }
        } catch (_: Throwable) {
            0
        }

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
     * the Recomposer's `_knownCompositions` (List<ControlledComposition>) → `invalidateAll()` on
     * each. Field names carry the underscore prefix and the composition list lives on the
     * Recomposer INSTANCE, not the Companion. Must run on the main thread.
     */
    fun invalidateAllCompositions(): Boolean {
        return try {
            val compositions = knownCompositions() ?: return false
            var count = 0
            for (composition in compositions) {
                val invalidate = composition.javaClass.methods
                    .firstOrNull { it.name == "invalidateAll" && it.parameterCount == 0 }
                    ?.apply { isAccessible = true }
                    ?: run { Log.e(TAG, "invalidateAll not found on ${composition.javaClass.name}"); return false }
                invalidate.invoke(composition)
                count++
            }
            Log.i(TAG, "invalidateAll on $count compositions OK")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "invalidateAllCompositions failed", t)
            false
        }
    }

    /** Force stable scopes to execute for a keyless code change without discarding slots. */
    fun forceRecomposeAllCompositions(): Boolean = recomposeAllCompositionsNow()

    /**
     * Synchronously re-run every root composition with its existing content lambda after disabling
     * scope skipping. Unlike live-edit's keyed invalidation this keeps the slot table in place, so
     * unchanged groups retain their remembered values. Used when a generated live-literal backing
     * field changes: the field is not snapshot state, so merely requesting a frame cannot make a
     * skipped scope read it.
     */
    private fun recomposeAllCompositionsNow(): Boolean {
        return try {
            val compositions = knownCompositions() ?: return false
            var count = 0
            for (composition in compositions) {
                val composerField = declaredField(composition.javaClass, "composer")
                    ?: run { Log.e(TAG, "composer field not found on ${composition.javaClass.name}"); return false }
                val composer = composerField.get(composition)
                    ?: run { Log.e(TAG, "composer is null"); return false }
                val force = composer.javaClass.methods
                    .firstOrNull {
                        it.name.substringBefore('$') == "forceRecomposeScopes" &&
                            it.parameterCount == 0
                    }
                    ?.apply { isAccessible = true }
                    ?: run { Log.e(TAG, "forceRecomposeScopes not found on ${composer.javaClass.name}"); return false }
                force.invoke(composer)

                val contentField = declaredField(composition.javaClass, "composable")
                    ?: run { Log.e(TAG, "composable field not found on ${composition.javaClass.name}"); return false }
                val content = contentField.get(composition)
                    ?: run { Log.e(TAG, "composable content is null"); return false }
                val setContent = composition.javaClass.methods
                    .firstOrNull { it.name == "setContent" && it.parameterCount == 1 }
                    ?.apply { isAccessible = true }
                    ?: run { Log.e(TAG, "setContent not found on ${composition.javaClass.name}"); return false }
                setContent.invoke(composition, content)
                count++
            }
            Log.i(TAG, "setContent on $count compositions OK")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "recomposeAllCompositionsNow failed", t)
            false
        }
    }

    /**
     * Every live ControlledComposition, de-duped by identity (one composition can be
     * reached via multiple recomposers). Null = the reflection route is broken.
     */
    private fun knownCompositions(): List<Any>? {
        val recomposer = Class.forName("androidx.compose.runtime.Recomposer")
        val running = declaredField(recomposer, "_runningRecomposers")
            ?: run { Log.e(TAG, "_runningRecomposers not found"); return null }
        val stateFlow = running.get(null) ?: run { Log.e(TAG, "_runningRecomposers is null"); return null }
        val value = stateFlow.javaClass.methods
            .firstOrNull { it.name == "getValue" && it.parameterCount == 0 }
            ?.apply { isAccessible = true }?.invoke(stateFlow)
        val infos = value as? Set<*> ?: run { Log.e(TAG, "runningRecomposers value not a Set"); return null }

        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
        val result = ArrayList<Any>()
        for (info in infos) {
            if (info == null) continue
            val outer = declaredField(info.javaClass, "this\$0")?.get(info)
                ?: run { Log.e(TAG, "RecomposerInfoImpl.this\$0 not found"); return null }
            val known = declaredField(outer.javaClass, "_knownCompositions")
                ?: run { Log.e(TAG, "_knownCompositions not found"); return null }
            val compositions = known.get(outer) as? List<*> ?: continue
            for (c in compositions) {
                if (c != null && seen.add(c)) result.add(c)
            }
        }
        return result
    }

    /** Declared field on [cls] whose name starts with [prefix] (tolerates name mangling). */
    private fun declaredField(cls: Class<*>, prefix: String) =
        cls.declaredFields.firstOrNull { it.name.startsWith(prefix) }?.apply { isAccessible = true }

    /** WeakReferences to the cache objects the last scan found, so repeat edits skip the walk. */
    private val cachedAssetCaches = ArrayList<java.lang.ref.WeakReference<Any>>()

    /**
     * Clear Compose's Context-scoped asset caches (ImageVectorCache + ResourceIdCache) so
     * a resource overlay's drawables re-decode on the next recomposition. Without this,
     * freshness is a GC lottery: the cache holds entries weakly, so a drawable edit shows
     * up only if a GC happened to run since the icon last rendered (observed live: first
     * edit fresh, second stale — T16's "always stale" was the same lottery, other side).
     *
     * Compose's own clear path (ComponentCallbacks2.onTrimMemory, proven live via
     * `am send-trim-memory`) is unreachable in-process: the registration list
     * (`Application.mComponentCallbacks`) is hidden-API-filtered — the field is invisible
     * to `getDeclaredFields`, not merely inaccessible. But the cache INSTANCES are plain
     * app-classpath objects: at ui 1.11 they hang off `ComposeViewContext` (reachable from
     * the root composition's composable lambda capture); at 1.9 they were remember slots.
     * Rather than hardcoding either layout, do a bounded BFS over the object graph from
     * every known composition, expanding only androidx.* objects and arrays, and clear()
     * every `androidx.compose.ui.res.*Cache` encountered (found within ~113 objects live).
     * No framework hidden APIs; must run on the main thread so the clear is ordered
     * strictly between the loader attach and the invalidation.
     *
     * Returns the number of caches cleared; 0 means none were found (drawable edits
     * would stay stale — logged loudly, but not fatal: value edits still work).
     */
    fun clearAssetCaches(): Int {
        val caches = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
        try {
            cachedAssetCaches.mapNotNullTo(caches) { it.get() }
            if (caches.isEmpty()) {
                val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
                val queue = ArrayDeque<Any>()
                fun encounter(obj: Any) {
                    if (!seen.add(obj)) return
                    val name = obj.javaClass.name
                    if (name.startsWith("androidx.compose.ui.res.") && name.endsWith("Cache")) caches.add(obj)
                    queue.add(obj)
                }
                knownCompositions().orEmpty().forEach(::encounter)
                var visited = 0
                while (queue.isNotEmpty() && visited < 50_000) {
                    val obj = queue.removeFirst(); visited++
                    if (obj is Array<*>) {
                        for (e in obj) e?.let(::encounter)
                        continue
                    }
                    // Only androidx internals can lead to the caches; expanding framework
                    // or app objects (Views, contexts) would drag in the whole heap.
                    if (!obj.javaClass.name.startsWith("androidx.")) continue
                    var c: Class<*>? = obj.javaClass
                    while (c != null && c != Any::class.java) {
                        for (f in c.declaredFields) {
                            if (f.type.isPrimitive) continue
                            try {
                                f.isAccessible = true
                                f.get(obj)?.let(::encounter)
                            } catch (_: Throwable) {
                                // Inaccessible field: skip, keep walking.
                            }
                        }
                        c = c.superclass
                    }
                }
                cachedAssetCaches.clear()
                caches.mapTo(cachedAssetCaches) { java.lang.ref.WeakReference(it) }
                Log.i(TAG, "asset-cache scan visited $visited objects")
            }
            for (cache in caches) {
                val clear = cache.javaClass.declaredMethods
                    .firstOrNull { it.name == "clear" && it.parameterCount == 0 }
                    ?.apply { isAccessible = true }
                clear?.invoke(cache) ?: Log.e(TAG, "no clear() on ${cache.javaClass.name}")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "clearAssetCaches failed", t)
        }
        if (caches.isEmpty()) {
            Log.e(TAG, "clearAssetCaches found no compose asset caches — drawable edits may stay stale")
        } else {
            Log.i(TAG, "cleared ${caches.size} compose asset cache(s)")
        }
        return caches.size
    }

    /**
     * Live-literals fast path (T24): set [key] (owned by [helperClass]) to [value] in place.
     *
     * Live-literals v2 emits a mutable static field named exactly [key], plus an `enabled`
     * switch. The disabled getter branch reads that field directly. Updating the generated field
     * while leaving the helper disabled avoids switching an already-rendered composition to a
     * lazily-created `State` it has never observed. A state-preserving whole-tree invalidation then
     * makes the getter re-read the field without bashing the enclosing group (which Compose's keyed
     * hot-reload invalidation would do). [invalidateKey] remains in the protocol for compatibility
     * and diagnostics. Must run on the main thread.
     */
    fun updateLiteral(key: String, helperClass: String, invalidateKey: Int, value: Any): Boolean {
        return try {
            val helper = Class.forName(helperClass, false, javaClass.classLoader)
            setLiveLiteralBackingField(helper, key, value)
            if (!forceRecomposeAllCompositions()) return false
            Log.i(TAG, "updateLiteral($helperClass#$key = $value, invKey=$invalidateKey) OK")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "updateLiteral($key) failed", t)
            false
        }
    }

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
