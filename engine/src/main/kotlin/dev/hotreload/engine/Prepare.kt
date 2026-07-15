package dev.hotreload.engine

import java.nio.file.Files
import java.nio.file.Path

/**
 * The `hotreload prepare` pipeline (T33 phase 6): build → locate APK → install →
 * force-stop → launch → write a build fingerprint, so a later `watch` can positively
 * verify the installed APK matches the resolved configuration (closing the stale-APK /
 * baseline-dex-mismatch hole). Builds in the SAME mode `watch` will run (that identity is
 * the whole point) by reusing the exact gradleArgs expression `WatchSession.run()` uses.
 */
object Prepare {
    /**
     * Build → locate APK → install → force-stop → launch → fingerprint. Throws
     * [IllegalStateException] with a user-facing message on any failure; each step fails
     * loud before the next runs.
     */
    fun run(
        config: ProjectConfig,
        sdk: Path,
        buildTools: String,
        store: FingerprintStore = FingerprintStore.default(),
    ): Fingerprint {
        // 1. Device serial FIRST, before any build (fail fast).
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        val adbPath = sdk.resolve("platform-tools/adb" + if (isWindows) ".exe" else "")
        check(Files.exists(adbPath)) { "adb not found at $adbPath" }
        val adb = Adb(adbPath, config.deviceSerial)
        val serials = try { adb.devices() } catch (_: Throwable) { emptyList() }
        val serial = if (config.deviceSerial != null) {
            check(config.deviceSerial in serials) {
                "device '${config.deviceSerial}' not found among online devices (${serials.joinToString()})"
            }
            config.deviceSerial
        } else {
            check(serials.isNotEmpty()) {
                "no online devices found — start an emulator or connect a device via adb"
            }
            check(serials.size == 1) {
                "expected 1 device, found ${serials.size} (${serials.joinToString()}) — pass --device <serial>"
            }
            serials.first()
        }

        val app = config.appModule
        val appVariant = app.variant ?: config.variant

        // 2. Build — same mode watch will run in (gradleArgs identity is the whole point).
        val gradleArgs = config.gradleArgs +
            if (config.literals) listOf("-Photreload.liveLiterals=true") else emptyList()
        val assembleTask = config.moduleMetadata[app.gradlePath]?.assembleTask
            ?: "${app.gradlePath}:assemble${appVariant.taskSegment()}"
        GradleCompiler(config.projectDir.toFile(), gradleArgs, config.projectJavaHome?.toFile()).use { gradle ->
            print("build: $assembleTask... ")
            val build = gradle.compile(assembleTask)
            check(build.success) { "build failed:\n${build.output}" }
            println("ok (${build.durationMs}ms)")
        }

        // 3. Probe + locate the APK (classes exist — assemble ran compile).
        val appModule = ModuleSpec.probe(
            config.projectDir, app, config.variant, config.moduleMetadata[app.gradlePath],
        )
        val located = ApkLocator.locate(appModule.apkOutputDirs, appModule.variant, config.applicationId)
            ?: throw IllegalStateException(
                "no APK found under ${appModule.apkOutputDirs.joinToString()} after $assembleTask",
            )
        println("apk: ${located.apk} (${located.source})")

        // 4. Install.
        val (installExit, installOut) = adb.install(located.apk)
        check(installExit == 0) { "adb install failed (exit $installExit): $installOut" }
        println("installed: ${config.applicationId} on $serial")

        // 5. Force-stop (belt-and-braces on top of install's process kill — injected/primed
        // classes are immutable per process; a fresh process is prepare's contract), then launch.
        adb.safeShell("am", "force-stop", config.applicationId)
        AppLauncher.ensureRunning(adb, config.applicationId, config.launchActivity)

        // 6. Fingerprint the base APK as installed on the device (null tolerated → validation
        // then always lands in the warn-only unknown-provenance state).
        val sha = adb.installedApkSha256(config.applicationId)
        val fp = Fingerprint.of(config, serial, sha, located.apk.toString())
        val fpPath = store.save(fp)
        println("fingerprint: written ($fpPath)")
        println("prepared: ${config.applicationId} on $serial (${config.variant}, literals=${config.literals})")
        return fp
    }
}
