package dev.hotreload.engine

import java.io.Closeable
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlin.io.path.absolutePathString

/** Bundled zero-touch payloads extracted outside the target tree for one Gradle session. */
class ZeroTouchArtifacts private constructor(
    private val root: Path,
    val bootstrapJar: Path,
    val runtimeAar: Path,
    val initScript: Path,
    val runtimeArtifactSha256: String,
) : Closeable {

    override fun close() {
        root.toFile().deleteRecursively()
    }

    companion object {
        private const val ROOT = "/dev/hotreload/bootstrap/"
        private const val BOOTSTRAP_JAR = "bootstrap.jar"
        private const val RUNTIME_AAR = "runtime-client.aar"
        private const val INIT_SCRIPT = "zero-touch.init.gradle"

        fun extract(): ZeroTouchArtifacts {
            val root = Files.createTempDirectory("compose-hotreload-bootstrap-").toAbsolutePath().normalize()
            runCatching {
                Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwx------"))
            }
            try {
                val jarBytes = resourceBytes(BOOTSTRAP_JAR)
                val aarBytes = resourceBytes(RUNTIME_AAR)
                val scriptBytes = resourceBytes(INIT_SCRIPT)
                val jar = writeContained(root, BOOTSTRAP_JAR, jarBytes)
                val aar = writeContained(root, RUNTIME_AAR, aarBytes)
                val script = writeContained(root, INIT_SCRIPT, scriptBytes)
                validateZip(jar, "dev/hotreload/bootstrap/BootstrapPlugin.class")
                validateZip(aar, "AndroidManifest.xml", "classes.jar")
                check(scriptBytes.toString(Charsets.UTF_8).contains("dev.hotreload.bootstrap.modules")) {
                    "bundled zero-touch init script is not the expected bootstrap script"
                }
                return ZeroTouchArtifacts(root, jar, aar, script, sha256(aarBytes))
            } catch (t: Throwable) {
                root.toFile().deleteRecursively()
                throw t
            }
        }

        /** Stable artifact identity for fingerprints; never includes an extracted temp path. */
        fun runtimeArtifactSha256(): String = sha256(resourceBytes(RUNTIME_AAR))

        fun verifyPackaged(): String = extract().use { it.runtimeArtifactSha256 }

        private fun resourceBytes(name: String): ByteArray =
            ZeroTouchArtifacts::class.java.getResourceAsStream(ROOT + name)?.use { it.readBytes() }
                ?: error("zero-touch payload missing from engine jar: $ROOT$name")

        private fun writeContained(root: Path, name: String, bytes: ByteArray): Path {
            val path = root.resolve(name).normalize()
            check(path.parent == root && path.startsWith(root)) { "zero-touch payload path escapes temp dir: $name" }
            Files.write(path, bytes)
            check(Files.isRegularFile(path) && !Files.isSymbolicLink(path)) {
                "zero-touch payload is not a regular file: $path"
            }
            return path
        }

        private fun validateZip(path: Path, vararg requiredEntries: String) {
            ZipFile(path.toFile()).use { zip ->
                for (entry in requiredEntries) {
                    check(zip.getEntry(entry) != null) { "bundled ${path.fileName} is missing $entry" }
                }
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val name = entries.nextElement().name
                    check(!name.startsWith("/") && name.split('/').none { it == ".." }) {
                        "unsafe ZIP entry in ${path.fileName}: $name"
                    }
                }
            }
        }

        private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }
}

/**
 * One decision point for every target Gradle invocation. User arguments stay separate
 * from tool-owned bootstrap properties and are validated before any Tooling API call.
 */
class GradleInvocation private constructor(
    val arguments: List<String>,
    val runtimeArtifactSha256: String?,
    private val artifacts: ZeroTouchArtifacts?,
) : Closeable {
    override fun close() {
        artifacts?.close()
    }

    companion object {
        private const val PREFIX = "dev.hotreload.bootstrap."
        private val GRADLE_PATH = Regex("^(:[A-Za-z0-9_.-]+)+$")
        private val VARIANT = Regex("^[A-Za-z0-9_.-]+$")

        fun open(config: ProjectConfig): GradleInvocation {
            ReservedGradleProperties.validate(config.gradleArgs)
            val literalsArg = if (config.literals) listOf("-Photreload.liveLiterals=true") else emptyList()
            if (config.integrationMode == IntegrationMode.CONFIGURED) {
                return GradleInvocation(config.gradleArgs + literalsArg, null, null)
            }

            val modules = config.modules.map { it.gradlePath }
            modules.forEach { require(it.matches(GRADLE_PATH)) { "invalid Gradle module path for zero-touch bootstrap: $it" } }
            require(modules.distinct().size == modules.size) { "duplicate watched modules in zero-touch bootstrap: $modules" }
            val appModule = config.appModule.gradlePath
            require(appModule in modules) { "zero-touch app module is absent from watched modules: $appModule" }
            val variant = config.appModule.variant ?: config.variant
            require(variant.matches(VARIANT)) { "invalid selected variant for zero-touch bootstrap: $variant" }

            val artifacts = ZeroTouchArtifacts.extract()
            val internal = listOf(
                "--no-configuration-cache",
                "--init-script", artifacts.initScript.absolutePathString(),
                "-P${PREFIX}jar=${artifacts.bootstrapJar.absolutePathString()}",
                "-P${PREFIX}runtimeAar=${artifacts.runtimeAar.absolutePathString()}",
                "-P${PREFIX}modules=${modules.joinToString(",")}",
                "-P${PREFIX}appModule=$appModule",
                "-P${PREFIX}variant=$variant",
            )
            return GradleInvocation(
                arguments = config.gradleArgs + literalsArg + internal,
                runtimeArtifactSha256 = artifacts.runtimeArtifactSha256,
                artifacts = artifacts,
            )
        }
    }
}
