package dev.hotreload.engine

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DoctorTest {

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
}
