package dev.hotreload.engine

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import dev.hotreload.protocol.Protocol
import java.nio.file.Files
import java.nio.file.Path

/**
 * Build fingerprint written by `hotreload prepare`, validated before `hotreload watch`
 * (T33 phase 6). It records the resolved-config fields that shape the installed APK plus
 * the sha256 of the base APK **as installed on the device**, so watch can positively
 * detect a stale/mismatched APK (e.g. one built `-Photreload.liveLiterals=true` while
 * watch runs without `--literals`, which silently corrupts the Compose slot table on the
 * first swap) and REFUSE instead of failing confusingly deep in a swap.
 *
 * Only `prepare` ever writes one. With no fingerprint file on disk the feature is unused
 * and watch/doctor behave byte-identically to before (the e2e regression contract).
 */
data class Fingerprint(
    val schemaVersion: Int = 1,
    val applicationId: String,
    val deviceSerial: String,
    val projectDir: String,
    val variant: String,
    val modules: List<String>,        // sorted "gradlePath=relativeDir"
    val moduleVariants: List<String>, // sorted "gradlePath=variant"
    val literals: Boolean,
    val integrationMode: IntegrationMode,
    val runtimeArtifactSha256: String?,
    val gradleArgs: List<String>,
    val projectJavaHome: String?,
    val protocolVersion: Int,
    val compilerFlags: List<String>,
    val hostJavaMajor: Int,
    val apkSha256: String?,           // null = device query failed at prepare
    val apkPath: String,
    val preparedAt: Long,
) {
    /**
     * Non-empty = mismatch; each entry `"field: recorded 'x' != resolved 'y'"`. Compares
     * exactly the hard-compared fields; it does NOT compare [apkSha256] (the caller checks
     * that against the live device value — a separate knowledge-state rule) nor the
     * informational-only fields (compilerFlags, hostJavaMajor, preparedAt, apkPath).
     */
    fun mismatches(config: ProjectConfig): List<String> {
        val resolved = of(config, deviceSerial, apkSha256, apkPath)
        val out = mutableListOf<String>()
        fun cmp(field: String, recorded: Any?, other: Any?) {
            if (recorded != other) out += "$field: recorded '$recorded' != resolved '$other'"
        }
        cmp("variant", variant, resolved.variant)
        cmp("modules", modules, resolved.modules)
        cmp("moduleVariants", moduleVariants, resolved.moduleVariants)
        cmp("literals", literals, resolved.literals)
        cmp("integrationMode", integrationMode, resolved.integrationMode)
        cmp("runtimeArtifactSha256", runtimeArtifactSha256, resolved.runtimeArtifactSha256)
        cmp("gradleArgs", gradleArgs, resolved.gradleArgs)
        cmp("projectJavaHome", projectJavaHome, resolved.projectJavaHome)
        cmp("protocolVersion", protocolVersion, resolved.protocolVersion)
        return out
    }

    companion object {
        val REQUIRED_COMPILER_FLAGS = listOf(
            "-Xlambdas=class", "-Xsam-conversions=class", "-Xstring-concat=inline",
        )

        /** Builds a fingerprint from a resolved [ProjectConfig] + the current [Protocol.VERSION]. */
        fun of(
            config: ProjectConfig,
            deviceSerial: String,
            apkSha256: String?,
            apkPath: String,
        ): Fingerprint = Fingerprint(
            applicationId = config.applicationId,
            deviceSerial = deviceSerial,
            projectDir = config.projectDir.toString(),
            variant = config.variant,
            modules = config.modules.map { "${it.gradlePath}=${it.relativeDir}" }.sorted(),
            moduleVariants = config.modules
                .filter { it.variant != null }
                .map { "${it.gradlePath}=${it.variant}" }
                .sorted(),
            literals = config.literals,
            integrationMode = config.integrationMode,
            runtimeArtifactSha256 = if (config.integrationMode == IntegrationMode.ZERO_TOUCH) {
                ZeroTouchArtifacts.runtimeArtifactSha256()
            } else {
                null
            },
            gradleArgs = config.gradleArgs,
            projectJavaHome = config.projectJavaHome?.toString(),
            protocolVersion = Protocol.VERSION,
            compilerFlags = REQUIRED_COMPILER_FLAGS,
            hostJavaMajor = try { Runtime.version().feature() } catch (_: Throwable) { 0 },
            apkSha256 = apkSha256,
            apkPath = apkPath,
            preparedAt = System.currentTimeMillis(),
        )
    }
}

/** Host-side store: one JSON file per (device, app) under `<baseDir>/fingerprints/`. */
class FingerprintStore(private val baseDir: Path) {

    fun path(serial: String, applicationId: String): Path {
        val sanitized = serial.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return baseDir.resolve("fingerprints").resolve("${sanitized}_$applicationId.json")
    }

    fun save(fp: Fingerprint): Path {
        val file = path(fp.deviceSerial, fp.applicationId)
        Files.createDirectories(file.parent)
        Files.writeString(file, GSON.toJson(fp))
        return file
    }

    /** null when absent OR unparsable (unparsable also prints the loud ignore line). */
    fun load(serial: String, applicationId: String): Fingerprint? {
        val file = path(serial, applicationId)
        if (!Files.exists(file)) return null
        return try {
            val json = JsonParser.parseString(Files.readString(file)).asJsonObject
            // Fingerprints written before zero-touch existed necessarily describe a
            // configured-mode APK. Normalize that additive schema-1 field before Gson
            // constructs the non-null enum property.
            if (!json.has("integrationMode")) {
                json.addProperty("integrationMode", IntegrationMode.CONFIGURED.name)
            } else {
                val mode = json.get("integrationMode")
                require(!mode.isJsonNull) { "integrationMode must not be null" }
                IntegrationMode.valueOf(mode.asString)
            }
            GSON.fromJson(json, Fingerprint::class.java)
                ?: throw IllegalStateException("empty JSON")
        } catch (e: Exception) {
            println("fingerprint: ignoring unreadable fingerprint file (${e.message})")
            null
        }
    }

    companion object {
        private val GSON: Gson = GsonBuilder().setPrettyPrinting().create()

        /** Same baseDir resolution as [ProfileStore.default] (honors HOTRELOAD_CONFIG_DIR). */
        fun default(): FingerprintStore {
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
            return FingerprintStore(baseDir)
        }
    }
}
