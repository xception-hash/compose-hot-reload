package dev.hotreload.client

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.util.Log
import java.lang.ref.WeakReference

/**
 * Tracks the currently-resumed Activity so a resource overlay can be attached to the
 * exact `Resources` instance Compose reads (`LocalContext.current.resources` == the
 * activity's `Resources`, T14). The androidx.startup initializer installs this before
 * any Activity is created, so no host-app code changes are needed.
 */
object ActivityTracker {
    private const val TAG = "HotReloadActivity"

    @Volatile
    private var currentRef: WeakReference<Activity>? = null

    /** The resumed Activity, or null if none is foregrounded. */
    val current: Activity?
        get() = currentRef?.get()

    fun install(context: Context) {
        val app = context.applicationContext as? Application ?: run {
            Log.w(TAG, "application context is not an Application; overlays limited to app resources")
            return
        }
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                currentRef = WeakReference(activity)
            }

            override fun onActivityPaused(activity: Activity) {
                if (currentRef?.get() === activity) currentRef = null
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }
}
