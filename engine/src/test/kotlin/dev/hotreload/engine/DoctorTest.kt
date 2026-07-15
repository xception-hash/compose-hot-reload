package dev.hotreload.engine

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DoctorTest {

    private fun captureStdout(block: () -> Unit): String {
        val captured = ByteArrayOutputStream()
        val original = System.out
        System.setOut(PrintStream(captured))
        try {
            block()
        } finally {
            System.setOut(original)
        }
        return captured.toString()
    }

    @Test
    fun doctorFailsOnInvalidProjectDir() {
        val tempDir = Files.createTempDirectory("doctor_test")
        try {
            val doctor = Doctor(
                config = ProjectConfig(
                    projectDir = tempDir.resolve("nonexistent"),
                    applicationId = "dev.hotreload.test",
                    modules = listOf(ModuleSpec.Request.parse("app")),
                ),
                sdkDir = tempDir.resolve("sdk"),
            )
            val ok = doctor.run()
            assertFalse(ok, "Doctor should fail when project dir does not exist")
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun doctorFailsOnMissingSdk() {
        val tempDir = Files.createTempDirectory("doctor_test_sdk")
        try {
            val projectDir = tempDir.resolve("proj")
            Files.createDirectories(projectDir)
            Files.writeString(projectDir.resolve("settings.gradle.kts"), "pluginManagement { includeBuild(\"...\") }")

            val appDir = projectDir.resolve("app")
            Files.createDirectories(appDir)
            Files.writeString(appDir.resolve("build.gradle.kts"), "plugins { id(\"dev.hotreload\") }")

            val doctor = Doctor(
                config = ProjectConfig(
                    projectDir = projectDir,
                    applicationId = "dev.hotreload.test",
                    modules = listOf(ModuleSpec.Request.parse("app")),
                ),
                sdkDir = tempDir.resolve("nonexistent_sdk"),
            )
            val ok = doctor.run()
            assertFalse(ok, "Doctor should fail when SDK dir does not exist")
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun zeroTouchProjectCheckDoesNotRequireConfiguredPluginReferences() {
        val tempDir = Files.createTempDirectory("doctor_zero_touch")
        try {
            val projectDir = tempDir.resolve("proj")
            Files.createDirectories(projectDir)
            Files.writeString(projectDir.resolve("settings.gradle.kts"), "rootProject.name = \"plain\"")

            val output = captureStdout {
                Doctor(
                    config = ProjectConfig(
                        projectDir = projectDir,
                        applicationId = "dev.hotreload.test",
                        modules = listOf(ModuleSpec.Request.parse("app")),
                        integrationMode = IntegrationMode.ZERO_TOUCH,
                    ),
                    sdkDir = tempDir.resolve("missing-sdk"),
                ).run()
            }

            assertTrue("[OK] Project configuration (zero-touch bootstrap; target build files remain unchanged)" in output)
            assertFalse("dev.hotreload plugin reference missing" in output)
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun configuredProjectCheckOutputRemainsUnchanged() {
        val tempDir = Files.createTempDirectory("doctor_configured")
        try {
            val projectDir = tempDir.resolve("proj")
            Files.createDirectories(projectDir)
            Files.writeString(projectDir.resolve("settings.gradle.kts"), "pluginManagement { includeBuild(\"...\") }")
            val appDir = projectDir.resolve("app")
            Files.createDirectories(appDir)
            Files.writeString(appDir.resolve("build.gradle.kts"), "plugins { id(\"dev.hotreload\") }")

            val output = captureStdout {
                Doctor(
                    config = ProjectConfig(
                        projectDir = projectDir,
                        applicationId = "dev.hotreload.test",
                        modules = listOf(ModuleSpec.Request.parse("app")),
                    ),
                    sdkDir = tempDir.resolve("missing-sdk"),
                ).run()
            }

            assertTrue("[OK] Project configuration (settings.gradle.kts and watched module(s) apply dev.hotreload)" in output)
            assertFalse("zero-touch bootstrap" in output)
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }
}
