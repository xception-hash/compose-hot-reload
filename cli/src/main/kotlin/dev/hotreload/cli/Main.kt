package dev.hotreload.cli

import dev.hotreload.engine.WatchSession
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

private const val USAGE = """hotreload — Flutter-style hot reload for Jetpack Compose on Android

Usage: hotreload watch --project <dir> --app-id <applicationId> [options]

Options:
  --project <dir>     Gradle project of the app (required)
  --app-id <id>       applicationId of the debug build on the device (required)
  --module <name>     Gradle module containing the app (default: app)
  --sdk <dir>         Android SDK root (default: ${'$'}ANDROID_HOME)
  --build-tools <v>   build-tools version for d8 (default: 36.0.0)
"""

fun main(args: Array<String>) {
    if (args.isEmpty() || args[0] != "watch") {
        println(USAGE)
        exitProcess(if (args.firstOrNull() == "--help") 0 else 1)
    }

    val opts = parseOptions(args.drop(1))
    val project = Path.of(opts.required("--project")).toAbsolutePath().normalize()
    val appId = opts.required("--app-id")
    val module = opts["--module"] ?: "app"
    val sdk = Path.of(
        opts["--sdk"] ?: System.getenv("ANDROID_HOME")
            ?: fail("--sdk not given and ANDROID_HOME not set"),
    )
    val buildTools = opts["--build-tools"] ?: "36.0.0"

    val d8 = sdk.resolve("build-tools/$buildTools/d8")
    val adb = sdk.resolve("platform-tools/adb")
    for ((what, path) in listOf("project dir" to project, "d8" to d8, "adb" to adb)) {
        if (!Files.exists(path)) fail("$what not found: $path")
    }

    try {
        WatchSession(WatchSession.Config.forProject(project, module, appId, d8, adb)).run()
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
