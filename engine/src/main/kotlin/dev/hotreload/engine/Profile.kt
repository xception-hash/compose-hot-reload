package dev.hotreload.engine

import java.nio.file.Files
import java.nio.file.Path

data class Profile(
    val project: String,                        // absolute path, REQUIRED
    val appId: String? = null,
    val variant: String? = null,
    val modules: List<String> = emptyList(),    // "gradlePath=relativeDir" entries, app FIRST
    val moduleVariants: List<String> = emptyList(), // "gradlePath=variant" (--module-variant form)
    val gradleArgs: List<String> = emptyList(),
    val projectJavaHome: String? = null,
    val device: String? = null,
    val launchActivity: String? = null,
    val literals: Boolean = false,
    val integrationMode: IntegrationMode = IntegrationMode.CONFIGURED,
) {
    init {
        validateUserGradleArgs(gradleArgs)
    }

    fun toToml(name: String): String {
        val sb = StringBuilder()
        sb.append("# Compose Hot Reload profile \"$name\" — written by `hotreload configure`.\n")
        sb.append("# CLI flags override these values. Docs: README \"Profiles\".\n")
        sb.append("schema = 1\n")
        sb.append("project = ${Toml.writeString(project)}\n")
        if (appId != null) {
            sb.append("app-id = ${Toml.writeString(appId)}\n")
        }
        if (variant != null) {
            sb.append("variant = ${Toml.writeString(variant)}\n")
        }
        sb.append("modules = [${modules.joinToString(", ") { Toml.writeString(it) }}]\n")
        if (moduleVariants.isNotEmpty()) {
            sb.append("module-variants = [${moduleVariants.joinToString(", ") { Toml.writeString(it) }}]\n")
        }
        if (gradleArgs.isNotEmpty()) {
            sb.append("gradle-args = [${gradleArgs.joinToString(", ") { Toml.writeString(it) }}]\n")
        }
        if (projectJavaHome != null) {
            sb.append("project-java-home = ${Toml.writeString(projectJavaHome)}\n")
        }
        if (device != null) {
            sb.append("device = ${Toml.writeString(device)}\n")
        }
        if (launchActivity != null) {
            sb.append("launch-activity = ${Toml.writeString(launchActivity)}\n")
        }
        if (literals) {
            sb.append("literals = true\n")
        }
        if (integrationMode == IntegrationMode.ZERO_TOUCH) {
            sb.append("zero-touch = true\n")
        }
        return sb.toString()
    }
}

class ProfileStore(val baseDir: Path) {
    private fun validateName(name: String) {
        if (!name.matches(Regex("^[A-Za-z0-9._-]+$")) || ".." in name) {
            throw IllegalArgumentException("invalid profile name '$name' (allowed: [A-Za-z0-9._-], no '..')")
        }
    }

    fun path(name: String): Path {
        validateName(name)
        return baseDir.resolve("projects").resolve("$name.toml")
    }

    fun discoveryPath(name: String): Path {
        validateName(name)
        return baseDir.resolve("projects").resolve("$name.discovery.json")
    }

    fun saveDiscovery(name: String, json: String): Path {
        validateName(name)
        val file = discoveryPath(name)
        Files.createDirectories(file.parent)
        Files.writeString(file, json)
        return file
    }

    fun loadDiscovery(name: String): String? {
        validateName(name)
        val file = discoveryPath(name)
        if (!Files.exists(file)) return null
        return Files.readString(file)
    }

    fun load(name: String): Profile {
        validateName(name)
        val file = path(name)
        if (!Files.exists(file)) {
            throw IllegalArgumentException("profile '$name' not found: $file")
        }
        try {
            val text = Files.readString(file)
            val map = Toml.parse(text)
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
        } catch (e: Exception) {
            throw IllegalArgumentException("profile '$name': ${e.message}", e)
        }
    }

    fun save(name: String, profile: Profile): Path {
        validateName(name)
        val file = path(name)
        Files.createDirectories(file.parent)
        Files.writeString(file, profile.toToml(name))
        return file
    }

    companion object {
        fun default(): ProfileStore {
            val envConfigDir = System.getenv("HOTRELOAD_CONFIG_DIR")
            val baseDir = if (!envConfigDir.isNullOrBlank()) {
                Path.of(envConfigDir)
            } else {
                val envXdg = System.getenv("XDG_CONFIG_HOME")
                if (!envXdg.isNullOrBlank()) {
                    Path.of(envXdg).resolve("compose-hot-reload")
                } else {
                    Path.of(System.getProperty("user.home")).resolve(".config/compose-hot-reload")
                }
            }
            return ProfileStore(baseDir)
        }
    }
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
