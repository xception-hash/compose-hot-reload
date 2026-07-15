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

        val closure = buildClosure(appProject.gradlePath, appVariant.projectDependencies.orEmpty(), byPath)

        val moduleSpecs = closure.mapNotNull { path ->
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

/**
 * The resolved watch plan from discovery: which variant, applicationId, and module
 * closure to watch (T33d).
 */
data class WatchPlan(
    val variantName: String,
    val applicationId: String?,
    val modules: List<ModuleSpec.Request>,
)

/** Per-module build metadata resolved from a DiscoveryReport for one watch plan. */
data class ModuleMetadata(
    val compileTask: String? = null,   // task PATHS, e.g. ":app:compileDebugKotlin"
    val assembleTask: String? = null,
    val installTask: String? = null,
    val classOutputDirs: List<String> = emptyList(), // rootDir-relative, as reported
    val sourceDirs: List<String> = emptyList(),
    val resDirs: List<String> = emptyList(),
    val apkOutputDir: String? = null,  // app module only
)

fun DiscoveryReport.moduleMetadata(
    modules: List<ModuleSpec.Request>,
    defaultVariant: String,
): Map<String, ModuleMetadata> {
    val allProjects = projects.orEmpty()
    val byPath = allProjects.associateBy { it.gradlePath }

    return modules.mapNotNull { request ->
        val project = byPath[request.gradlePath] ?: return@mapNotNull null
        val metadata = when (project.type) {
            "androidApp", "androidLib" -> {
                val variantName = request.variant ?: defaultVariant
                val variant = project.variants.orEmpty().firstOrNull { it.name == variantName }
                    ?: return@mapNotNull null
                ModuleMetadata(
                    compileTask = variant.tasks?.compileKotlin,
                    assembleTask = variant.tasks?.assemble,
                    installTask = variant.tasks?.install,
                    classOutputDirs = variant.classOutputDirs.orEmpty(),
                    sourceDirs = variant.sourceDirs.orEmpty(),
                    resDirs = variant.resDirs.orEmpty(),
                    apkOutputDir = if (project.type == "androidApp") variant.apkOutputDir else null
                )
            }
            "kotlinJvm" -> {
                val jvm = project.jvm ?: return@mapNotNull null
                ModuleMetadata(
                    classOutputDirs = jvm.classOutputDirs.orEmpty(),
                    sourceDirs = jvm.sourceDirs.orEmpty()
                )
            }
            else -> return@mapNotNull null
        }
        request.gradlePath to metadata
    }.toMap()
}

/**
 * Resolve a [WatchPlan] from a [DiscoveryReport].
 *
 * @param appModulePath `--app-module` (normalized via `ModuleSpec.Request.parse`); null = auto-detect
 * @param variantName `--variant`; null = pick automatically
 * @param includeModules normalized gradle paths for `--include-module`
 * @param excludeModules normalized gradle paths for `--exclude-module`
 */
fun DiscoveryReport.resolveWatchPlan(
    appModulePath: String?,
    variantName: String?,
    includeModules: List<String>,
    excludeModules: List<String>,
): WatchPlan {
    val allProjects = projects.orEmpty()
    val byPath = allProjects.associateBy { it.gradlePath }

    // --- App project ---
    val appProject: DiscoveredProject
    if (appModulePath != null) {
        val normalized = ModuleSpec.Request.parse(appModulePath).gradlePath
        appProject = byPath[normalized]
            ?: throw IllegalArgumentException("--app-module '$appModulePath' not found in the project")
        require(appProject.type == "androidApp") {
            "--app-module '$appModulePath' is ${appProject.type}, not an Android application module"
        }
    } else {
        val androidApps = allProjects.filter { it.type == "androidApp" }
        when {
            androidApps.isEmpty() -> throw IllegalArgumentException(
                "no Android application module found — pass --module and --app-id explicitly"
            )
            androidApps.size > 1 -> throw IllegalArgumentException(
                "multiple application modules found (${androidApps.joinToString { it.gradlePath }}) — pass --app-module"
            )
            else -> appProject = androidApps.single()
        }
    }

    // --- Variant (within the app project's debuggable variants only) ---
    val debuggableVariants = appProject.variants.orEmpty().filter { it.debuggable == true }
    if (debuggableVariants.isEmpty()) {
        throw IllegalArgumentException("no debuggable variants in ${appProject.gradlePath}")
    }

    val appVariant: DiscoveredVariant
    if (variantName != null) {
        appVariant = debuggableVariants.firstOrNull { it.name == variantName }
            ?: throw IllegalArgumentException(
                "variant '$variantName' not found or not debuggable — debuggable variants: ${debuggableVariants.joinToString { it.name }}"
            )
    } else {
        appVariant = debuggableVariants.firstOrNull { it.name == "debug" }
            ?: if (debuggableVariants.size == 1) debuggableVariants.single()
            else throw IllegalArgumentException(
                "multiple debuggable variants (${debuggableVariants.joinToString { it.name }}) — pass --variant"
            )
    }

    // --- Closure ---
    val closurePaths = buildClosure(appProject.gradlePath, appVariant.projectDependencies.orEmpty(), byPath)

    var moduleRequests = closurePaths.mapNotNull { path ->
        val proj = byPath[path] ?: return@mapNotNull null
        ModuleSpec.Request(path, proj.projectDir.orEmpty(), variant = null)
    }

    // --- Filters (applied to the closure, app module exempt) ---
    val appPath = appProject.gradlePath
    val closurePathSet = moduleRequests.map { it.gradlePath }.toSet()

    if (includeModules.isNotEmpty()) {
        for (inc in includeModules) {
            if (inc !in closurePathSet) {
                throw IllegalArgumentException(
                    "--include-module '$inc' is not a watched module (watched: ${closurePathSet.joinToString()})"
                )
            }
        }
        moduleRequests = moduleRequests.filter { it.gradlePath == appPath || it.gradlePath in includeModules }
    }

    if (excludeModules.isNotEmpty()) {
        val currentPaths = moduleRequests.map { it.gradlePath }.toSet()
        for (exc in excludeModules) {
            if (exc == appPath) {
                throw IllegalArgumentException("cannot exclude the app module '$exc'")
            }
            if (exc !in currentPaths) {
                throw IllegalArgumentException(
                    "--exclude-module '$exc' is not a watched module (watched: ${currentPaths.joinToString()})"
                )
            }
        }
        moduleRequests = moduleRequests.filter { it.gradlePath !in excludeModules }
    }

    return WatchPlan(
        variantName = appVariant.name,
        applicationId = appVariant.applicationId,
        modules = moduleRequests,
    )
}

/**
 * Build the `hotreload watch` command that reproduces a [WatchPlan] without discovery.
 */
fun DiscoveryReport.watchCommandFor(plan: WatchPlan, projectDir: String): String {
    val moduleSpecs = plan.modules.joinToString(",") { "${it.gradlePath}=${it.relativeDir}" }
    val parts = mutableListOf(
        "hotreload", "watch",
        "--project", projectDir,
        "--app-id", plan.applicationId.orEmpty(),
        "--module", moduleSpecs,
    )
    if (plan.variantName != "debug") {
        parts += listOf("--variant", plan.variantName)
    }
    return parts.joinToString(" ")
}

/**
 * BFS the declared project-dependency graph starting at the app, picking up each
 * dependency project's own declared deps.
 */
internal fun buildClosure(
    appPath: String,
    appDeps: List<String>,
    byPath: Map<String, DiscoveredProject>,
): List<String> {
    val moduleOrder = LinkedHashSet<String>()
    moduleOrder += appPath
    val queue = ArrayDeque(appDeps)
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
    return moduleOrder.toList()
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

    /** Parses a schema-v1 JSON back to a [DiscoveryReport]. */
    fun fromJson(json: String): DiscoveryReport = Gson().fromJson(json, DiscoveryReport::class.java)

    private fun extractInitScript(): Path {
        val bytes = GradleDiscovery::class.java.getResourceAsStream("/dev/hotreload/inspect.init.gradle")
            ?.use { it.readBytes() }
            ?: error("inspect.init.gradle resource missing from the engine jar")
        val tmp = Files.createTempFile("hotreload-inspect-", ".init.gradle")
        tmp.writeBytes(bytes)
        return tmp
    }
}
