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
