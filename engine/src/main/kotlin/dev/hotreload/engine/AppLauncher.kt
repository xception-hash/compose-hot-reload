package dev.hotreload.engine

import dev.hotreload.protocol.Protocol

/**
 * Launches the target app when it isn't already running. Extracted verbatim from
 * `WatchSession.ensureAppRunning` (T33 phase 6) so `prepare` can reuse it; every printed
 * line and the poll loop are byte-identical (the existing e2e greps prove it).
 */
internal object AppLauncher {
    /**
     * If the app is not running on the device, launch it (via `am start` if a launch
     * activity is configured, otherwise via `monkey`) and poll until it's alive.
     */
    fun ensureRunning(adb: Adb, appId: String, launchActivity: String?) {
        val (_, pidOut) = adb.safeShell("pidof", appId)
        val pid = pidOut.trim().split(Regex("\\s+")).firstOrNull()
        if (!pid.isNullOrEmpty() && pid.toLongOrNull() != null) return // already running

        // Launch the app.
        if (launchActivity != null) {
            adb.safeShell("am", "start", "-n", "$appId/$launchActivity")
        } else {
            adb.safeShell("monkey", "-p", appId, "-c", "android.intent.category.LAUNCHER", "1")
        }

        // Poll every 250 ms, up to 60 attempts (15 s). A live pid is NOT enough: the
        // process exists well before HotReloadInitializer binds the PatchServer socket
        // (Application.onCreate), and pinging in that window gets an EOF. Readiness =
        // the abstract socket appearing in /proc/net/unix.
        val socketToken = "@" + Protocol.deviceSocketName(appId)
        var launchedPid: String? = null
        repeat(60) {
            Thread.sleep(250)
            if (launchedPid == null) {
                val (_, pollOut) = adb.safeShell("pidof", appId)
                launchedPid = pollOut.trim().split(Regex("\\s+")).firstOrNull()
                    ?.takeIf { it.toLongOrNull() != null }
            }
            if (launchedPid != null) {
                val (_, unix) = adb.safeShell("cat", "/proc/net/unix")
                if (socketToken in unix) {
                    println("launched: $appId (pid $launchedPid)")
                    return
                }
            }
        }
        if (launchedPid != null) {
            // Process is up but the hot-reload socket never appeared (e.g. runtime-client
            // missing) — proceed and let the handshake fail with its established message.
            println("launched: $appId (pid $launchedPid)")
            return
        }
        throw IllegalStateException(
            "app $appId is not running and could not be launched" +
                " — start it on the device, or pass --launch-activity <activity>"
        )
    }
}
