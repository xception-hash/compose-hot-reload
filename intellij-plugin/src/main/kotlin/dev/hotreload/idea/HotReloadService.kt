package dev.hotreload.idea

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.wm.WindowManager
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Owns the `hotreload watch` child process for one project and the derived [HotReloadStatus].
 * The plugin does NOT embed the engine (T26): it spawns the CLI and folds its stdout/stderr
 * through [CliProtocol]. Destroying the process ends the session — the CLI's own shutdown
 * tears down the device forward, so no orphaned `dev.hotreload.cli.MainKt` should remain.
 */
@Service(Service.Level.PROJECT)
class HotReloadService(private val project: Project) : Disposable {

    @Volatile
    var status: HotReloadStatus = HotReloadStatus.OFF
        private set

    private val listeners = CopyOnWriteArrayList<Runnable>()
    private var handler: OSProcessHandler? = null

    /** True while a user-initiated stop() is in flight, so processTerminated reports Off,
     *  not Error — destroyProcess() kills via signal, which always yields a non-zero exit code. */
    @Volatile
    private var stopping = false

    /** Accumulates output between newlines — onTextAvailable may hand us partial lines. */
    private val lineBuffer = StringBuilder()

    /** Bounded ring buffer of the last [HISTORY_LIMIT] non-blank CLI output lines, for the
     *  "Report on GitHub" action — gives the reporter recent context without unbounded memory. */
    private val outputHistory = ArrayDeque<String>()

    fun addListener(listener: Runnable) = listeners.add(listener)

    val isRunning: Boolean
        get() = handler?.let { !it.isProcessTerminated } == true

    @Synchronized
    fun toggle() {
        if (isRunning) stop() else start()
    }

    @Synchronized
    fun start() {
        if (isRunning) return
        outputHistory.clear()
        val settings = HotReloadSettings.getInstance(project).state
        val config = HotReloadWatchConfig.from(settings, project.basePath.orEmpty())
        val command = try {
            buildCommand(config)
        } catch (e: ConfigError) {
            setStatus(status.copy(state = HotReloadState.ERROR, detail = e.message))
            balloon("Cannot start hot reload", e.message ?: "invalid configuration", NotificationType.ERROR)
            return
        }

        setStatus(HotReloadStatus.STARTING)
        val process = OSProcessHandler(command)
        process.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                // Both stdout and stderr carry protocol lines (stderr = fatal "hotreload: …").
                consumeOutput(event.text)
            }

