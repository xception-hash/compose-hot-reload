package dev.hotreload.engine

import dev.hotreload.protocol.Protocol
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

class Doctor(
    private val projectDir: Path,
    private val applicationId: String,
    private val moduleNames: List<String>,
    private val sdkDir: Path,
    private val buildTools: String = "36.0.0",
) {
    fun run(): Boolean {
        var hasFail = false

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
            fail("Java environment: JAVA_HOME is not set (fix: export JAVA_HOME=/path/to/jdk-17-or-newer)")
        } else {
            val feature = try {
                Runtime.version().feature()
            } catch (_: Throwable) {
                System.getProperty("java.version").split(".")[0].toIntOrNull() ?: 0
            }
            if (feature < 17) {
                fail("Java environment: Java version is $feature (≥ 17 required) (fix: set JAVA_HOME to JDK 17 or newer)")
            } else {
                val javacFile = File(javaHome, "bin/javac" + (if (isWindows) ".exe" else ""))
                if (!javacFile.exists()) {
                    fail("Java environment: javac not found at ${javacFile.absolutePath} (fix: point JAVA_HOME to a JDK installation, not a JRE)")
                } else {
                    ok("Java environment (JAVA_HOME set, Java $feature ≥ 17)")
                }
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

        // c. Exactly one device/emulator online (`adb devices`); device API >= 30 (`getprop ro.build.version.sdk`)
        var deviceSerial: String? = null
        var adb: Adb? = null
        if (!sdkOk) {
            fail("Device check: skipped due to missing Android SDK/adb")
        } else {
            val adbInst = Adb(adbPath)
            adb = adbInst
            val serials = try {
                adbInst.devices()
            } catch (t: Throwable) {
                emptyList()
            }
            if (serials.isEmpty()) {
                fail("Device connected: no online devices found (fix: start an emulator or connect a device via adb)")
            } else if (serials.size > 1) {
                fail("Device connected: expected 1 device, found ${serials.size} (${serials.joinToString()}) (fix: disconnect extra devices or target a single device)")
            } else {
                val serial = serials.first()
                val (exitCode, apiStr) = adbInst.safeShell("getprop", "ro.build.version.sdk")
                val api = if (exitCode == 0) apiStr.trim().toIntOrNull() else null
                if (api == null || api < 30) {
                    fail("Device connected: device API level is ${apiStr.trim().ifEmpty { "unknown" }} (≥ 30 required) (fix: use an emulator or device running Android 11 / API 30 or higher)")
                } else {
                    deviceSerial = serial
                    ok("Device connected: $serial (API $api ≥ 30)")
                }
            }
        }

        // d. Project checks: settings.gradle.kts applies dev.hotreload plugin's pluginManagement includeBuild;
        // each watched module's build file applies dev.hotreload
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
            } else {
                val settingsText = try { Files.readString(settingsFile) } catch (_: Throwable) { "" }
                val settingsHasPlugin = settingsText.contains("includeBuild") || settingsText.contains("dev.hotreload")

                var allModulesHavePlugin = true
                var missingModuleName: String? = null
                for (mod in moduleNames) {
                    val modDir = projectDir.resolve(mod)
                    val buildKts = modDir.resolve("build.gradle.kts")
                    val buildGroovy = modDir.resolve("build.gradle")
                    val buildFile = when {
                        Files.exists(buildKts) -> buildKts
                        Files.exists(buildGroovy) -> buildGroovy
                        else -> null
                    }
                    if (buildFile == null) {
                        allModulesHavePlugin = false
                        missingModuleName = mod
                        break
                    } else {
                        val buildText = try { Files.readString(buildFile) } catch (_: Throwable) { "" }
                        if (!buildText.contains("dev.hotreload")) {
                            allModulesHavePlugin = false
                            missingModuleName = mod
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
                fail("App installed: package '$applicationId' is not installed (fix: install the app on device via ./gradlew :${moduleNames.first()}:installDebug)")
            } else {
                val (runAsExit, runAsOutput) = adbRef.safeShell("run-as", applicationId, "id")
                if (runAsExit != 0 || runAsOutput.contains("not debuggable") || runAsOutput.contains("unknown package")) {
                    fail("App debuggable: 'run-as $applicationId' failed ($runAsOutput) — app is not debuggable (fix: ensure debug build variant with android:debuggable=\"true\")")
                } else {
                    appOk = true
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
                warn("Runtime handshake: app not running — start it and re-run for the handshake check")
            } else {
                val localPort = try {
                    adbRef.forward(Protocol.deviceSocketName(applicationId))
                } catch (t: Throwable) {
                    null
                }
                if (localPort == null) {
                    fail("Runtime handshake: adb forward failed (fix: reinstall the app (stale runtime-client))")
                } else {
                    try {
                        val caps = DeviceClient(port = localPort).use { it.ping() }
                        if (caps.protocolVersion != Protocol.VERSION) {
                            fail("Runtime handshake: protocol version mismatch (device ${caps.protocolVersion} != engine ${Protocol.VERSION}) (fix: reinstall the app (stale runtime-client))")
                        } else {
                            ok("Runtime handshake: protocol v${caps.protocolVersion}, Compose runtime version: ${caps.composeVersion} (api=${caps.apiLevel}, redefine=${caps.canRedefine}, structural=${caps.canStructural}, inject=${caps.canInjectFile}, compose=${caps.composeBridgeOk})")
                        }
                    } catch (t: Throwable) {
                        fail("Runtime handshake: failed to connect to app runtime (${t.message ?: t}) (fix: reinstall the app (stale runtime-client))")
                    } finally {
                        try { adbRef.removeForward(localPort) } catch (_: Throwable) {}
                    }
                }
            }
        }

        return !hasFail
    }
}
