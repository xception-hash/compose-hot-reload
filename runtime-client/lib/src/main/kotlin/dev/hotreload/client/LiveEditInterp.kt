package dev.hotreload.client

import android.util.Log
import java.lang.reflect.Method

/**
 * Reflective bridge to the AOSP LiveEdit bytecode interpreter shipped in `interp.dex` (T27). The
 * AAR never compiles against the interpreter classes — they only exist in the app process after the
 * engine injects `interp.dex` (via the existing [Request.InjectDex][dev.hotreload.protocol.Request.InjectDex]
 * op) — so everything here is by-name reflection through the app classloader.
 *
 * Handles the [Request.LiveEditClasses][dev.hotreload.protocol.Request.LiveEditClasses] flow:
 * one-time [ensureInitialized] (register the interpreter JNI natives + `LiveEditStubs.init`), then
 * [addClasses] per request. Called only from [PatchServer]'s serial main-thread handler, so no
 * additional synchronization is needed.
 */
object LiveEditInterp {
    private const val TAG = "HotReloadInterp"
    private const val STUBS = "com.android.tools.deploy.liveedit.LiveEditStubs"
    private const val JNI = "com.android.tools.deploy.interpreter.JNI"

    private var initialized = false
    private var addClassesMethod: Method? = null

    private val noProxies = emptyArray<ByteArray>()

    /**
     * First-use-per-process setup: resolve the just-injected interpreter classes through [loader],
     * register the interpreter's JNI natives on `interpreter.JNI`, and call `LiveEditStubs.init`.
     * Idempotent. Throws if the interpreter dex wasn't injected first or native registration fails.
     */
    fun ensureInitialized(loader: ClassLoader) {
        if (initialized) return

        // interpreter.JNI's natives (super.m() / synchronized) must be registered on the exact
        // class object the interpreter uses — resolve it via the app loader interp.dex went into.
        val jniClass = Class.forName(JNI, false, loader)
        val rc = HotSwap.nativeRegisterInterpreterJni(jniClass)
        check(rc == 0) { "nativeRegisterInterpreterJni failed: $rc" }

        val stubs = Class.forName(STUBS, false, loader)
        stubs.getMethod("init", ClassLoader::class.java).invoke(null, loader)

        val byteArrayArray = Array<ByteArray>::class.java // byte[][]
        addClassesMethod = stubs.getMethod(
            "addClasses", byteArrayArray, byteArrayArray, Boolean::class.javaPrimitiveType,
        )

        initialized = true
        Log.i(TAG, "interpreter initialized")
    }

    /**
     * Reflectively `LiveEditStubs.addClasses(primary, [], structural=false)` — store the edited JVM
     * `.class` bytes so the primed stubs interpret the latest bytecode. Priming (structural redefine
     * of the transformed baseline) is handled engine-side via the existing Redefine op; this only
     * updates the interpreter's per-class bytecode.
     */
    fun addClasses(primaryClasses: Array<ByteArray>) {
        val method = addClassesMethod ?: error("LiveEditInterp not initialized")
        method.invoke(null, primaryClasses, noProxies, false)
    }
}
