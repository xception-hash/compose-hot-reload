package dev.hotreload.client

import android.content.Context
import android.os.Debug
import android.util.Log

/**
 * Agent attach + JNI surface of libhotreload_agent.so (packaged in this AAR).
 * All natives return a JVMTI error code (0 = OK) or agent-local negatives:
 * -100 not attached/capability denied, -101 no structural ext, -103 no inject ext,
 * -104 bad arguments.
 */
object HotSwap {
    private const val TAG = "HotSwap"

    @Volatile
    var attached: Boolean = false
        private set

    fun attachAgent(context: Context): Boolean {
        if (attached) return true
        // Do NOT pass an absolute path: install dirs contain "==" (base64) and
        // attachJvmtiAgent rejects any '=' in the library string. The bare name
        // resolves through the given classloader's native library namespace
        // (requires jniLibs useLegacyPackaging in the consuming app).
        return try {
            Debug.attachJvmtiAgent("libhotreload_agent.so", null, context.classLoader)
            System.loadLibrary("hotreload_agent") // bind the JNI natives in this classloader
            attached = true
            Log.i(TAG, "agent attached; canRedefine=${nativeCanRedefine()} structural=${nativeHasStructural()} injectFile=${nativeHasInjectFile()}")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "agent attach failed", t)
            false
        }
    }

    /** Atomic multi-class redefine; arrays are parallel (targets[i] gets dexFiles[i]). */
    external fun nativeRedefine(targets: Array<Class<*>>, dexFiles: Array<ByteArray>, structural: Boolean): Int
    external fun nativeInjectDexFile(loader: ClassLoader, path: String): Int

    /**
     * Register the interpreter's `com.android.tools.deploy.interpreter.JNI` natives (the
     * invokespecial* / enter-exitMonitor dispatch in interpreter_jni.cpp) via `RegisterNatives`
     * on [jniClass]. Must be called AFTER interp.dex is injected — the class does not exist before
     * that, so [jniClass] is resolved by the caller through the app classloader. Idempotent;
     * returns 0 on success. See [dev.hotreload.client.LiveEditInterp].
     */
    external fun nativeRegisterInterpreterJni(jniClass: Class<*>): Int

    external fun nativeGetLastError(): String?
    external fun nativeCanRedefine(): Boolean
    external fun nativeHasStructural(): Boolean
    external fun nativeHasInjectFile(): Boolean
}
