package dev.hotreload.idea

import com.google.gson.Gson
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

/** Schema-v1 wire model emitted by `hotreload inspect --json`. Keep this independent of engine. */
data class DiscoveryReportJson(
    val schemaVersion: Int = 0,
    val projects: List<DiscoveryProjectJson>? = null,
)

data class DiscoveryProjectJson(
    val gradlePath: String? = null,
    val type: String? = null,
    val variants: List<DiscoveryVariantJson>? = null,
    val jvm: DiscoveryJvmJson? = null,
)

data class DiscoveryJvmJson(val projectDependencies: List<String>? = null)

data class DiscoveryVariantJson(
    val name: String? = null,
    val debuggable: Boolean? = null,
    val applicationId: String? = null,
    val projectDependencies: List<String>? = null,
)

data class DiscoveryChoices(
    val appModules: List<String>,
    val variantsByAppModule: Map<String, List<String>>,
    val appIdsByAppVariant: Map<Pair<String, String>, String>,
    val moduleClosureByAppVariant: Map<Pair<String, String>, List<String>>,
) {
    fun variants(appModule: String): List<String> = variantsByAppModule[appModule].orEmpty()
    fun appId(appModule: String, variant: String): String? = appIdsByAppVariant[appModule to variant]
    fun moduleClosure(appModule: String, variant: String): List<String> =
        moduleClosureByAppVariant[appModule to variant].orEmpty()
}

object DiscoveryParser {
    fun parse(json: String): DiscoveryChoices {
        val report = Gson().fromJson(json, DiscoveryReportJson::class.java)
            ?: error("inspect returned no JSON")
        require(report.schemaVersion == 1) { "unsupported inspect schema ${report.schemaVersion}; expected schema 1" }
        val projects = report.projects.orEmpty()
        val byPath = projects.mapNotNull { it.gradlePath?.let { path -> path to it } }.toMap()
        val apps = projects.filter { it.type == "androidApp" && !it.gradlePath.isNullOrBlank() }
        val variants = linkedMapOf<String, List<String>>()
        val ids = linkedMapOf<Pair<String, String>, String>()
        val closures = linkedMapOf<Pair<String, String>, List<String>>()
        for (app in apps) {
            val appPath = app.gradlePath!!
            val debuggable = app.variants.orEmpty().filter { it.debuggable == true && !it.name.isNullOrBlank() }
            variants[appPath] = debuggable.map { it.name!! }
            for (variant in debuggable) {
                val key = appPath to variant.name!!
                variant.applicationId?.takeIf { it.isNotBlank() }?.let { ids[key] = it }
                closures[key] = closure(appPath, variant.projectDependencies.orEmpty(), byPath)
            }
        }
        return DiscoveryChoices(variants.keys.toList(), variants, ids, closures)
    }

    private fun closure(root: String, dependencies: List<String>, byPath: Map<String, DiscoveryProjectJson>): List<String> {
        val result = linkedSetOf(root)
        fun visit(path: String) {
            if (!result.add(path)) return
            val project = byPath[path] ?: return
            val next = when (project.type) {
                "androidApp", "androidLib" -> project.variants.orEmpty().firstOrNull()?.projectDependencies.orEmpty()
                "kotlinJvm" -> project.jvm?.projectDependencies.orEmpty()
                else -> emptyList()
            }
            next.forEach(::visit)
        }
        dependencies.forEach(::visit)
        return result.toList()
    }
}

/** Runs non-mutating Gradle discovery outside the EDT and returns a schema-v1 choice model. */
@Service(Service.Level.PROJECT)
class HotReloadDiscoveryService(private val project: Project) {
    fun refresh(config: HotReloadWatchConfig, callback: (Result<DiscoveryChoices>) -> Unit) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching {
                require(config.projectDir.isNotBlank()) { "project dir is not set" }
                val launcher = HotReloadService.getInstance(project).resolveLauncher(config)
                val command = GeneralCommandLine(launcher.toString())
                    .withWorkDirectory(config.projectDir)
                    .withEnvironment("JAVA_HOME", System.getProperty("java.home"))
                    .withParameters(config.inspectArguments())
                val process = command.createProcess()
                val output = ProcessOutputCollector.collect(process)
                val stdout = output.stdout.toString(Charsets.UTF_8)
                val stderr = output.stderr.toString(Charsets.UTF_8)
                check(output.exitCode == 0) { (stderr.ifBlank { stdout }).trim().ifBlank { "hotreload inspect failed" } }
                DiscoveryParser.parse(stdout)
            }
            ApplicationManager.getApplication().invokeLater { callback(result) }
        }
    }

    companion object {
        fun getInstance(project: Project): HotReloadDiscoveryService = project.service()
    }
}