            override fun processTerminated(event: ProcessEvent) {
                flushBuffer()
                // A user-initiated stop() destroys the process via signal (non-zero exit), so
                // treat it as a clean Off; only an unexpected exit surfaces as an error.
                val wasError = status.state == HotReloadState.ERROR
                val ended = if (stopping || event.exitCode == 0) HotReloadStatus.OFF
                else status.copy(state = HotReloadState.ERROR, detail = "watch exited (code ${event.exitCode})")
                handler = null
                stopping = false
                lineBuffer.setLength(0)
                setStatus(ended)
                // A fatal CLI line has already ballooned via onStateEntered before the process
                // exits; only balloon here when the exit itself is the first sign of trouble.
                if (ended.state == HotReloadState.ERROR && !wasError) {
                    val detail = ended.detail ?: "watch exited unexpectedly"
                    balloon(
                        "Hot reload watch exited unexpectedly",
                        detail,
                        NotificationType.ERROR,
                        reportAction(detail),
                    )
                }
            }
        })
        handler = process
        process.startNotify()
        LOG.info("started: ${command.commandLineString}")
    }

    @Synchronized
    fun stop() {
        if (handler == null) return
        stopping = true
        handler?.destroyProcess() // triggers processTerminated → status reset to Off
    }

    /** Buffer a chunk of process output and fold every complete (newline-terminated) line. */
    @Synchronized
    private fun consumeOutput(text: String) {
        lineBuffer.append(text)
        var nl = lineBuffer.indexOf("\n")
        while (nl >= 0) {
            val line = lineBuffer.substring(0, nl).trimEnd('\r')
            lineBuffer.delete(0, nl + 1)
            if (line.isNotBlank()) fold(line)
            nl = lineBuffer.indexOf("\n")
        }
    }

    /** Process a trailing partial line (no newline) — e.g. the final line before exit. */
    @Synchronized
    private fun flushBuffer() {
        val rest = lineBuffer.toString().trimEnd('\r', '\n')
        lineBuffer.setLength(0)
        if (rest.isNotBlank()) fold(rest)
    }

    /** Fold one CLI line, then react to state entry (balloons on Error / Rebuild-needed). */
    private fun fold(line: String) {
        appendHistory(line)
        val before = status
        val after = CliProtocol.advance(before, line)
        if (after == before) return
        setStatus(after)
        if (after.state != before.state) onStateEntered(after)
    }

    /** Append a non-blank CLI line to the bounded ring buffer (called under the same
     *  @Synchronized discipline as fold's callers: consumeOutput / flushBuffer). */
    private fun appendHistory(line: String) {
        outputHistory.addLast(line)
        while (outputHistory.size > HISTORY_LIMIT) outputHistory.removeFirst()
    }

    /** Snapshot the ring buffer under the object monitor — safe to call from callbacks
     *  (e.g. processTerminated) that don't otherwise hold it. */
    @Synchronized
    private fun historySnapshot(): List<String> = outputHistory.toList()

    private fun onStateEntered(s: HotReloadStatus) {
        when (s.state) {
            HotReloadState.ERROR -> {
                val detail = s.detail ?: "see the CLI output"
                balloon("Hot reload failed", detail, NotificationType.ERROR, reportAction(detail))
            }
            HotReloadState.REBUILD_NEEDED ->
                balloon(
                    "Rebuild required",
                    (s.detail ?: "this change can't be hot-swapped") +
                        "\nRun a full install (e.g. ./gradlew :app:installDebug), relaunch, then Start again.",
                    NotificationType.WARNING,
                )
            else -> {} // no balloon on Ready/Reloading/Starting/Off
        }
    }

    private fun setStatus(next: HotReloadStatus) {
        status = next
        listeners.forEach { it.run() }
        WindowManager.getInstance().getStatusBar(project)
            ?.updateWidget(HotReloadStatusWidget.ID)
    }

    private class ConfigError(message: String) : Exception(message)

    /** The exact launch preview used by Settings; execution still uses token lists, not this text. */
    fun renderedCommand(config: HotReloadWatchConfig): String = try {
        renderCommand(listOf(resolveLauncher(config).toString()) + config.watchArguments())
    } catch (e: ConfigError) {
        "(CLI unavailable: ${e.message}) ${renderCommand(config.watchArguments())}"
    }

    private fun buildCommand(config: HotReloadWatchConfig): GeneralCommandLine {
        val launcher = resolveLauncher(config)
        val projectDir = config.projectDir
        if (projectDir.isBlank()) throw ConfigError("project dir is not set and the IDE project has no base path")
        if (config.appId.isBlank() && config.profile.isBlank()) {
            throw ConfigError("application id is not set (or select a profile that provides one)")
        }

        val cmd = GeneralCommandLine(launcher.toString()).apply {
            withWorkDirectory(projectDir)
            // The installDist launcher runs `$JAVA_HOME/bin/java`. A GUI-launched IDE usually has no
            // JAVA_HOME in its env (T26 gotcha: the CLI then dies "Unable to locate a Java Runtime"),
            // so point the child at the IDE's own bundled JBR — always present, and a valid JDK for
            // the CLI JVM and the target project's Gradle daemon (JBR matches the pinned toolchain).
            withEnvironment("JAVA_HOME", System.getProperty("java.home"))
            addParameters(config.watchArguments())
        }
        return cmd
    }

    /**
     * Resolve the CLI launcher to spawn. A non-blank Settings path is an explicit override (kept for
     * devs pointing at a local `:cli:installDist`); otherwise use the CLI bundled inside the plugin
     * (T31 Part 2) so a Marketplace user needs no repo clone.
     */
    internal fun resolveLauncher(config: HotReloadWatchConfig): Path {
        val override = config.cliLauncherPath.trim()
        if (override.isNotEmpty()) {
            val p = Path.of(override)
            if (!Files.isRegularFile(p)) throw ConfigError("CLI launcher not found: $override")
            return p
        }
        val bundled = bundledLauncher()
            ?: throw ConfigError(
                "No CLI launcher is set and none is bundled with this plugin build. Set " +
                    "Settings › Tools › Compose Hot Reload › CLI launcher to a " +
                    "…/cli/build/install/cli/bin/cli produced by `./gradlew :cli:installDist`.",
            )
        if (!Files.isRegularFile(bundled)) {
            throw ConfigError("bundled CLI launcher missing at $bundled — the plugin build may not have packaged the CLI")
        }
        // The Unix launcher can lose its executable bit when the plugin zip is unpacked on install;
        // restore it so GeneralCommandLine can exec it directly (no-op on Windows / if already set).
        val file = bundled.toFile()
        if (!SystemInfo.isWindows && !file.canExecute()) file.setExecutable(true)
        return bundled
    }

    /**
     * Path to the CLI launcher packaged inside this plugin's install dir (`<pluginDir>/cli/bin/cli`,
     * `cli.bat` on Windows). Null if this plugin path can't be resolved — an override must then be set.
     */
    private fun bundledLauncher(): Path? {
        // Resolve THIS plugin's own install dir WITHOUT any IntelliJ plugin-descriptor API — every
        // PluginManager descriptor lookup is @ApiStatus.Internal on 2026.2 and the Marketplace
        // compat check rejects them. PluginInfo derives the dir from our jar's on-disk location.
        val pluginPath = PluginInfo.installDir ?: return null
        val binName = if (SystemInfo.isWindows) "cli.bat" else "cli"
        return pluginPath.resolve("cli").resolve("bin").resolve(binName)
    }

    private fun balloon(title: String, content: String, type: NotificationType, action: NotificationAction? = null) {
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(title, content, type)
        if (action != null) notification.addAction(action)
        notification.notify(project)
    }

    /** Build the "Report on GitHub" balloon action: prefills a GitHub issue with the error
     *  detail, the last 30 lines of CLI output, plugin/IDE/OS info, and opens it in the browser. */
    private fun reportAction(errorDetail: String): NotificationAction {
        val history = historySnapshot().takeLast(30)
        return NotificationAction.createSimple("Report on GitHub") {
            BrowserUtil.browse(GitHubIssueUrl.build(reportTitle(errorDetail), reportBody(errorDetail, history)))
        }
    }

    private fun reportTitle(errorDetail: String): String {
        val firstLine = errorDetail.lineSequence().firstOrNull()?.takeIf { it.isNotBlank() }
            ?: "hot reload watch failed"
        return "[crash] $firstLine"
    }

    private fun reportBody(errorDetail: String, history: List<String>): String {
        val pluginVersion = PluginInfo.version
        val ideBuild = runCatching { ApplicationInfo.getInstance().build.asString() }.getOrDefault("unknown")
        return buildString {
            append("### Error\n\n").append(errorDetail).append("\n\n")
            append("### Last CLI output (up to 30 lines)\n\n")
            append("```\n").append(history.joinToString("\n")).append("\n```\n\n")
            append("### Environment\n\n")
            append("- Plugin version: `$pluginVersion`\n")
            append("- IDE build: `$ideBuild`\n")
            append("- OS: `${SystemInfo.OS_NAME} ${SystemInfo.OS_VERSION}`\n\n")
            append("Please run `hotreload doctor` and paste its output here.\n")
        }
    }

    override fun dispose() {
        stop()
    }

    companion object {
        const val NOTIFICATION_GROUP = "Compose Hot Reload"
        private const val HISTORY_LIMIT = 100
        private val LOG = logger<HotReloadService>()
        fun getInstance(project: Project): HotReloadService = project.service()
    }
}
