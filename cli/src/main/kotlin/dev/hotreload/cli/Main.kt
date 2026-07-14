package dev.hotreload.cli

import dev.hotreload.engine.Doctor
import dev.hotreload.engine.DiscoveryReport
import dev.hotreload.engine.GradleDiscovery
import dev.hotreload.engine.ModuleSpec
import dev.hotreload.engine.ProjectConfig
import dev.hotreload.engine.WatchSession
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

private const val USAGE = """hotreload — Flutter-style hot reload for Jetpack Compose on Android

Usage:
  hotreload watch --project <dir> --app-id <applicationId> [options]
  hotreload doctor --project <dir> --app-id <applicationId> [options]
  hotreload inspect --project <dir> [options]

Options:
  --project <dir>     Gradle project of the app (required)
  --app-id <id>       applicationId of the debug build on the device (required for
                      watch/doctor; not used by inspect)
  --module <names>    Comma-separated Gradle modules to watch; the FIRST is the app
                      module (default: app). Map a Gradle path to a physical directory
                      with '=', e.g. :app=applications/main,libs/core
  --module-variant <GradlePath=variant>
                      Variant override for a watched module; may be repeated
  --app-module <gradlePath>
                      Which watched module is the app (default: first --module)
  --app-module-dir <dir>
                      Physical directory override for the app module
  --variant <name>    Android variant to compile (default: debug)
  --project-java-home <dir>
                      JDK used by the target project's Gradle build (default: CLI JVM)
  --gradle-arg <arg>  Extra target Gradle argument; may be repeated
  --sdk <dir>         Android SDK root (default: ${'$'}ANDROID_HOME)
  --build-tools <v>   build-tools version for d8 (default: 36.0.0)
  --literals          Enable the live-literals fast path (T24); requires the app built
                      with -Photreload.liveLiterals=true
  --json              (inspect only) print the raw discovery report JSON, nothing else
"""

fun main(args: Array<String>) {
    if (args.isEmpty() || args[0] == "--help" || args[0] == "help") {
        println(USAGE)
        exitProcess(0)
    }

    val command = args[0]
    if (command != "watch" && command != "doctor" && command != "inspect") {
        println(USAGE)
        exitProcess(1)
    }

    if (command == "inspect") {
        runInspect(args.drop(1))
        return
    }

    // Valueless boolean flags: pull them out before the key/value option parser.
    val rest = args.drop(1)
    val literals = "--literals" in rest
    val opts = parseOptions(rest.filter { it != "--literals" })
    val project = Path.of(opts.required("--project")).toAbsolutePath().normalize()
    val appId = opts.required("--app-id")
    val moduleRequests = (opts.single("--module") ?: "app").split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map(ModuleSpec.Request::parse)
    if (moduleRequests.isEmpty()) fail("--module needs at least one module name")
    val modulesWithVariants = try {
        ModuleSpec.Request.applyVariantOverrides(
            moduleRequests,
            opts["--module-variant"].orEmpty(),
        )
    } catch (e: IllegalArgumentException) {
        fail(e.message ?: "invalid --module-variant")
    }
    val sdk = Path.of(
        opts.single("--sdk") ?: System.getenv("ANDROID_HOME")
            ?: fail("--sdk not given and ANDROID_HOME not set"),
    )
    val buildTools = opts.single("--build-tools") ?: "36.0.0"
    val variant = opts.single("--variant") ?: "debug"
    val projectJavaHome = opts.single("--project-java-home")?.let(Path::of)
    val gradleArgs = opts["--gradle-arg"].orEmpty()
    val modules = try {
        ProjectConfig.selectAppModule(
            modulesWithVariants,
            opts.single("--app-module"),
            opts.single("--app-module-dir"),
        )
    } catch (e: IllegalArgumentException) {
        fail(e.message ?: "invalid --app-module/--app-module-dir")
    }

    val config = ProjectConfig(
        projectDir = project,
        modules = modules,
        applicationId = appId,
        variant = variant,
        projectJavaHome = projectJavaHome,
        gradleArgs = gradleArgs,
        literals = literals,
    )

    if (command == "doctor") {
        val ok = Doctor(config, sdk, buildTools).run()
        exitProcess(if (ok) 0 else 1)
    }

    val d8 = sdk.resolve("build-tools/$buildTools/d8")
    val adb = sdk.resolve("platform-tools/adb")
    for ((what, path) in listOf("project dir" to project, "d8" to d8, "adb" to adb)) {
        if (!Files.exists(path)) fail("$what not found: $path")
    }

    try {
        WatchSession(
            WatchSession.Config(
                project = config,
                d8 = d8,
                adb = adb,
            ),
        ).run()
    } catch (t: Throwable) {
        fail(t.message ?: t.toString())
    }
}

private fun runInspect(rest: List<String>) {
    // Valueless boolean flag, same treatment as --literals above.
    val json = "--json" in rest
    val opts = parseOptions(rest.filter { it != "--json" })
    val project = Path.of(opts.required("--project")).toAbsolutePath().normalize()
    val projectJavaHome = opts.single("--project-java-home")?.let(Path::of)
    val gradleArgs = opts["--gradle-arg"].orEmpty()

    val report = try {
        GradleDiscovery.run(project, projectJavaHome, gradleArgs)
    } catch (t: Throwable) {
        fail(t.message ?: t.toString())
    }

    if (json) {
        println(GradleDiscovery.toJson(report))
        exitProcess(0)
    }

    printInspectHuman(report)
    exitProcess(0)
}

private fun printInspectHuman(report: DiscoveryReport) {
    println("project root: ${report.rootDir.orEmpty()}  (Gradle ${report.gradleVersion.orEmpty()})")
    for (project in report.projects.orEmpty()) {
        println("${project.gradlePath}  ${project.type.orEmpty()}  dir=${project.projectDir.orEmpty()}")
        val variants = project.variants.orEmpty()
        for (variant in variants) {
            if (variant.debuggable == true) {
                val appId = variant.applicationId?.let { " appId=$it" }.orEmpty()
                val compileTask = variant.tasks?.compileKotlin?.let { "  → $it" }.orEmpty()
                println("  ${variant.name}: debuggable$appId$compileTask")
            } else {
                println("  ${variant.name}: not debuggable")
            }
        }
        val deps = (variants.flatMap { it.projectDependencies.orEmpty() } + project.jvm?.projectDependencies.orEmpty())
            .distinct()
        if (variants.isNotEmpty() || project.jvm != null) {
            println("  deps: ${if (deps.isEmpty()) "(none)" else deps.joinToString(", ")}")
        }
    }
    val suggestion = report.suggestedWatchCommand()
    if (suggestion != null) {
        println("suggested: $suggestion")
    }
}

private fun parseOptions(args: List<String>): Map<String, List<String>> {
    val opts = mutableMapOf<String, MutableList<String>>()
    var i = 0
    while (i < args.size) {
        val key = args[i]
        if (!key.startsWith("--") || i + 1 >= args.size) fail("bad or valueless option: $key\n\n$USAGE")
        opts.getOrPut(key) { mutableListOf() }.add(args[i + 1])
        i += 2
    }
    return opts
}

private fun Map<String, List<String>>.single(key: String): String? {
    val values = this[key] ?: return null
    if (values.size != 1) fail("$key may only be specified once")
    return values.single()
}

private fun Map<String, List<String>>.required(key: String) =
    single(key) ?: fail("$key is required\n\n$USAGE")

private fun fail(message: String): Nothing {
    System.err.println("hotreload: $message")
    exitProcess(1)
}
