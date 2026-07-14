package dev.hotreload.cli

import dev.hotreload.engine.Doctor
import dev.hotreload.engine.DiscoveryReport
import dev.hotreload.engine.GradleDiscovery
import dev.hotreload.engine.ModuleSpec
import dev.hotreload.engine.ProjectConfig
import dev.hotreload.engine.WatchSession
import dev.hotreload.engine.resolveWatchPlan
import dev.hotreload.engine.watchCommandFor
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

private const val USAGE = """hotreload — Flutter-style hot reload for Jetpack Compose on Android

Usage:
  hotreload watch --project <dir> [options]
  hotreload doctor --project <dir> [options]
  hotreload inspect --project <dir> [options]

When --app-id and --module are omitted, the app module, variant, applicationId, and
watched-module closure are discovered automatically via Gradle inspection.

Options:
  --project <dir>     Gradle project of the app (required)
  --app-id <id>       applicationId of the debug build on the device (discovered when
                      omitted with --module also absent)
  --app-module <gradlePath>
                      Which watched module is the app (default: first --module)
  --app-module-dir <dir>
                      Physical directory override for the app module
  --build-tools <v>   build-tools version for d8 (default: 36.0.0)
  --device <serial>   Target device serial when several are connected (adb -s;
                      overrides ${'$'}ANDROID_SERIAL)
  --exclude-module <gradlePath>
                      Drop a DISCOVERED watched module; may be repeated. Only valid
                      without --module
  --gradle-arg <arg>  Extra target Gradle argument; may be repeated
  --include-module <gradlePath>
                      Restrict the DISCOVERED watched modules to these (+ the app
                      module); may be repeated. Only valid without --module
  --json              (inspect only) print the raw discovery report JSON, nothing else
  --launch-activity <name>
                      Activity to launch when the app is not running (default: the
                      device's LAUNCHER intent for --app-id)
  --literals          Enable the live-literals fast path (T24); requires the app built
                      with -Photreload.liveLiterals=true
  --module <names>    Comma-separated Gradle modules to watch; the FIRST is the app
                      module (default: app). Map a Gradle path to a physical directory
                      with '=', e.g. :app=applications/main,libs/core
  --module-variant <GradlePath=variant>
                      Variant override for a watched module; may be repeated
  --project-java-home <dir>
                      JDK used by the target project's Gradle build (default: CLI JVM)
  --sdk <dir>         Android SDK root (default: ${'$'}ANDROID_HOME)
  --variant <name>    Android variant to compile (default: debug)
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
    val sdk = Path.of(
        opts.single("--sdk") ?: System.getenv("ANDROID_HOME")
            ?: fail("--sdk not given and ANDROID_HOME not set"),
    )
    val buildTools = opts.single("--build-tools") ?: "36.0.0"
    val variant = opts.single("--variant") ?: "debug"
    val projectJavaHome = opts.single("--project-java-home")?.let(Path::of)
    val gradleArgs = opts["--gradle-arg"].orEmpty()
    val deviceSerial = opts.single("--device")
    val launchActivity = opts.single("--launch-activity")

    val moduleOpt = opts.single("--module")
    val appIdOpt = opts.single("--app-id")
    val includeModules = opts["--include-module"].orEmpty()
    val excludeModules = opts["--exclude-module"].orEmpty()
    val appModuleOpt = opts.single("--app-module")
    val appModuleDirOpt = opts.single("--app-module-dir")

    // Trigger rule: discovery runs iff --module is ABSENT and (--app-id is absent OR
    // any --include-module/--exclude-module is given).
    val hasModuleFlag = moduleOpt != null
    val hasFilters = includeModules.isNotEmpty() || excludeModules.isNotEmpty()

    if (hasModuleFlag && hasFilters) {
        fail("--include-module/--exclude-module filter the discovered module set; with --module, edit the list directly")
    }

    val useDiscovery = !hasModuleFlag && (appIdOpt == null || hasFilters)

    val config: ProjectConfig
    if (useDiscovery) {
        // --- Discovery path ---
        val report = try {
            GradleDiscovery.run(project, projectJavaHome, gradleArgs)
        } catch (t: Throwable) {
            fail(t.message ?: t.toString())
        }

        val normalizedIncludes = includeModules.map { ModuleSpec.Request.parse(it).gradlePath }
        val normalizedExcludes = excludeModules.map { ModuleSpec.Request.parse(it).gradlePath }

        val plan = try {
            report.resolveWatchPlan(appModuleOpt, opts.single("--variant"), normalizedIncludes, normalizedExcludes)
        } catch (e: IllegalArgumentException) {
            fail(e.message ?: "discovery resolution failed")
        }

        val applicationId = appIdOpt
            ?: plan.applicationId
            ?: fail("discovery found no applicationId for variant '${plan.variantName}' — pass --app-id")

        val modulesWithVariants = try {
            ModuleSpec.Request.applyVariantOverrides(
                plan.modules,
                opts["--module-variant"].orEmpty(),
            )
        } catch (e: IllegalArgumentException) {
            fail(e.message ?: "invalid --module-variant")
        }

        val modules = try {
            ProjectConfig.selectAppModule(modulesWithVariants, null, appModuleDirOpt)
        } catch (e: IllegalArgumentException) {
            fail(e.message ?: "invalid --app-module/--app-module-dir")
        }

        // Print discovery lines.
        val appModule = plan.modules.first()
        println("discovered: app=${appModule.gradlePath} dir=${appModule.relativeDir} variant=${plan.variantName} appId=$applicationId")
        // "modules", not "watching": the bare "watching " prefix is the session-ready
        // contract line (e2e/IDE plugin grep for it) and must never appear earlier.
        println("discovered: modules ${plan.modules.joinToString { it.gradlePath }}")
        println("resolved: ${report.watchCommandFor(plan, project.toString())}")

        config = ProjectConfig(
            projectDir = project,
            modules = modules,
            applicationId = applicationId,
            variant = plan.variantName,
            projectJavaHome = projectJavaHome,
            gradleArgs = gradleArgs,
            literals = literals,
            deviceSerial = deviceSerial,
            launchActivity = launchActivity,
        )
    } else {
        // --- Legacy path (--module present, or --app-id present with no filters) ---
        val appId = appIdOpt ?: fail("--app-id is required\n\n$USAGE")
        val moduleRequests = (moduleOpt ?: "app").split(',')
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
        val modules = try {
            ProjectConfig.selectAppModule(
                modulesWithVariants,
                appModuleOpt,
                appModuleDirOpt,
            )
        } catch (e: IllegalArgumentException) {
            fail(e.message ?: "invalid --app-module/--app-module-dir")
        }

        config = ProjectConfig(
            projectDir = project,
            modules = modules,
            applicationId = appId,
            variant = variant,
            projectJavaHome = projectJavaHome,
            gradleArgs = gradleArgs,
            literals = literals,
            deviceSerial = deviceSerial,
            launchActivity = launchActivity,
        )
    }

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
