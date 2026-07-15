package dev.hotreload.engine

import dev.hotreload.protocol.Protocol
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

class Doctor(
    private val config: ProjectConfig,
    private val sdkDir: Path,
    private val buildTools: String = "36.0.0",
) {
    private val projectDir: Path get() = config.projectDir
    private val applicationId: String get() = config.applicationId
    private val modules: List<ModuleSpec.Request> get() = config.modules
    private val variant: String get() = config.variant
    private val projectJavaHome: Path? get() = config.projectJavaHome
    private val integrationMode: IntegrationMode get() = config.integrationMode

    /**
     * Structured checks (T33 phase 6) so `start` can gate on the environment and decide
     * whether `prepare` is needed. [run] is now `runChecked().ok`; every printed line is
     * byte-identical and no check is added, removed, or reordered (doctor output is asserted
     * by users/scripts). Doctor does NOT look at fingerprints (out of scope).
     */
    data class Result(
        val ok: Boolean,           // == today's return value
        val envOk: Boolean,        // java + sdk + device sections all passed
        val appInstalled: Boolean,
        val appDebuggable: Boolean,
        val appRunning: Boolean,
        val handshakeOk: Boolean?, // null = not attempted (app not running / earlier failure)
    )

    fun run(): Boolean = runChecked().ok

    fun runChecked(): Result {
        var hasFail = false
        var javaSectionOk = true
        var deviceOk = false
        var appInstalled = false
        var appDebuggable = false
        var appRunning = false
        var handshakeOk: Boolean? = null

        fun ok(message: String) {
            println("[OK] $message")
        }

        fun warn(message: String) {
            println("[WARN] $message")
        }

        fun fail(message: String): Boolean {
            println("[FAIL] $message")
            hasFail = true
            return false
        }

        val isWindows = System.getProperty("os.name").lowercase().contains("win")

        // a. JAVA_HOME set + javac/java version >= 17
        val javaHome = System.getenv("JAVA_HOME")
        if (javaHome.isNullOrBlank()) {
            javaSectionOk = fail("Java environment: JAVA_HOME is not set (fix: export JAVA_HOME=/path/to/jdk-17-or-newer)")
        } else {
            val feature = try {
                Runtime.version().feature()
            } catch (_: Throwable) {
                System.getProperty("java.version").split(".")[0].toIntOrNull() ?: 0
            }
            if (feature < 17) {
                javaSectionOk = fail("Java environment: Java version is $feature (≥ 17 required) (fix: set JAVA_HOME to JDK 17 or newer)")
            } else {
                val javacFile = File(javaHome, "bin/javac" + (if (isWindows) ".exe" else ""))
                if (!javacFile.exists()) {
                    javaSectionOk = fail("Java environment: javac not found at ${javacFile.absolutePath} (fix: point JAVA_HOME to a JDK installation, not a JRE)")
                } else {
                    ok("Java environment (JAVA_HOME set, Java $feature ≥ 17)")
                }
            }
        }

        projectJavaHome?.let { home ->
            val javac = home.resolve("bin/javac" + if (isWindows) ".exe" else "")
            if (!Files.isDirectory(home) || !Files.isRegularFile(javac)) {
                javaSectionOk = fail("Target project Java home: full JDK not found at $home")
            } else {
                ok("Target project Java home ($home)")
            }
        }

        // b. SDK root exists; platform-tools/adb and build-tools/<v>/d8 present
        val adbExe = if (isWindows) "adb.exe" else "adb"
        val d8Exe = if (isWindows) "d8.bat" else "d8"
        val adbPath = sdkDir.resolve("platform-tools/$adbExe")
        val d8Path = sdkDir.resolve("build-tools/$buildTools/$d8Exe")

        var sdkOk = true
        if (!Files.isDirectory(sdkDir)) {
            sdkOk = false
            fail("Android SDK: root directory not found: $sdkDir (fix: pass --sdk or set ANDROID_HOME)")
        } else if (!Files.exists(adbPath)) {
            sdkOk = false
            fail("Android SDK: adb not found at $adbPath (fix: install platform-tools via SDK Manager)")
        } else if (!Files.exists(d8Path)) {
            sdkOk = false
            fail("Android SDK: d8 not found at $d8Path (fix: install build-tools $buildTools via SDK Manager)")
        } else {
            ok("Android SDK root exists ($sdkDir) and tools present (adb, d8 $buildTools)")
        }

        // c. Device check — serial-aware (T33d)
        var deviceSerial: String? = null
        var adb: Adb? = null
        if (!sdkOk) {
            fail("Device check: skipped due to missing Android SDK/adb")
        } else {
            val adbInst = Adb(adbPath, config.deviceSerial)
            adb = adbInst
            val serials = try {
                adbInst.devices()
            } catch (t: Throwable) {
                emptyList()
            }
            if (config.deviceSerial != null) {
                // Explicit --device: the serial must appear among online devices.
                if (config.deviceSerial !in serials) {
                    fail("Device connected: '${config.deviceSerial}' not found among online devices (${serials.joinToString()})")
                } else {
                    val (exitCode, apiStr) = adbInst.safeShell("getprop", "ro.build.version.sdk")
                    val api = if (exitCode == 0) apiStr.trim().toIntOrNull() else null
                    if (api == null || api < 30) {
                        fail("Device connected: device API level is ${apiStr.trim().ifEmpty { "unknown" }} (≥ 30 required) (fix: use an emulator or device running Android 11 / API 30 or higher)")
                    } else {
                        deviceSerial = config.deviceSerial
                        deviceOk = true
                        ok("Device connected: ${config.deviceSerial} (API $api ≥ 30)")
                    }
                }
            } else {
                // No --device: exactly one online device required.
                if (serials.isEmpty()) {
                    fail("Device connected: no online devices found (fix: start an emulator or connect a device via adb)")
                } else if (serials.size > 1) {
                    fail("Device connected: expected 1 device, found ${serials.size} (${serials.joinToString()}) (fix: disconnect extra devices or pass --device <serial>)")
                } else {
                    val serial = serials.first()
                    val (exitCode, apiStr) = adbInst.safeShell("getprop", "ro.build.version.sdk")
                    val api = if (exitCode == 0) apiStr.trim().toIntOrNull() else null
                    if (api == null || api < 30) {
                        fail("Device connected: device API level is ${apiStr.trim().ifEmpty { "unknown" }} (≥ 30 required) (fix: use an emulator or device running Android 11 / API 30 or higher)")
                    } else {
                        deviceSerial = serial
                        deviceOk = true
                        ok("Device connected: $serial (API $api ≥ 30)")
                    }
                }
            }
        }

        // d. Project checks: every mode needs a Gradle root/settings file. Configured mode
        // additionally verifies the dev.hotreload settings/module references; zero-touch
        // supplies those externally and deliberately leaves target build files unchanged.
        if (!Files.isDirectory(projectDir)) {
            fail("Project checks: project directory not found: $projectDir (fix: specify valid --project directory)")
        } else {
            val settingsKts = projectDir.resolve("settings.gradle.kts")
            val settingsGroovy = projectDir.resolve("settings.gradle")
            val settingsFile = when {
                Files.exists(settingsKts) -> settingsKts
                Files.exists(settingsGroovy) -> settingsGroovy
                else -> null
            }

            if (settingsFile == null) {
                fail("Project checks: settings file not found in $projectDir (fix: point --project to the Gradle root directory)")
            } else if (integrationMode == IntegrationMode.ZERO_TOUCH) {
                try {
                    ZeroTouchArtifacts.verifyPackaged()
                    ok("Project configuration (zero-touch bootstrap; target build files remain unchanged)")
                } catch (t: Throwable) {
                    fail("Project configuration: zero-touch bootstrap artifacts missing or invalid (${t.message ?: t})")
                }
            } else {
                val settingsText = try { Files.readString(settingsFile) } catch (_: Throwable) { "" }
                val settingsHasPlugin = settingsText.contains("includeBuild") || settingsText.contains("dev.hotreload")

                var allModulesHavePlugin = true
                var missingModuleName: String? = null
                for (module in modules) {
                    val modDir = projectDir.resolve(module.relativeDir)
                    val buildKts = modDir.resolve("build.gradle.kts")
                    val buildGroovy = modDir.resolve("build.gradle")
                    val buildFile = when {
                        Files.exists(buildKts) -> buildKts
                        Files.exists(buildGroovy) -> buildGroovy
                        else -> null
                    }
                    if (buildFile == null) {
                        allModulesHavePlugin = false
                        missingModuleName = module.gradlePath
                        break
                    } else {
                        val buildText = try { Files.readString(buildFile) } catch (_: Throwable) { "" }
                        if (!buildText.contains("dev.hotreload")) {
                            allModulesHavePlugin = false
                            missingModuleName = module.gradlePath
                            break
                        }
                    }
                }

                if (!settingsHasPlugin || !allModulesHavePlugin) {
                    warn("Project configuration: dev.hotreload plugin reference missing or unverified in settings or module '${missingModuleName ?: "unknown"}' (ensure plugin is applied)")
                } else {
                    ok("Project configuration (settings.gradle.kts and watched module(s) apply dev.hotreload)")
                }
            }
        }

        // e. App installed + debuggable: `adb shell pm path <appId>` non-empty and `run-as <appId> id` succeeds
        var appOk = false
        val adbRef = adb
        if (adbRef == null || deviceSerial == null) {
            fail("App installed check: skipped due to missing device")
        } else {
            val (pmExit, pmOutput) = adbRef.safeShell("pm", "path", applicationId)
            if (pmExit != 0 || pmOutput.trim().isEmpty() || !pmOutput.contains("package:")) {
                val app = modules.first()
                val installTask = "${app.gradlePath}:install${(app.variant ?: variant).taskSegment()}"
                val fix = if (integrationMode == IntegrationMode.ZERO_TOUCH) {
                    "run 'hotreload prepare --zero-touch' with the same project options"
                } else {
                    "install the app on device via ./gradlew $installTask"
                }
                fail("App installed: package '$applicationId' is not installed (fix: $fix)")
            } else {
                appInstalled = true
                val (runAsExit, runAsOutput) = adbRef.safeShell("run-as", applicationId, "id")
                if (runAsExit != 0 || runAsOutput.contains("not debuggable") || runAsOutput.contains("unknown package")) {
                    fail("App debuggable: 'run-as $applicationId' failed ($runAsOutput) — app is not debuggable (fix: ensure debug build variant with android:debuggable=\"true\")")
                } else {
                    appOk = true
                    appDebuggable = true
                    ok("App installed and debuggable ($applicationId)")
                }
            }
        }

        // f. Runtime handshake
        if (adbRef == null || deviceSerial == null) {
            fail("Runtime handshake: skipped due to missing device")
        } else if (!appOk) {
            fail("Runtime handshake: skipped due to app install/debug check failure")
        } else {
            val (pidExit, pidOutput) = adbRef.safeShell("pidof", applicationId)
            val pid = if (pidExit == 0) pidOutput.trim().split(Regex("\\s+")).firstOrNull() else null
            if (pid.isNullOrEmpty() || pid.toIntOrNull() == null) {
                warn("Runtime handshake: app not running — hotreload watch will auto-launch it; start it manually and re-run doctor for the handshake check")
            } else {
                appRunning = true
                val localPort = try {
                    adbRef.forward(Protocol.deviceSocketName(applicationId))
                } catch (t: Throwable) {
                    null
                }
                if (localPort == null) {
                    val fix = if (integrationMode == IntegrationMode.ZERO_TOUCH) {
                        "re-run 'hotreload prepare --zero-touch' with the same project options"
                    } else {
                        "reinstall the app (stale runtime-client)"
                    }
                    handshakeOk = fail("Runtime handshake: adb forward failed (fix: $fix)")
                } else {
                    try {
                        val caps = DeviceClient(port = localPort).use { it.ping() }
                        if (caps.protocolVersion != Protocol.VERSION) {
                            val fix = if (integrationMode == IntegrationMode.ZERO_TOUCH) {
                                "re-run 'hotreload prepare --zero-touch' with the same project options"
                            } else {
                                "reinstall the app (stale runtime-client)"
                            }
                            handshakeOk = fail("Runtime handshake: protocol version mismatch (device ${caps.protocolVersion} != engine ${Protocol.VERSION}) (fix: $fix)")
                        } else {
                            handshakeOk = true
                            ok("Runtime handshake: protocol v${caps.protocolVersion}, Compose runtime version: ${caps.composeVersion} (api=${caps.apiLevel}, redefine=${caps.canRedefine}, structural=${caps.canStructural}, inject=${caps.canInjectFile}, compose=${caps.composeBridgeOk})")
                        }
                    } catch (t: Throwable) {
                        val fix = if (integrationMode == IntegrationMode.ZERO_TOUCH) {
                            "re-run 'hotreload prepare --zero-touch' with the same project options"
                        } else {
                            "reinstall the app (stale runtime-client)"
                        }
                        handshakeOk = fail("Runtime handshake: failed to connect to app runtime (${t.message ?: t}) (fix: $fix)")
                    } finally {
                        try { adbRef.removeForward(localPort) } catch (_: Throwable) {}
                    }
                }
            }
        }

        return Result(
            ok = !hasFail,
            envOk = javaSectionOk && sdkOk && deviceOk,
            appInstalled = appInstalled,
            appDebuggable = appDebuggable,
            appRunning = appRunning,
            handshakeOk = handshakeOk,
        )
    }
}
