package dev.hotreload.idea

import com.intellij.openapi.diagnostic.logger
import java.net.URI
import java.nio.file.Path
import java.nio.file.Paths

/**
 * This plugin's own version and install directory, resolved WITHOUT any IntelliJ
 * plugin-descriptor API.
 *
 * As of the 2026.2 platform every `PluginManager` descriptor lookup
 * (`getPluginByClass`, `findEnabledPlugin`, `getPlugin`, …) is annotated
 * `@ApiStatus.Internal`, and the Marketplace compat check rejects any use of them — that is
 * exactly what bounced 0.1.2 (twice) and 0.1.3. There is no public platform accessor for a
 * plugin's own descriptor, so we avoid the platform entirely:
 *
 *  - [version] is baked in at build time (see `build.gradle.kts` `generatePluginProperties`)
 *    and read back from a bundled resource.
 *  - [installDir] is derived from the on-disk location of this very class, which the IDE loads
 *    from `<pluginDir>/lib/<plugin>.jar`.
 *
 * Both are pure JVM mechanics and therefore stable across IDE releases.
 */
internal object PluginInfo {
    private val LOG = logger<PluginInfo>()

    /** Plugin version string baked in at build time, or "unknown" if the resource is missing. */
    val version: String by lazy {
        runCatching {
            PluginInfo::class.java.getResourceAsStream("/dev/hotreload/idea/plugin.properties")?.use { stream ->
                java.util.Properties().apply { load(stream) }.getProperty("version")
            }
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "unknown"
    }

    /**
     * Filesystem directory this plugin is installed in (`<pluginDir>`), or null if it can't be
     * resolved (e.g. classes loaded from an exploded dir rather than a jar). Callers must handle
     * null — the Settings CLI-path override is the escape hatch.
     */
    val installDir: Path? by lazy { locateInstallDir() }

    private fun locateInstallDir(): Path? {
        val cls = PluginInfo::class.java
        val res = cls.getResource(cls.simpleName + ".class")
        if (res == null) {
            LOG.warn("cannot resolve plugin install dir: no resource URL for ${cls.name}")
            return null
        }
        // Expected: jar:file:/…/<pluginDir>/lib/<plugin>.jar!/dev/hotreload/idea/PluginInfo.class
        if (res.protocol != "jar") {
            LOG.warn("cannot resolve plugin install dir: class loaded from '${res.protocol}', not a jar ($res)")
            return null
        }
        val spec = res.path // file:/…/lib/<plugin>.jar!/dev/hotreload/idea/PluginInfo.class
        val bang = spec.indexOf("!/")
        if (bang < 0) {
            LOG.warn("cannot resolve plugin install dir: unexpected jar URL '$res'")
            return null
        }
        val jar = runCatching { Paths.get(URI(spec.substring(0, bang))) }.getOrElse {
            LOG.warn("cannot resolve plugin install dir: bad jar URI in '$res'", it)
            return null
        }
        // <pluginDir>/lib/<plugin>.jar  →  parent = lib  →  parent = <pluginDir>
        val dir = jar.parent?.parent
        if (dir == null) LOG.warn("cannot resolve plugin install dir: jar has no grandparent ($jar)")
        return dir
    }
}
