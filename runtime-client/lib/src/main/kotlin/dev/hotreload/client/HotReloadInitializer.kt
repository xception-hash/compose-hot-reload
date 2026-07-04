package dev.hotreload.client

import android.content.Context
import android.util.Log
import androidx.startup.Initializer

/**
 * androidx.startup entry point — runs before any Activity (and thus before the first
 * composition, which setHotReloadEnabled requires). Registered in this AAR's manifest;
 * zero code changes in the host app.
 */
class HotReloadInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        Log.i("HotReload", "initializing (debuggable builds only)")
        ComposeBridge.enableHotReloadMode()
        ActivityTracker.install(context)
        HotSwap.attachAgent(context)
        PatchServer(context).start()
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}
