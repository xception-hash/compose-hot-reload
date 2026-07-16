package dev.hotreload.idea

import java.io.File

/**
 * Locates an Android SDK when the user gave no explicit SDK path in settings. Pure `java.io.File`
 * logic only (no IntelliJ platform types) so it's unit-testable with `@TempDir`.
 */
object AndroidSdkResolver {
    /** Absolute Android SDK dir, or null if none found. Only the first candidate that
     *  actually exists on disk is returned, so callers can fall through to the CLI's own error. */
    fun discover(projectDir: String, env: Map<String, String>, userHome: String, isWindows: Boolean): String? {
        val candidates = buildList {
            add(localPropertiesSdkDir(projectDir))
            add(env["ANDROID_HOME"])
            add(env["ANDROID_SDK_ROOT"])
            if (isWindows) {
                add(env["LOCALAPPDATA"]?.let { "$it\\Android\\Sdk" })
            } else {
                add("$userHome/Library/Android/sdk")
                add("$userHome/Android/Sdk")
            }
        }
        for (candidate in candidates) {
            val path = candidate?.takeIf { it.isNotBlank() } ?: continue
            val expanded = expandUserHome(path, userHome)
            val file = File(expanded)
            if (file.isDirectory) return file.absolutePath
        }
        return null
    }

    private fun localPropertiesSdkDir(projectDir: String): String? {
        val file = File(projectDir, "local.properties")
        if (!file.isFile) return null
        val line = file.readLines().firstOrNull { it.trim().startsWith("sdk.dir=") } ?: return null
        val value = line.trim().removePrefix("sdk.dir=").trim()
            .replace("\\:", ":")
            .replace("\\\\", "\\")
        return value.takeIf { it.isNotBlank() }
    }
}
