package dev.hotreload.engine

import com.google.gson.Gson
import org.gradle.tooling.GradleConnector
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.readText
import kotlin.io.path.writeBytes

/**
 * Schema v1 (T33c): the JSON report written by `engine/src/main/resources/dev/hotreload/inspect.init.gradle`
 * and read back by [GradleDiscovery.run]. Field names match the JSON keys exactly — gson
 * reflects them directly, no `@SerializedName` needed.
 *
 * Optional fields are nullable rather than defaulted: gson's Kotlin data-class handling
 * bypasses the primary constructor (so default parameter values are never applied) and
 * simply leaves missing fields `null`. Consumers use `.orEmpty()` / `?.` accordingly.
 */
data class DiscoveryReport(
    val schemaVersion: Int = 0,
    val gradleVersion: String? = null,
    val rootProjectName: String? = null,
    val rootDir: String? = null,
    val javaHome: String? = null,
    val projects: List<DiscoveredProject>? = null,
) {
    /**
     * First `androidApp` project + its first debuggable variant (exact name `debug`
     * preferred), plus the transitive closure of that variant's declared project
     * dependencies walked through the report. Null when no debuggable app variant exists.
     */
    fun suggestedWatchCommand(): String? {
        val allProjects = projects.orEmpty()
        val byPath = allProjects.associateBy { it.gradlePath }

        val appProject = allProjects.firstOrNull { it.type == "androidApp" } ?: return null
        val debuggableVariants = appProject.variants.orEmpty().filter { it.debuggable == true }
        if (debuggableVariants.isEmpty()) return null
        val appVariant = debuggableVariants.firstOrNull { it.name == "debug" } ?: debuggableVariants.first()

        // BFS the declared project-dependency graph starting at the app variant, picking
        // up each dependency project's own declared deps (first variant for android
        // types, jvm.projectDependencies for kotlinJvm) so the whole closure is watched.
        val moduleOrder = LinkedHashSet<String>()
        moduleOrder += appProject.gradlePath
        val queue = ArrayDeque(appVariant.projectDependencies.orEmpty())
        while (queue.isNotEmpty()) {
            val path = queue.removeFirst()
            if (!moduleOrder.add(path)) continue
            val dep = byPath[path] ?: continue
            val nextDeps = when (dep.type) {
                "androidApp", "androidLib" -> dep.variants.orEmpty().firstOrNull()?.projectDependencies.orEmpty()
                "kotlinJvm" -> dep.jvm?.projectDependencies.orEmpty()
                else -> emptyList()
            }
            queue += nextDeps
        }

        val moduleSpecs = moduleOrder.mapNotNull { path ->
            val proj = byPath[path] ?: return@mapNotNull null
            "$path=${proj.projectDir.orEmpty()}"
        }
        if (moduleSpecs.isEmpty()) return null

        val parts = mutableListOf(
            "hotreload", "watch",
            "--project", rootDir.orEmpty(),
            "--app-id", appVariant.applicationId.orEmpty(),
            "--module", moduleSpecs.joinToString(","),
        )
        if (appVariant.name != "debug") {
            parts += listOf("--variant", appVariant.name)
        }
        return parts.joinToString(" ")
    }
}

data class DiscoveredProject(
    val gradlePath: String = "",
    val projectDir: String? = null,
    val type: String? = null,
    val pluginIds: List<String>? = null,
    val variants: List<DiscoveredVariant>? = null,
    val jvm: JvmInfo? = null,
)

data class DiscoveredVariant(
    val name: String = "",
    val buildType: String? = null,
    val flavors: List<String>? = null,
    val debuggable: Boolean? = null,
    val applicationId: String? = null,
    val tasks: VariantTasks? = null,
    val classOutputDirs: List<String>? = null,
    val sourceDirs: List<String>? = null,
    val resDirs: List<String>? = null,
    val apkOutputDir: String? = null,
    val projectDependencies: List<String>? = null,
)

data class VariantTasks(
    val compileKotlin: String? = null,
    val assemble: String? = null,
    val install: String? = null,
)

data class JvmInfo(
    val classOutputDirs: List<String>? = null,
    val sourceDirs: List<String>? = null,
    val projectDependencies: List<String>? = null,
)

/**
 * Runs the `hotreloadInspect` task (from the bundled `inspect.init.gradle` resource) over
 * a fresh, short-lived [GradleConnector] connection — deliberately NOT [GradleCompiler]'s
 * warm daemon connection, since discovery is a one-shot call unrelated to the incremental
 * compile loop.
 */
object GradleDiscovery {

    fun run(
        projectDir: Path,
        projectJavaHome: Path? = null,
        gradleArgs: List<String> = emptyList(),
    ): DiscoveryReport {
        val initScript = extractInitScript()
        val outFile = Files.createTempFile("hotreload-inspect-", ".json")
        try {
            val connection = GradleConnector.newConnector()
                .forProjectDirectory(projectDir.toFile())
                .connect()
            val stdout = ByteArrayOutputStream()
            val stderr = ByteArrayOutputStream()
            try {
                connection.newBuild()
                    .forTasks("hotreloadInspect")
                    .withArguments(
                        listOf(
                            "--init-script", initScript.absolutePathString(),
                            "-Pdev.hotreload.inspect.out=${outFile.absolutePathString()}",
                            "--no-configuration-cache",
                        ) + gradleArgs,
                    )
                    .apply {
                        if (projectJavaHome != null) {
                            setJavaHome(projectJavaHome.toFile())
                            setEnvironmentVariables(
                                System.getenv() + ("JAVA_HOME" to projectJavaHome.absolutePathString()),
                            )
                        }
                    }
                    .setStandardOutput(stdout)
                    .setStandardError(stderr)
                    .run()
            } catch (e: Exception) {
                val tail = stderr.toString(Charsets.UTF_8).ifBlank { stdout.toString(Charsets.UTF_8) }
                    .lines().takeLast(40).joinToString("\n")
                throw IllegalStateException(
                    "hotreload inspect: Gradle discovery build failed for $projectDir:\n$tail",
                    e,
                )
            } finally {
                connection.close()
            }

            if (!Files.exists(outFile) || Files.size(outFile) == 0L) {
                throw IllegalStateException(
                    "hotreload inspect: hotreloadInspect task completed but wrote no output to $outFile",
                )
            }
            return Gson().fromJson(outFile.readText(), DiscoveryReport::class.java)
        } finally {
            Files.deleteIfExists(initScript)
            Files.deleteIfExists(outFile)
        }
    }

    /** Re-serializes a [DiscoveryReport] to schema-v1 JSON (for `hotreload inspect --json`). */
    fun toJson(report: DiscoveryReport): String = Gson().toJson(report)

    private fun extractInitScript(): Path {
        val bytes = GradleDiscovery::class.java.getResourceAsStream("/dev/hotreload/inspect.init.gradle")
            ?.use { it.readBytes() }
            ?: error("inspect.init.gradle resource missing from the engine jar")
        val tmp = Files.createTempFile("hotreload-inspect-", ".init.gradle")
        tmp.writeBytes(bytes)
        return tmp
    }
}
