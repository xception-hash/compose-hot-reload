package dev.hotreload.cli

import dev.hotreload.engine.Adb
import dev.hotreload.engine.Doctor
import dev.hotreload.engine.DiscoveryReport
import dev.hotreload.engine.FingerprintStore
import dev.hotreload.engine.Prepare
import dev.hotreload.engine.GradleDiscovery
import dev.hotreload.engine.ModuleSpec
import dev.hotreload.engine.ProjectConfig
import dev.hotreload.engine.WatchSession
import dev.hotreload.engine.resolveWatchPlan
import dev.hotreload.engine.watchCommandFor
import dev.hotreload.engine.Profile
import dev.hotreload.engine.ProfileStore
import dev.hotreload.engine.ModuleMetadata
import dev.hotreload.engine.moduleMetadata
import dev.hotreload.engine.IntegrationMode
import dev.hotreload.engine.GradleInvocation
import dev.hotreload.engine.ReservedGradleProperties
import dev.hotreload.engine.ZeroTouchArtifacts
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.system.exitProcess

private const val USAGE = """hotreload — Flutter-style hot reload for Jetpack Compose on Android

Usage:
  hotreload watch --project <dir> [options]
  hotreload prepare --project <dir> [options]
  hotreload start --project <dir> [options]
  hotreload doctor --project <dir> [options]
  hotreload inspect --project <dir> [options]
  hotreload configure --project <dir> --save-as <name> [options]
  hotreload config show --profile <name>

  prepare  build, install, and launch an instrumented APK for the resolved config
  start    run doctor, prepare when necessary, then watch

When --app-id and --module are omitted, the app module, variant, applicationId, and
watched-module closure are discovered automatically via Gradle inspection.

Options:
  --project <dir>     Gradle project of the app (required unless --profile supplies it)
  --profile <name>    Load defaults from ~/.config/compose-hot-reload/projects/<name>.toml
                      (written by 'hotreload configure'); any explicit flag overrides it
  --app-id <id>       applicationId of the debug build on the device (discovered when
                      omitted with --module also absent)
  --app-module <gradlePath>
                      Which watched module is the app (default: first --module)
  --app-module-dir <dir>
                      Physical directory override for the app module
  --build-tools <v>   build-tools version for d8 (default: 36.0.0)
                      Not stored in profiles; repeat across prepare/doctor/watch
  --device <serial>   Target device serial when several are connected (adb -s;
                      overrides ${'$'}ANDROID_SERIAL)
  --exclude-module <gradlePath>
                      Drop a DISCOVERED watched module; may be repeated. Only valid
                      without --module
  --gradle-arg <arg>  Extra target Gradle argument; may be repeated
  --ignore-fingerprint
                      (watch/start only) skip the build-fingerprint validation written by
                      'hotreload prepare' (diagnostic only; mismatches can corrupt state)
  --include-module <gradlePath>
                      Restrict the DISCOVERED watched modules to these (+ the app
                      module); may be repeated. Only valid without --module
  --json              (inspect only) print the raw discovery report JSON, nothing else
  --launch-activity <name>
                      Activity to launch when the app is not running (default: the
                      device's LAUNCHER intent for --app-id)
  --literals          Experimental live-literals fast path; prepare/watch supply the
                      matching compiler property, so keep the choice identical
  --module <names>    Comma-separated Gradle modules to watch; the FIRST is the app
                      module (default: app). Map a Gradle path to a physical directory
                      with '=', e.g. :app=applications/main,libs/core
  --module-variant <GradlePath=variant>
                      Variant override for a watched module; may be repeated
  --project-java-home <dir>
                      JDK used by the target project's Gradle build (default: CLI JVM)
  --sdk <dir>         Android SDK root (default: ${'$'}ANDROID_HOME)
                      Not stored in profiles; repeat across prepare/doctor/watch
  --variant <name>    Android variant to compile (default: debug)
  --zero-touch        Experimental bundled init-script bootstrap; never requires editing
                      the target project's settings or module build files
"""

