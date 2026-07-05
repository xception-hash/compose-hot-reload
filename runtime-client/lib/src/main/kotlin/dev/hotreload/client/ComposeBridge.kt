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
     * the Recomposer's `_knownCompositions` (List<ControlledComposition>) →
     * `invalidateAll()` on each. Field names carry the underscore prefix and the
     * composition list lives on the Recomposer INSTANCE, not the Companion. Must run on
     * the main thread.
     */
    fun invalidateAllCompositions(): Boolean {
        return try {
            val compositions = knownCompositions() ?: return false
            var count = 0
            for (c in compositions) {
                val m = c.javaClass.methods
                    .firstOrNull { it.name == "invalidateAll" && it.parameterCount == 0 }
                    ?.apply { isAccessible = true }
                    ?: run { Log.e(TAG, "invalidateAll not found on ${c.javaClass.name}"); return false }
                m.invoke(c)
                count++
            }
            Log.i(TAG, "invalidateAll on $count compositions OK")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "invalidateAllCompositions failed", t)
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
     * `androidx.compose.runtime.internal.LiveLiteralKt` — the live-literals runtime
     * (T24). Present only when the app was compiled with the Compose compiler's
     * live-literals codegen (`-Photreload.liveLiterals=true`); null otherwise.
     */
    private val liveLiteralKt: Class<*>? by lazy {
        try {
            Class.forName("androidx.compose.runtime.internal.LiveLiteralKt")
        } catch (t: Throwable) {
            Log.e(TAG, "LiveLiteralKt unavailable (app not built with liveLiterals?)", t)
            null
        }
    }

    /** Global live-literals flag: idempotent, enabled once per process. */
    @Volatile
    private var literalsEnabled = false

    /** Helper classes whose per-file `enabled` flag we have already flipped. */
    private val enabledHelpers = java.util.Collections.synchronizedSet(HashSet<String>())

    /**
     * Live-literals fast path (T24): set [key] (owned by [helperClass]) to [value] in place.
     *
     * Live-literals **v2** gates each generated getter on TWO flags, both of which we must
     * flip (mirrors AOSP `LiveLiteralSupport.enableGlobal` + `enableHelperClass`):
     *  1. the global `LiveLiteralKt.isLiveLiteralsEnabled`, and
     *  2. a per-file `enabled` boolean field on the `LiveLiterals$*Kt` [helperClass] itself
     *     — WITHOUT this the getter returns its compile-time default and the update is
     *     invisible (the bug that made v1 stick on screen).
     *
     * Then `LiveLiteralKt.updateLiveLiteralValue(key, value)` writes into the Compose
     * `MutableState` backing the literal, and we recompose so the getter re-reads it.
     *
     * Recomposition is the subtle part. We enable lazily (to keep the flag's cost off
     * non-`--literals` runs), so the composition that first drew the literal took the
     * disabled branch and isn't subscribed to the state. A plain [invalidateAllCompositions]
     * marks the scopes invalid but does NOT wake the Recomposer's frame loop when the app is
     * idle — verified live: the value only appeared on the *next* unrelated edit. The
     * Recomposer's own hot-reload entrypoint [invalidateGroupsWithKey] DOES wake it, so we
     * invalidate the enclosing composable's key ([invalidateKey]); combined with the whole-
     * tree invalidate (marks every scope, incl. the literal's) the pumped frame drains them
     * all with `remember`/`rememberSaveable` preserved. Names are matched by prefix to
     * tolerate `$runtime_release` mangling. Must run on the main thread.
     */
    fun updateLiteral(key: String, helperClass: String, invalidateKey: Int, value: Any): Boolean {
        return try {
            val cls = liveLiteralKt ?: return false
            if (!literalsEnabled) {
                val enabledField = cls.declaredFields
                    .firstOrNull { it.name.startsWith("isLiveLiteralsEnabled") }
                    ?.apply { isAccessible = true }
                if (enabledField?.getBoolean(null) != true) {
                    val enable = cls.declaredMethods
                        .firstOrNull { it.name.startsWith("enableLiveLiterals") && it.parameterCount == 0 }
                        ?.apply { isAccessible = true }
                        ?: run { Log.e(TAG, "enableLiveLiterals not found"); return false }
                    enable.invoke(null)
                }
                literalsEnabled = true
            }
            if (enabledHelpers.add(helperClass) && !enableHelper(helperClass)) return false

            val update = cls.declaredMethods
                .firstOrNull { it.name.startsWith("updateLiveLiteralValue") && it.parameterCount == 2 }
                ?.apply { isAccessible = true }
                ?: run { Log.e(TAG, "updateLiveLiteralValue not found"); return false }
            update.invoke(null, key, value)
            // Mark every scope invalid (incl. the literal's), then wake the Recomposer. The
            // whole-tree invalidate alone won't pump a frame; invalidateGroupsWithKey on the
            // enclosing composable does. If the engine couldn't resolve a key, fall back to a
            // forced frame (best-effort — may lag until the next natural frame).
            invalidateAllCompositions()
            if (invalidateKey != 0) invalidateGroupsWithKey(invalidateKey) else pumpFrame()
            Log.i(TAG, "updateLiteral($helperClass#$key = $value, invKey=$invalidateKey) OK")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "updateLiteral($key) failed", t)
            false
        }
    }

    /**
     * Force a Choreographer frame so the Recomposer's frame clock ticks and it drains any
     * pending recompositions (an `invalidateAll` with no other activity would otherwise sit
     * until the next natural frame). Best-effort; must run on the main thread.
     */
    private fun pumpFrame() {
        try {
            android.view.Choreographer.getInstance().postFrameCallback { }
            ActivityTracker.current?.window?.decorView?.invalidate()
        } catch (t: Throwable) {
            Log.w(TAG, "pumpFrame failed", t)
        }
    }

    /** Flip the per-file `enabled` flag on a `LiveLiterals$*Kt` class (live-literals v2). */
    private fun enableHelper(helperClass: String): Boolean {
        return try {
            val helper = Class.forName(helperClass, false, javaClass.classLoader)
            val enabled = helper.declaredFields.firstOrNull { it.name == "enabled" }
                ?.apply { isAccessible = true }
                ?: run { Log.e(TAG, "no 'enabled' field on $helperClass"); return false }
            enabled.setBoolean(null, true)
            true
        } catch (t: Throwable) {
            Log.e(TAG, "enableHelper($helperClass) failed", t)
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
