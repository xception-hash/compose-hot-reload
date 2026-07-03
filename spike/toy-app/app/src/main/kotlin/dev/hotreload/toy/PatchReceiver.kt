package dev.hotreload.toy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File

/**
 * Spike-only transport: adb pushes a single-class dex to the app's external files dir,
 * then triggers this receiver.
 *
 * adb shell am broadcast -n dev.hotreload.toy/.PatchReceiver -a dev.hotreload.toy.PATCH \
 *   --es file patch.dex --es cls dev.hotreload.toy.MainActivityKt --ei key <groupKey> \
 *   [--ez structural true] [--ez reset true]
 */
class PatchReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val tag = "PatchReceiver"
        val main = Handler(Looper.getMainLooper())

        if (intent.getBooleanExtra("reset", false)) {
            main.post { ComposeBridge.hotReloadReset() }
            return
        }

        val fileName = intent.getStringExtra("file")
        val className = intent.getStringExtra("cls")
        val key = intent.getIntExtra("key", 0)
        val structural = intent.getBooleanExtra("structural", false)

        if (fileName != null && className != null) {
            val dex = File(context.getExternalFilesDir(null), fileName)
            if (!dex.exists()) {
                Log.e(tag, "no such patch file: $dex")
                return
            }
            val bytes = dex.readBytes()
            val target = Class.forName(className)
            val err = if (structural) {
                HotSwap.nativeRedefineStructural(target, bytes)
            } else {
                HotSwap.nativeRedefine(target, bytes)
            }
            Log.i(tag, "redefine($className, ${bytes.size}B, structural=$structural) -> $err")
            if (err != 0) return
        }

        if (key != 0) {
            main.post { ComposeBridge.invalidateGroupsWithKey(key) }
        }
    }
}
