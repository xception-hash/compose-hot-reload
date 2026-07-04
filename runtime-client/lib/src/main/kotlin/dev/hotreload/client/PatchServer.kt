package dev.hotreload.client

import android.content.Context
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import dev.hotreload.protocol.ComposeErrorInfo
import dev.hotreload.protocol.Protocol
import dev.hotreload.protocol.Request
import dev.hotreload.protocol.Response
import dev.hotreload.protocol.Wire
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * On-device protocol server. Listens on an abstract-namespace Unix domain socket
 * ([Protocol.deviceSocketName] — a TCP bind would need the INTERNET permission, which
 * the host app must not be forced to declare); the engine connects through
 * `adb forward tcp:<local> localabstract:<name>`. Connections are handled serially,
 * requests strictly in order; every request gets exactly one response on the same socket.
 */
class PatchServer(private val context: Context) {
    private val tag = "HotReloadServer"
    private val mainHandler = Handler(Looper.getMainLooper())
    private val safeName = Regex("[A-Za-z0-9._-]+")

    fun start() {
        Thread({ serve() }, "hotreload-server").apply { isDaemon = true }.start()
    }

    private fun serve() {
        val name = Protocol.deviceSocketName(context.packageName)
        val server = try {
            LocalServerSocket(name) // abstract namespace: no file, no permission
        } catch (t: Throwable) {
            Log.e(tag, "cannot bind localabstract:$name", t)
            return
        }
        Log.i(tag, "listening on localabstract:$name")
        while (true) {
            try {
                val socket = server.accept()
                try {
                    session(socket)
                } finally {
                    socket.close()
                }
            } catch (t: Throwable) {
                Log.e(tag, "session error", t)
            }
        }
    }

    private fun session(socket: LocalSocket) {
        Log.i(tag, "engine connected")
        val input = socket.getInputStream().buffered()
        val output = socket.getOutputStream().buffered()
        while (true) {
            val request = Wire.readRequest(input) ?: break
            val response = try {
                handle(request)
            } catch (t: Throwable) {
                Log.e(tag, "request ${request.requestId} failed", t)
                Response.Failure(request.requestId, -1, t.toString())
            }
            Wire.write(output, response)
        }
        Log.i(tag, "engine disconnected")
    }

    private fun handle(request: Request): Response = when (request) {
        is Request.Ping -> Response.Capabilities(
            request.requestId,
            Protocol.VERSION,
            Build.VERSION.SDK_INT,
            HotSwap.attached && HotSwap.nativeCanRedefine(),
            HotSwap.attached && HotSwap.nativeHasStructural(),
            HotSwap.attached && HotSwap.nativeHasInjectFile(),
            ComposeBridge.available,
        )

        is Request.Redefine -> {
            val targets = request.classes
                .map { Class.forName(it.className, false, context.classLoader) }
                .toTypedArray()
            val dexFiles = request.classes.map { it.dexBytes }.toTypedArray()
            val err = HotSwap.nativeRedefine(targets, dexFiles, request.structural)
            if (err == 0) {
                Response.Ack(request.requestId)
            } else {
                Response.Failure(request.requestId, err, HotSwap.nativeGetLastError() ?: "jvmti error $err")
            }
        }

        is Request.InjectDex -> {
            require(safeName.matches(request.name)) { "bad dex name: ${request.name}" }
            // The in-memory extension trips ART's writable-dex check (API 36), so
            // land the dex read-only in app-private storage and use the file variant.
            val dex = File(context.codeCacheDir, request.name)
            dex.delete()
            dex.writeBytes(request.dexBytes)
            dex.setReadOnly()
            val err = HotSwap.nativeInjectDexFile(context.classLoader, dex.absolutePath)
            if (err == 0) {
                Response.Ack(request.requestId)
            } else {
                Response.Failure(request.requestId, err, HotSwap.nativeGetLastError() ?: "jvmti error $err")
            }
        }

        is Request.GetErrors -> Response.ComposeErrors(
            request.requestId,
            ComposeBridge.currentErrors(request.clear)
                ?.map { ComposeErrorInfo(it.message, it.recoverable) }
                ?: emptyList(),
        )

        is Request.Invalidate -> onMainThread(request.requestId) {
            request.keys.all { ComposeBridge.invalidateGroupsWithKey(it) }
        }

        is Request.Reset -> onMainThread(request.requestId) {
            ComposeBridge.hotReloadReset()
        }
    }

    /** Runs [block] on the main thread and waits, so the response reflects the outcome. */
    private fun onMainThread(requestId: Int, block: () -> Boolean): Response {
        var ok = false
        var error: Throwable? = null
        val done = CountDownLatch(1)
        mainHandler.post {
            try {
                ok = block()
            } catch (t: Throwable) {
                error = t
            } finally {
                done.countDown()
            }
        }
        if (!done.await(10, TimeUnit.SECONDS)) {
            return Response.Failure(requestId, -1, "main thread did not respond within 10s")
        }
        error?.let { return Response.Failure(requestId, -1, it.toString()) }
        return if (ok) Response.Ack(requestId) else Response.Failure(requestId, -1, "ComposeBridge call failed (see logcat)")
    }
}
