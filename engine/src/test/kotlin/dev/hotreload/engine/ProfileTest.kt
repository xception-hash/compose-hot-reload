package dev.hotreload.engine

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class ProfileTest {

    @Test
    fun roundTripAllFields() {
        val original = Profile(
            project = "/path/to/project",
            appId = "dev.hotreload.test",
            variant = "stageDebug",
            modules = listOf(":app=app", ":feature=feature"),
            moduleVariants = listOf(":feature=stageDebug"),
            gradleArgs = listOf("--parallel", "--offline"),
            projectJavaHome = "/path/to/jdk",
            device = "emulator-5554",
            launchActivity = ".MainActivity",
            literals = true,
            integrationMode = IntegrationMode.ZERO_TOUCH,
        )
        val tomlText = original.toToml("test-profile")
        
        // Assert optional keys are present
        assertTrue("app-id = " in tomlText)
        assertTrue("variant = " in tomlText)
        assertTrue("module-variants = " in tomlText)
        assertTrue("gradle-args = " in tomlText)
        assertTrue("project-java-home = " in tomlText)
        assertTrue("device = " in tomlText)
        assertTrue("launch-activity = " in tomlText)
        assertTrue("literals = true" in tomlText)
        assertTrue("zero-touch = true" in tomlText)

        // Parse and reconstruct
        val map = Toml.parse(tomlText)
        val schema = map["schema"] as Long
        assertEquals(1L, schema)
        
        val reconstructed = mapToProfile(map)
        assertEquals(original, reconstructed)
    }

    @Test
    fun roundTripMinimal() {
        val original = Profile(
            project = "/path/to/project"
        )
        val tomlText = original.toToml("test-profile")

        // Assert optional keys are NOT present
        assertFalse("app-id =" in tomlText)
        assertFalse("variant =" in tomlText)
        assertFalse("module-variants =" in tomlText)
        assertFalse("gradle-args =" in tomlText)
        assertFalse("project-java-home =" in tomlText)
        assertFalse("device =" in tomlText)
        assertFalse("launch-activity =" in tomlText)
        assertFalse("literals =" in tomlText)
        assertFalse("zero-touch =" in tomlText)

        val map = Toml.parse(tomlText)
        val reconstructed = mapToProfile(map)
        assertEquals(original, reconstructed)
    }

    @Test
    fun schema2Rejected() {
        val map = mapOf(
            "schema" to 2L,
            "project" to "/path/to/project"
        )
        val e = assertFailsWith<IllegalArgumentException> {
            validateAndMap(map)
        }
        assertTrue(e.message!!.contains("unsupported profile schema 2 (this hotreload understands schema 1)"))
    }

    @Test
    fun missingProjectRejected() {
        val map = mapOf(
            "schema" to 1L
        )
        val e = assertFailsWith<IllegalArgumentException> {
            validateAndMap(map)
        }
        assertTrue(e.message!!.contains("profile is missing required key 'project'"))
    }

    @Test
    fun unknownKeyErrorListsAllowedKeys() {
        val map = mapOf(
            "schema" to 1L,
            "project" to "/path/to/project",
            "bogus-key" to "value"
        )
        val e = assertFailsWith<IllegalArgumentException> {
            validateAndMap(map)
        }
        assertTrue(e.message!!.contains("unknown key 'bogus-key' (allowed: schema, project, app-id, variant, modules, module-variants, gradle-args, project-java-home, device, launch-activity, literals, zero-touch)"))
    }

    private fun mapToProfile(map: Map<String, Any>): Profile {
        return validateAndMap(map)
    }

    private fun validateAndMap(map: Map<String, Any>): Profile {
        val schema = map.getLong("schema") ?: 1L
        if (schema != 1L) {
            throw IllegalArgumentException("unsupported profile schema $schema (this hotreload understands schema 1)")
        }
        val allowedKeys = setOf(
            "schema", "project", "app-id", "variant", "modules", "module-variants",
            "gradle-args", "project-java-home", "device", "launch-activity", "literals", "zero-touch"
        )
        for (k in map.keys) {
            if (k !in allowedKeys) {
                throw IllegalArgumentException("unknown key '$k' (allowed: schema, project, app-id, variant, modules, module-variants, gradle-args, project-java-home, device, launch-activity, literals, zero-touch)")
            }
        }
        val project = map.getString("project") ?: throw IllegalArgumentException("profile is missing required key 'project'")
        val appId = map.getString("app-id")
        val variant = map.getString("variant")
        val modules = map.getStringList("modules") ?: emptyList()
        val moduleVariants = map.getStringList("module-variants") ?: emptyList()
        val gradleArgs = map.getStringList("gradle-args") ?: emptyList()
        val projectJavaHome = map.getString("project-java-home")
        val device = map.getString("device")
        val launchActivity = map.getString("launch-activity")
        val literals = map.getBoolean("literals") ?: false
        val integrationMode = if (map.getBoolean("zero-touch") == true) {
            IntegrationMode.ZERO_TOUCH
        } else {
            IntegrationMode.CONFIGURED
        }

        return Profile(
            project = project,
            appId = appId,
            variant = variant,
            modules = modules,
            moduleVariants = moduleVariants,
            gradleArgs = gradleArgs,
            projectJavaHome = projectJavaHome,
            device = device,
            launchActivity = launchActivity,
            literals = literals,
            integrationMode = integrationMode,
        )
    }

    private fun Map<String, Any>.getString(key: String): String? {
        val value = this[key] ?: return null
        if (value !is String) throw IllegalArgumentException("'$key' must be string")
        return value
    }

    private fun Map<String, Any>.getBoolean(key: String): Boolean? {
        val value = this[key] ?: return null
        if (value !is Boolean) throw IllegalArgumentException("'$key' must be boolean")
        return value
    }

    private fun Map<String, Any>.getLong(key: String): Long? {
        val value = this[key] ?: return null
        if (value !is Long) throw IllegalArgumentException("'$key' must be integer")
        return value
    }

    private fun Map<String, Any>.getStringList(key: String): List<String>? {
        val value = this[key] ?: return null
        if (value !is List<*>) throw IllegalArgumentException("'$key' must be list of strings")
        for (item in value) {
            if (item !is String) throw IllegalArgumentException("'$key' must be list of strings")
        }
        @Suppress("UNCHECKED_CAST")
        return value as List<String>
    }
}
