package dev.hotreload.client

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import androidx.startup.Initializer

/**
 * androidx.startup entry point — runs before any Activity (and thus before the first
 * composition, which setHotReloadEnabled requires). Registered in this AAR's manifest;
 * zero code changes in the host app.
 */
class HotReloadInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        // Belt-and-braces on top of the plugin's `debugImplementation` wiring: never expose
        // the injection surface on a release/non-debuggable build, even if the dependency is
        // hand-added to a release classpath or a whitelabel build flips `debuggable`.
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) {
            Log.w("HotReload", "not a debuggable build — hot reload disabled, no server started")
            return
        }
        Log.i("HotReload", "initializing (debuggable builds only)")
        ComposeBridge.enableHotReloadMode()
        ActivityTracker.install(context)
        HotSwap.attachAgent(context)
        PatchServer(context).start()
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}
