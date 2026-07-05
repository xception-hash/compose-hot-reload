package dev.hotreload.cli

import dev.hotreload.engine.Doctor
import dev.hotreload.engine.WatchSession
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

private const val USAGE = """hotreload — Flutter-style hot reload for Jetpack Compose on Android

Usage:
  hotreload watch --project <dir> --app-id <applicationId> [options]
  hotreload doctor --project <dir> --app-id <applicationId> [options]

Options:
  --project <dir>     Gradle project of the app (required)
  --app-id <id>       applicationId of the debug build on the device (required)
  --module <names>    Comma-separated Gradle modules to watch; the FIRST is the app
                      module (default: app). Nested paths use '/', e.g. app,libs/core
  --sdk <dir>         Android SDK root (default: ${'$'}ANDROID_HOME)
  --build-tools <v>   build-tools version for d8 (default: 36.0.0)
"""

fun main(args: Array<String>) {
    if (args.isEmpty() || args[0] == "--help" || args[0] == "help") {
        println(USAGE)
        exitProcess(0)
    }

    val command = args[0]
    if (command != "watch" && command != "doctor") {
        println(USAGE)
        exitProcess(1)
    }

    val opts = parseOptions(args.drop(1))
    val project = Path.of(opts.required("--project")).toAbsolutePath().normalize()
    val appId = opts.required("--app-id")
    val modules = (opts["--module"] ?: "app").split(',').map { it.trim() }.filter { it.isNotEmpty() }
    if (modules.isEmpty()) fail("--module needs at least one module name")
    val sdk = Path.of(
        opts["--sdk"] ?: System.getenv("ANDROID_HOME")
            ?: fail("--sdk not given and ANDROID_HOME not set"),
    )
    val buildTools = opts["--build-tools"] ?: "36.0.0"

    if (command == "doctor") {
        val ok = Doctor(project, appId, modules, sdk, buildTools).run()
        exitProcess(if (ok) 0 else 1)
    }

    val d8 = sdk.resolve("build-tools/$buildTools/d8")
    val adb = sdk.resolve("platform-tools/adb")
    for ((what, path) in listOf("project dir" to project, "d8" to d8, "adb" to adb)) {
        if (!Files.exists(path)) fail("$what not found: $path")
    }

    try {
        WatchSession(WatchSession.Config(project, modules, appId, d8, adb)).run()
    } catch (t: Throwable) {
        fail(t.message ?: t.toString())
    }
}

private fun parseOptions(args: List<String>): Map<String, String> {
    val opts = mutableMapOf<String, String>()
    var i = 0
    while (i < args.size) {
        val key = args[i]
        if (!key.startsWith("--") || i + 1 >= args.size) fail("bad or valueless option: $key\n\n$USAGE")
        opts[key] = args[i + 1]
        i += 2
    }
    return opts
}

private fun Map<String, String>.required(key: String) = this[key] ?: fail("$key is required\n\n$USAGE")

private fun fail(message: String): Nothing {
    System.err.println("hotreload: $message")
    exitProcess(1)
}
