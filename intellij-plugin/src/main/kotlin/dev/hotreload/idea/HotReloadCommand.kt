package dev.hotreload.idea

/**
 * The complete structured input to one CLI invocation.  Every argument remains an individual
 * token from settings through [GeneralCommandLine]; none of this layer accepts a shell command.
 */
data class HotReloadWatchConfig(
    val cliLauncherPath: String = "",
    val projectDir: String,
    val profile: String = "",
    val appModule: String = "",
    val appId: String = "",
    val variant: String = "",
    val targetJdk: String = "",
    val watchedModules: String = "app",
    val device: String = "",
    val sdkPath: String = "",
    val literals: Boolean = false,
    val zeroTouch: Boolean = false,
    val gradleArgs: List<String> = emptyList(),
    /** Advanced escape hatch. Each list element is exactly one appended CLI token. */
    val advancedTokens: List<String> = emptyList(),
) {
    fun watchArguments(): List<String> = buildList {
        add("watch")
        add("--project"); add(projectDir)
        profile.takeIf { it.isNotBlank() }?.let { add("--profile"); add(it.trim()) }
        appModule.takeIf { it.isNotBlank() }?.let { add("--app-module"); add(it.trim()) }
        appId.takeIf { it.isNotBlank() }?.let { add("--app-id"); add(it.trim()) }
        variant.takeIf { it.isNotBlank() }?.let { add("--variant"); add(it.trim()) }
        targetJdk.takeIf { it.isNotBlank() }?.let { add("--project-java-home"); add(it.trim()) }
        watchedModules.trim().ifBlank { "app" }.let { add("--module"); add(it) }
        device.takeIf { it.isNotBlank() }?.let { add("--device"); add(it.trim()) }
        sdkPath.takeIf { it.isNotBlank() }?.let { add("--sdk"); add(it.trim()) }
        if (literals) add("--literals")
        if (zeroTouch) add("--zero-touch")
        gradleArgs.filter { it.isNotBlank() }.forEach { add("--gradle-arg"); add(it) }
        addAll(advancedTokens.filter { it.isNotBlank() })
    }

    /** Discovery deliberately has a smaller, non-mutating argument surface than watch. */
    fun inspectArguments(): List<String> = buildList {
        add("inspect")
        add("--project"); add(projectDir)
        add("--json")
        profile.takeIf { it.isNotBlank() }?.let { add("--profile"); add(it.trim()) }
        targetJdk.takeIf { it.isNotBlank() }?.let { add("--project-java-home"); add(it.trim()) }
        if (zeroTouch) add("--zero-touch")
        gradleArgs.filter { it.isNotBlank() }.forEach { add("--gradle-arg"); add(it) }
    }

    companion object {
        /** Preserve the old project XML surface exactly while introducing structured settings. */
        fun from(state: HotReloadSettings.State, fallbackProjectDir: String): HotReloadWatchConfig = HotReloadWatchConfig(
            cliLauncherPath = state.cliLauncherPath,
            projectDir = state.projectDir.ifBlank { fallbackProjectDir },
            profile = state.profile,
            appModule = state.appModule,
            appId = state.appId,
            variant = state.variant,
            targetJdk = state.targetJdk,
            watchedModules = state.modules.ifBlank { "app" },
            device = state.device,
            sdkPath = state.sdkPath,
            literals = state.literals,
            zeroTouch = state.zeroTouch,
            gradleArgs = state.gradleArgs.toList(),
            advancedTokens = state.advancedTokens.takeIf { it.isNotEmpty() } ?: legacyTokens(state.extraArgs),
        )

        /** `extraArgs` was the old whitespace-delimited field; migrate it only as legacy input. */
        private fun legacyTokens(value: String): List<String> = value.trim()
            .takeIf { it.isNotEmpty() }
            ?.split(Regex("\\s+"))
            .orEmpty()
    }
}

/** Human-readable preview only; process execution always consumes the original token list. */
fun renderCommand(tokens: List<String>): String = tokens.joinToString(" ") { token ->
    if (token.matches(Regex("[A-Za-z0-9_./:=+@%,-]+"))) token
    else "'" + token.replace("'", "'\\\"'\\\"'") + "'"
}
