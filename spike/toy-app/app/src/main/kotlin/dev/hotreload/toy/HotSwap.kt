package dev.hotreload.toy

import android.content.Context
import android.os.Debug
import android.util.Log

object HotSwap {
    private const val TAG = "HotSwap"

    fun attachAgent(context: Context) {
        // Do NOT pass an absolute path: install dirs contain "==" (base64) and
        // attachJvmtiAgent rejects any '=' in the library string. The bare name
        // resolves through the given classloader's native library namespace.
        val lib = "libhotreload_agent.so"
        try {
            Debug.attachJvmtiAgent(lib, null, context.classLoader)
            System.loadLibrary("hotreload_agent") // bind the JNI natives in this classloader
            Log.i(TAG, "agent attached from $lib; structural=${nativeHasStructural()}")
        } catch (t: Throwable) {
            Log.e(TAG, "agent attach failed", t)
        }
    }

    external fun nativeRedefine(target: Class<*>, dexBytes: ByteArray): Int
    external fun nativeRedefineStructural(target: Class<*>, dexBytes: ByteArray): Int
    external fun nativeHasStructural(): Boolean
    external fun nativeInjectDex(loader: ClassLoader, dexBytes: ByteArray): Int
    external fun nativeInjectDexFile(loader: ClassLoader, path: String): Int

    // T30 item 1: RegisterNatives for interpreter.JNI (invokespecial*/enter-exitMonitor), bound
    // via interp_bridge.cpp -> runtime-client's interpreter_jni.cpp (compiled verbatim into this
    // app's .so). Call AFTER interp.dex is injected, with the JNI class resolved through the app
    // classloader — mirrors production LiveEditInterp.ensureInitialized. Idempotent; 0 = OK.
    external fun nativeRegisterInterpreterJni(jniClass: Class<*>): Int
}