fun main(args: Array<String>) {
    if (args.isEmpty() || args[0] == "--help" || args[0] == "help") {
        println(USAGE)
        exitProcess(0)
    }

    val command = args[0]
    if (command != "watch" && command != "prepare" && command != "start" && command != "doctor" && command != "inspect" && command != "configure" && command != "config") {
        println(USAGE)
        exitProcess(1)
    }

    // --ignore-fingerprint is honored only by watch/start; reject it everywhere else
    // (same shape as configure's disallowed-option error).
    if ("--ignore-fingerprint" in args && command != "watch" && command != "start") {
        fail("option --ignore-fingerprint is not allowed for $command")
    }

    if (command == "inspect") {
        runInspect(args.drop(1))
        return
    }

    if (command == "config") {
        if (args.size < 2 || args[1] != "show") {
            fail("invalid subcommand for config: expected 'show'\n\n$USAGE")
        }
        val rest = args.drop(2)
        val opts = parseOptions(rest)
        val profileName = opts.single("--profile") ?: fail("--profile <name> is required")
        val store = ProfileStore.default()
        val profile = try {
            store.load(profileName)
        } catch (e: IllegalArgumentException) {
            fail(e.message ?: "failed to load profile")
        }
        val path = store.path(profileName)
        println("profile: $profileName ($path)")
        val content = Files.readString(path)
        print(content)

        val parts = mutableListOf<String>()
        parts.add("hotreload")
        parts.add("watch")
        parts.add("--project")
        parts.add(profile.project)
        if (profile.appId != null) {
            parts.add("--app-id")
            parts.add(profile.appId!!)
        }
        if (profile.modules.isNotEmpty()) {
            parts.add("--module")
            parts.add(profile.modules.joinToString(","))
        }
        if (profile.variant != null && profile.variant != "debug") {
            parts.add("--variant")
            parts.add(profile.variant!!)
        }
        for (mv in profile.moduleVariants) {
            parts.add("--module-variant")
            parts.add(mv)
        }
        for (ga in profile.gradleArgs) {
            parts.add("--gradle-arg")
            parts.add(ga)
        }
        if (profile.projectJavaHome != null) {
            parts.add("--project-java-home")
            parts.add(profile.projectJavaHome!!)
        }
        if (profile.device != null) {
            parts.add("--device")
            parts.add(profile.device!!)
        }
        if (profile.launchActivity != null) {
            parts.add("--launch-activity")
            parts.add(profile.launchActivity!!)
        }
        if (profile.literals) {
            parts.add("--literals")
        }
        if (profile.integrationMode == IntegrationMode.ZERO_TOUCH) {
            parts.add("--zero-touch")
        }
        println("expanded: ${parts.joinToString(" ")}")
        val discoveryPath = store.discoveryPath(profileName)
        if (Files.exists(discoveryPath)) {
            println("metadata: discovery cache present ($discoveryPath)")
        }
        return
    }

    if (command == "configure") {
        val rest = args.drop(1)
        val literalsFlag = "--literals" in rest
        val zeroTouchFlag = "--zero-touch" in rest
        val opts = parseOptions(rest.filter { it != "--literals" && it != "--zero-touch" })

        if (opts.keys.any { it in setOf("--profile", "--sdk", "--build-tools") }) {
            val badKey = opts.keys.first { it in setOf("--profile", "--sdk", "--build-tools") }
            fail("option $badKey is not allowed for configure")
        }

        val saveAs = opts.single("--save-as") ?: fail("--save-as <name> is required")
        if (!saveAs.matches(Regex("^[A-Za-z0-9._-]+$")) || ".." in saveAs) {
            fail("invalid profile name '$saveAs' (allowed: [A-Za-z0-9._-], no '..')")
        }

        val projectStr = opts.required("--project")
        val project = Path.of(projectStr).toAbsolutePath().normalize()

        val appIdOpt = opts.single("--app-id")
        val moduleOpt = opts.single("--module")
        val variantOpt = opts.single("--variant")
        val moduleVariantList = opts["--module-variant"].orEmpty()
        val gradleArgs = opts["--gradle-arg"].orEmpty()
        validateGradleArgs(gradleArgs)
        val projectJavaHomeStr = opts.single("--project-java-home")
        val projectJavaHome = projectJavaHomeStr?.let(Path::of)
        val deviceSerial = opts.single("--device")
        val launchActivity = opts.single("--launch-activity")
        val literals = literalsFlag

        val includeModules = opts["--include-module"].orEmpty()
        val excludeModules = opts["--exclude-module"].orEmpty()
        val appModuleOpt = opts.single("--app-module")
        val appModuleDirOpt = opts.single("--app-module-dir")

        val resolved = resolveConfig(
            project = project,
            appIdOpt = appIdOpt,
            moduleOpt = moduleOpt,
            variantOpt = variantOpt,
            moduleVariantList = moduleVariantList,
            gradleArgs = gradleArgs,
            projectJavaHome = projectJavaHome,
            launchActivity = launchActivity,
            literals = literals,
            deviceSerial = deviceSerial,
            includeModules = includeModules,
            excludeModules = excludeModules,
            appModuleOpt = appModuleOpt,
            appModuleDirOpt = appModuleDirOpt,
            integrationMode = if (zeroTouchFlag) IntegrationMode.ZERO_TOUCH else IntegrationMode.CONFIGURED,
        )
        val config = resolved.config

        val profile = Profile(
            project = config.projectDir.toString(),
            appId = config.applicationId,
            variant = config.variant,
            modules = config.modules.map { "${it.gradlePath}=${it.relativeDir}" },
            moduleVariants = config.modules.filter { it.variant != null }.map { "${it.gradlePath}=${it.variant}" },
            gradleArgs = config.gradleArgs,
            projectJavaHome = config.projectJavaHome?.toString(),
            device = config.deviceSerial,
            launchActivity = config.launchActivity,
            literals = config.literals,
            integrationMode = config.integrationMode,
        )

        val store = ProfileStore.default()
        val report = resolved.discoveryReport
        if (report != null) {
            store.saveDiscovery(saveAs, GradleDiscovery.toJson(report))
        } else {
            Files.deleteIfExists(store.discoveryPath(saveAs))
        }

        val path = store.save(saveAs, profile)
        println("saved: $saveAs -> $path")
        println("run: hotreload watch --profile $saveAs")
        return
    }

    // Valueless boolean flags: pull them out before the key/value option parser.
    val rest = args.drop(1)
    val literalsFlag = "--literals" in rest
    val ignoreFingerprint = "--ignore-fingerprint" in rest
    val zeroTouchFlag = "--zero-touch" in rest
    val opts = parseOptions(rest.filter { it != "--literals" && it != "--ignore-fingerprint" && it != "--zero-touch" })

    val profileName = opts.single("--profile")
    val profile = if (profileName != null) {
        try {
            ProfileStore.default().load(profileName)
        } catch (e: IllegalArgumentException) {
            fail(e.message ?: "failed to load profile")
        }
    } else {
        null
    }

    if (profile != null) {
        val path = ProfileStore.default().path(profileName!!)
        println("profile: $profileName ($path)")
    }

    val projectStr = opts.single("--project") ?: profile?.project ?: fail("--project is required (or provide it via --profile)")
    val project = Path.of(projectStr).toAbsolutePath().normalize()
    val sdk = Path.of(
        opts.single("--sdk") ?: System.getenv("ANDROID_HOME")
            ?: fail("--sdk not given and ANDROID_HOME not set"),
    )
    val buildTools = opts.single("--build-tools") ?: "36.0.0"

    val appIdOpt = opts.single("--app-id") ?: profile?.appId
    val moduleOpt = opts.single("--module") ?: profile?.modules?.takeIf { it.isNotEmpty() }?.joinToString(",")
    val variantOpt = opts.single("--variant") ?: profile?.variant
    val moduleVariantList = opts["--module-variant"].let { if (!it.isNullOrEmpty()) it else profile?.moduleVariants.orEmpty() }
    val gradleArgs = opts["--gradle-arg"].let { if (!it.isNullOrEmpty()) it else profile?.gradleArgs.orEmpty() }
    validateGradleArgs(gradleArgs)
    val projectJavaHomeStr = opts.single("--project-java-home") ?: profile?.projectJavaHome
    val projectJavaHome = projectJavaHomeStr?.let(Path::of)
    val deviceSerial = opts.single("--device") ?: profile?.device
    val launchActivity = opts.single("--launch-activity") ?: profile?.launchActivity
    val literals = literalsFlag || (profile?.literals ?: false)
    val integrationMode = if (zeroTouchFlag || profile?.integrationMode == IntegrationMode.ZERO_TOUCH) {
        IntegrationMode.ZERO_TOUCH
    } else {
        IntegrationMode.CONFIGURED
    }

    val includeModules = opts["--include-module"].orEmpty()
    val excludeModules = opts["--exclude-module"].orEmpty()
    val appModuleOpt = opts.single("--app-module")
    val appModuleDirOpt = opts.single("--app-module-dir")

    val resolved = resolveConfig(
        project = project,
        appIdOpt = appIdOpt,
        moduleOpt = moduleOpt,
        variantOpt = variantOpt,
        moduleVariantList = moduleVariantList,
        gradleArgs = gradleArgs,
        projectJavaHome = projectJavaHome,
        launchActivity = launchActivity,
        literals = literals,
        deviceSerial = deviceSerial,
        includeModules = includeModules,
        excludeModules = excludeModules,
        appModuleOpt = appModuleOpt,
        appModuleDirOpt = appModuleDirOpt,
        integrationMode = integrationMode,
    )
    var config = resolved.config

    if (profileName != null) {
        val discoveryJson = try {
            ProfileStore.default().loadDiscovery(profileName)
        } catch (e: Exception) {
            null
        }
        if (discoveryJson != null) {
            try {
                val report = GradleDiscovery.fromJson(discoveryJson)
                if (report.rootDir != profile!!.project) {
                    println("metadata: ignoring discovery cache (rootDir mismatch) — using convention fallbacks")
                } else {
                    val effectiveVariant = config.variant
                    val cacheMetadata = report.moduleMetadata(config.modules, effectiveVariant)
                    if (cacheMetadata.isNotEmpty()) {
                        println("metadata: ${cacheMetadata.size} module(s) from discovery cache")
                        config = config.copy(moduleMetadata = cacheMetadata)
                    }
                }
            } catch (e: Exception) {
                val reason = e.message ?: e.toString()
                println("metadata: ignoring discovery cache ($reason) — using convention fallbacks")
            }
        }
    }

    when (command) {
        "doctor" -> {
            val ok = Doctor(config, sdk, buildTools).run()
            exitProcess(if (ok) 0 else 1)
        }
        "prepare" -> {
            try {
                Prepare.run(config, sdk, buildTools)
            } catch (t: Throwable) {
                fail(t.message ?: t.toString())
            }
            exitProcess(0)
        }
        "start" -> runStart(config, sdk, buildTools, ignoreFingerprint)
        else -> runWatchPhase(config, sdk, buildTools, ignoreFingerprint)
    }
}

