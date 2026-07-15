package dev.hotreload.engine

import java.nio.file.Path

enum class IntegrationMode {
    CONFIGURED,
    ZERO_TOUCH,
}

/**
 * Reject user Gradle arguments that attempt to set properties owned by the zero-touch
 * bootstrap. Internal bootstrap arguments are assembled separately and must never pass
 * through this validator.
 */
object ReservedGradleProperties {
    const val PREFIX = "dev.hotreload.bootstrap."

    fun validate(args: List<String>) {
        fun rejectIfReserved(propertyAndValue: String, systemProperty: Boolean = false) {
            val property = propertyAndValue.substringBefore('=')
            val projectProperty = if (systemProperty) {
                property.removePrefix("org.gradle.project.")
            } else {
                property
            }
            require(!projectProperty.startsWith(PREFIX)) {
                "Gradle property '$projectProperty' is reserved for zero-touch bootstrap internals " +
                    "and cannot be passed with --gradle-arg"
            }
        }

        var index = 0
        while (index < args.size) {
            val arg = args[index]
            when {
                arg == "-P" || arg == "--project-prop" -> {
                    args.getOrNull(index + 1)?.let { rejectIfReserved(it) }
                    index += 2
                }
                arg.startsWith("-P") && arg.length > 2 -> {
                    rejectIfReserved(arg.substring(2))
                    index++
                }
                arg.startsWith("--project-prop=") -> {
                    rejectIfReserved(arg.substringAfter('='))
                    index++
                }
                arg == "-D" || arg == "--system-prop" -> {
                    args.getOrNull(index + 1)?.let { rejectIfReserved(it, systemProperty = true) }
                    index += 2
                }
                arg.startsWith("-D") && arg.length > 2 -> {
                    rejectIfReserved(arg.substring(2), systemProperty = true)
                    index++
                }
                arg.startsWith("--system-prop=") -> {
                    rejectIfReserved(arg.substringAfter('='), systemProperty = true)
                    index++
                }
                else -> index++
            }
        }
    }
}

fun validateUserGradleArgs(args: List<String>) = ReservedGradleProperties.validate(args)

/**
 * The typed, fully-resolved project configuration shared by the CLI, [Doctor], and
 * [WatchSession] (T33 phase 1): consolidates what used to be threaded twice (once into
 * Doctor's constructor, once into WatchSession.Config) into a single object.
 */
data class ProjectConfig(
    val projectDir: Path,
    /** Watched Gradle modules; the FIRST holds the app (applicationId, APK, resources). */
    val modules: List<ModuleSpec.Request>,
    val applicationId: String,
    val variant: String = "debug",
    val projectJavaHome: Path? = null,
    val gradleArgs: List<String> = emptyList(),
    val integrationMode: IntegrationMode = IntegrationMode.CONFIGURED,
    /**
     * Live-literals fast path (T24, `--literals`): literal-only edits are pushed in
     * place through Compose live literals before the normal compile+swap runs. Requires
     * the app built with `-Photreload.liveLiterals=true` (verified at startup).
     */
    val literals: Boolean = false,
    /** `--device`; null = adb's default-device behavior. */
    val deviceSerial: String? = null,
    /** `--launch-activity`; null = monkey LAUNCHER fallback. */
    val launchActivity: String? = null,
    val moduleMetadata: Map<String, ModuleMetadata> = emptyMap(),
) {
    init {
        require(modules.isNotEmpty()) { "at least one module (the app module) is required" }
        require(variant.isNotBlank()) { "variant must not be blank" }
        require(applicationId.matches(Regex("^[A-Za-z0-9._]+$"))) {
            "applicationId contains characters outside [A-Za-z0-9._]: $applicationId"
        }
        validateUserGradleArgs(gradleArgs)
        launchActivity?.let {
            require(it.matches(Regex("^[A-Za-z0-9._\$]+$"))) {
                "launchActivity contains characters outside [A-Za-z0-9._\$]: $it"
            }
        }
    }

    val appModule: ModuleSpec.Request get() = modules.first()

    companion object {
        /**
         * Reorder/override for explicit app-module selection (T33a `--app-module` /
         * `--app-module-dir`).
         * - [appModulePath]: must equal the gradlePath of one entry in [modules] (normalized
         *   through [ModuleSpec.Request.parse] so `app` == `:app`); that entry moves to index 0.
         * - [appModuleDir]: replaces the (post-reorder) app entry's relativeDir.
         * - Both null → [modules] is returned untouched (current behavior).
         */
        fun selectAppModule(
            modules: List<ModuleSpec.Request>,
            appModulePath: String?,
            appModuleDir: String?,
        ): List<ModuleSpec.Request> {
            var result = modules
            if (appModulePath != null) {
                val normalized = ModuleSpec.Request.parse(appModulePath).gradlePath
                val index = result.indexOfFirst { it.gradlePath == normalized }
                require(index >= 0) { "--app-module '$appModulePath' does not match any watched module" }
                if (index != 0) {
                    result = listOf(result[index]) + result.filterIndexed { i, _ -> i != index }
                }
            }
            if (appModuleDir != null) {
                require(!Path.of(appModuleDir).isAbsolute) {
                    "module directory must be relative to the project root: $appModuleDir"
                }
                result = result.mapIndexed { i, request ->
                    if (i == 0) request.copy(relativeDir = appModuleDir) else request
                }
            }
            return result
        }
    }
}
