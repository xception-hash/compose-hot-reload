package dev.hotreload.toy

import android.util.Log

/**
 * Reflection shims into Compose runtime internals. Every signature discovered here
 * goes into docs/phase0-findings.md verbatim.
 */
object ComposeBridge {
    private const val TAG = "ComposeBridge"

    private val recomposerCompanion: Any by lazy {
        val recomposer = Class.forName("androidx.compose.runtime.Recomposer")
        val field = recomposer.getDeclaredField("Companion").apply { isAccessible = true }
        field.get(null)
    }

    /** Log every companion method once so the findings doc records exact signatures. */
    fun dumpApiSurface() {
        recomposerCompanion.javaClass.declaredMethods.forEach {
            Log.i(TAG, "Recomposer.Companion method: $it")
        }
    }

    /** Must run before any composition exists so compositions register hot-reload state. */
    fun enableHotReloadMode() {
        try {
            val m = recomposerCompanion.javaClass.declaredMethods
                .first { it.name.startsWith("setHotReloadEnabled") }
                .apply { isAccessible = true }
            m.invoke(recomposerCompanion, true)
            Log.i(TAG, "setHotReloadEnabled(true) OK")
        } catch (t: Throwable) {
            Log.e(TAG, "setHotReloadEnabled failed", t)
        }
    }

    fun invalidateGroupsWithKey(key: Int): Boolean {
        return try {
            val m = recomposerCompanion.javaClass.declaredMethods
                .first { it.name.startsWith("invalidateGroupsWithKey") }
                .apply { isAccessible = true }
            m.invoke(recomposerCompanion, key)
            Log.i(TAG, "invalidateGroupsWithKey($key) invoked via $m")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "invalidateGroupsWithKey($key) failed", t)
            false
        }
    }

    /** Tier-2 fallback: full composition reset preserving rememberSaveable. */
    fun hotReloadReset(): Boolean {
        return try {
            val methods = recomposerCompanion.javaClass.declaredMethods
            val save = methods.first { it.name.startsWith("saveStateAndDisposeForHotReload") }
                .apply { isAccessible = true }
            val load = methods.first { it.name.startsWith("loadStateAndComposeForHotReload") }
                .apply { isAccessible = true }
            val token = save.invoke(recomposerCompanion)
            load.invoke(recomposerCompanion, token)
            Log.i(TAG, "hot reload reset OK (token=${token?.javaClass})")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "hot reload reset failed", t)
            false
        }
    }
}