/**
 * `start`: doctor → prepare when necessary → watch. Gates on the environment, then prepares
 * only when the app is missing, the handshake failed, or the fingerprint says the installed
 * APK does not (positively) match the resolved configuration.
 */
private fun runStart(config: ProjectConfig, sdk: Path, buildTools: String, ignoreFingerprint: Boolean) {
    val result = Doctor(config, sdk, buildTools).runChecked()
    if (!result.envOk) {
        println("start: environment checks failed — fix the [FAIL] items above")
        exitProcess(1)
    }
    val reasons = mutableListOf<String>()
    if (!result.appInstalled) reasons += "app not installed"
    if (result.handshakeOk == false) reasons += "runtime handshake failed"
    // handshakeOk == null (app not running) is NOT a trigger — watch auto-launches.
    if (!ignoreFingerprint) fingerprintPrepareReason(config, sdk)?.let { reasons += it }

    if (reasons.isNotEmpty()) {
        println("start: preparing (${reasons.joinToString(", ")})")
        try {
            Prepare.run(config, sdk, buildTools)
        } catch (t: Throwable) {
            fail(t.message ?: t.toString())
        }
    }
    runWatchPhase(config, sdk, buildTools, ignoreFingerprint)
}

/** Existence checks + fingerprint gate + WatchSession (shared by `watch` and `start`). */
private fun runWatchPhase(config: ProjectConfig, sdk: Path, buildTools: String, ignoreFingerprint: Boolean) {
    val d8 = sdk.resolve("build-tools/$buildTools/d8")
    val adb = sdk.resolve("platform-tools/adb")
    for ((what, path) in listOf("project dir" to config.projectDir, "d8" to d8, "adb" to adb)) {
        if (!Files.exists(path)) fail("$what not found: $path")
    }

    fingerprintGate(config, adb, ignoreFingerprint)

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

/**
 * Fingerprint validation gate (T33 phase 6), run after the d8/adb existence checks and
 * before WatchSession construction. Applies the three knowledge-state rules from the spec:
 * absent fingerprint ⇒ silent (freeze); positively-matched device APK ⇒ compare config
 * fields, REFUSE on any mismatch; unknown provenance (device sha differs/unobtainable) ⇒
 * warn and proceed.
 */
private fun fingerprintGate(config: ProjectConfig, adbPath: Path, ignoreFingerprint: Boolean) {
    if (ignoreFingerprint) {
        println("fingerprint: check skipped (--ignore-fingerprint)")
        return
    }
    val serial = resolveGateSerial(config, adbPath) ?: return // 0/multiple devices → skip silently
    val fp = FingerprintStore.default().load(serial, config.applicationId) ?: return // absent → freeze

    val adb = Adb(adbPath, serial)
    val deviceSha = adb.installedApkSha256(config.applicationId)
    if (fp.apkSha256 != null && deviceSha != null && fp.apkSha256 == deviceSha) {
        val mismatches = fp.mismatches(config)
        if (mismatches.isNotEmpty()) {
            println("fingerprint: MISMATCH")
            mismatches.forEach { println("  $it") }
            fail(
                "the installed APK was prepared for a different configuration — re-run " +
                    "'hotreload prepare', or pass --ignore-fingerprint to override",
            )
        }
        println("fingerprint: OK (prepared ${Instant.ofEpochMilli(fp.preparedAt)})")
    } else {
        println(
            "fingerprint: installed APK changed outside 'hotreload prepare' — cannot verify " +
                "build mode; run 'hotreload prepare' to refresh",
        )
    }
}

/**
 * Why `start` should prepare (fingerprint clauses only), or null if the fingerprint state
 * does not call for it: absent, unknown provenance (sha differs/unobtainable), or a
 * field-mismatch against a positively-matched APK all trigger a prepare.
 */
private fun fingerprintPrepareReason(config: ProjectConfig, sdk: Path): String? {
    val adbPath = sdk.resolve("platform-tools/adb")
    val serial = resolveGateSerial(config, adbPath) ?: return null
    val fp = FingerprintStore.default().load(serial, config.applicationId)
        ?: return "no build fingerprint"
    val deviceSha = Adb(adbPath, serial).installedApkSha256(config.applicationId)
    return if (fp.apkSha256 != null && deviceSha != null && fp.apkSha256 == deviceSha) {
        if (fp.mismatches(config).isNotEmpty()) "build configuration changed" else null
    } else {
        "installed APK changed outside prepare"
    }
}

/** config.deviceSerial, or the single online device; null when zero or multiple are attached. */
private fun resolveGateSerial(config: ProjectConfig, adbPath: Path): String? {
    config.deviceSerial?.let { return it }
    val devices = try { Adb(adbPath, null).devices() } catch (_: Throwable) { emptyList() }
    return devices.singleOrNull()
}

private class Resolved(val config: ProjectConfig, val discoveryReport: DiscoveryReport?)

private fun resolveConfig(
    project: Path,
    appIdOpt: String?,
    moduleOpt: String?,
    variantOpt: String?,
    moduleVariantList: List<String>,
    gradleArgs: List<String>,
    projectJavaHome: Path?,
    launchActivity: String?,
    literals: Boolean,
    deviceSerial: String?,
    includeModules: List<String>,
    excludeModules: List<String>,
    appModuleOpt: String?,
    appModuleDirOpt: String?,
    integrationMode: IntegrationMode,
): Resolved {
    val hasModule = moduleOpt != null
    val hasFilters = includeModules.isNotEmpty() || excludeModules.isNotEmpty()

    if (hasModule && hasFilters) {
        fail("--include-module/--exclude-module filter the discovered module set; with --module or a profile that pins modules, edit the list directly")
    }

    val useDiscovery = !hasModule && (appIdOpt == null || hasFilters)

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
            report.resolveWatchPlan(appModuleOpt, variantOpt, normalizedIncludes, normalizedExcludes)
        } catch (e: IllegalArgumentException) {
            fail(e.message ?: "discovery resolution failed")
        }

        val applicationId = appIdOpt
            ?: plan.applicationId
            ?: fail("discovery found no applicationId for variant '${plan.variantName}' — pass --app-id")

        val modulesWithVariants = try {
            ModuleSpec.Request.applyVariantOverrides(
                plan.modules,
                moduleVariantList,
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
        val resolvedCommand = report.watchCommandFor(plan, project.toString()) +
            if (integrationMode == IntegrationMode.ZERO_TOUCH) " --zero-touch" else ""
        println("resolved: $resolvedCommand")

        val provisionalConfig = try {
            ProjectConfig(
                projectDir = project,
                modules = modules,
                applicationId = applicationId,
                variant = plan.variantName,
                projectJavaHome = projectJavaHome,
                gradleArgs = gradleArgs,
                literals = literals,
                deviceSerial = deviceSerial,
                launchActivity = launchActivity,
                integrationMode = integrationMode,
            )
        } catch (e: IllegalArgumentException) {
            fail(e.message ?: "invalid configuration")
        }

        // The first discovery is intentionally uninstrumented: only it knows the exact
        // watched-module closure. Once resolved, refresh metadata with the bootstrap init
        // script applied to that allowlist and no other project.
        val effectiveReport = if (integrationMode == IntegrationMode.ZERO_TOUCH) {
            try {
                GradleInvocation.open(provisionalConfig).use { invocation ->
                    GradleDiscovery.run(project, projectJavaHome, invocation.arguments)
                }
            } catch (t: Throwable) {
                fail(t.message ?: t.toString())
            }
        } else {
            report
        }

        val moduleMetadata = effectiveReport.moduleMetadata(modules, plan.variantName)
        if (moduleMetadata.isNotEmpty()) {
            println("metadata: ${moduleMetadata.size} module(s) from discovery")
        }

        val config = provisionalConfig.copy(moduleMetadata = moduleMetadata)
        return Resolved(config, effectiveReport)
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
                moduleVariantList,
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

        val provisionalConfig = try {
            ProjectConfig(
                projectDir = project,
                modules = modules,
                applicationId = appId,
                variant = variantOpt ?: "debug",
                projectJavaHome = projectJavaHome,
                gradleArgs = gradleArgs,
                literals = literals,
                deviceSerial = deviceSerial,
                launchActivity = launchActivity,
                integrationMode = integrationMode,
            )
        } catch (e: IllegalArgumentException) {
            fail(e.message ?: "invalid configuration")
        }

        // Explicit --module/--app-id still needs variant metadata for patch D8. In particular,
        // KotlinCompile's resolved libraries tell D8 which referenced owners are interfaces, so
        // minSdk<24 patches can target the same `$-CC` helpers as the installed APK. Discovery is
        // best-effort here to preserve convention fallbacks for older/unusual builds.
        val report = try {
            if (integrationMode == IntegrationMode.ZERO_TOUCH) {
                GradleInvocation.open(provisionalConfig).use { invocation ->
                    GradleDiscovery.run(project, projectJavaHome, invocation.arguments)
                }
            } else {
                GradleDiscovery.run(project, projectJavaHome, gradleArgs)
            }
        } catch (t: Throwable) {
            println("metadata: discovery unavailable (${t.message ?: t}) — using convention fallbacks")
            null
        }
        val moduleMetadata = report?.moduleMetadata(modules, provisionalConfig.variant).orEmpty()
        if (moduleMetadata.isNotEmpty()) {
            println("metadata: ${moduleMetadata.size} module(s) from discovery")
        }
        return Resolved(provisionalConfig.copy(moduleMetadata = moduleMetadata), report)
    }
}

private fun runInspect(rest: List<String>) {
    // Valueless boolean flag, same treatment as --literals above.
    val json = "--json" in rest
    val zeroTouchFlag = "--zero-touch" in rest
    val opts = parseOptions(rest.filter { it != "--json" && it != "--zero-touch" })
    val profile = opts.single("--profile")?.let { name ->
        try {
            ProfileStore.default().load(name)
        } catch (e: IllegalArgumentException) {
            fail(e.message ?: "failed to load profile")
        }
    }
    val project = Path.of(opts.single("--project") ?: profile?.project ?: fail("--project is required (or provide it via --profile)"))
        .toAbsolutePath()
        .normalize()
    val projectJavaHome = (opts.single("--project-java-home") ?: profile?.projectJavaHome)?.let(Path::of)
    val explicitGradleArgs = opts["--gradle-arg"].orEmpty()
    val gradleArgs = explicitGradleArgs.ifEmpty { profile?.gradleArgs.orEmpty() }
    validateGradleArgs(gradleArgs)
    val zeroTouch = zeroTouchFlag || profile?.integrationMode == IntegrationMode.ZERO_TOUCH

    if (zeroTouch) {
        try {
            ZeroTouchArtifacts.verifyPackaged()
        } catch (t: Throwable) {
            fail(t.message ?: t.toString())
        }
    }

    val report = try {
        GradleDiscovery.run(project, projectJavaHome, gradleArgs)
    } catch (t: Throwable) {
        fail(t.message ?: t.toString())
    }

    if (json) {
        println(GradleDiscovery.toJson(report))
        exitProcess(0)
    }

    printInspectHuman(report, zeroTouch)
    exitProcess(0)
}

private fun validateGradleArgs(args: List<String>) {
    try {
        ReservedGradleProperties.validate(args)
    } catch (e: IllegalArgumentException) {
        fail(e.message ?: "invalid --gradle-arg")
    }
}

private fun printInspectHuman(report: DiscoveryReport, zeroTouch: Boolean = false) {
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
        println("suggested: $suggestion${if (zeroTouch) " --zero-touch" else ""}")
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
