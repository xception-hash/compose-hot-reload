package dev.hotreload.idea

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
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

    /** Accumulates output between newlines — onTextAvailable may hand us partial lines. */
    private val lineBuffer = StringBuilder()

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
        val settings = HotReloadSettings.getInstance(project).state
        val command = try {
            buildCommand(settings)
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
                val ended = if (event.exitCode == 0) HotReloadStatus.OFF
                else status.copy(state = HotReloadState.ERROR, detail = "watch exited (code ${event.exitCode})")
                handler = null
                lineBuffer.setLength(0)
                setStatus(ended)
            }
        })
        handler = process
        process.startNotify()
        LOG.info("started: ${command.commandLineString}")
    }

    @Synchronized
    fun stop() {
        handler?.destroyProcess() // triggers processTerminated → status reset
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
        val before = status
        val after = CliProtocol.advance(before, line)
        if (after == before) return
        setStatus(after)
        if (after.state != before.state) onStateEntered(after)
    }

    private fun onStateEntered(s: HotReloadStatus) {
        when (s.state) {
            HotReloadState.ERROR ->
                balloon("Hot reload failed", s.detail ?: "see the CLI output", NotificationType.ERROR)
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

    private fun buildCommand(s: HotReloadSettings.State): GeneralCommandLine {
        val launcher = s.cliLauncherPath.trim()
        if (launcher.isEmpty()) {
            throw ConfigError("CLI launcher path is not set — run `./gradlew :cli:installDist` and point Settings › Compose Hot Reload at …/cli/build/install/cli/bin/cli")
        }
        if (!Files.isRegularFile(Path.of(launcher))) {
            throw ConfigError("CLI launcher not found: $launcher")
        }
        val projectDir = s.projectDir.ifBlank { project.basePath ?: "" }
        if (projectDir.isBlank()) throw ConfigError("project dir is not set and the IDE project has no base path")
        if (s.appId.isBlank()) throw ConfigError("application id is not set")

        val cmd = GeneralCommandLine(launcher).apply {
            withWorkDirectory(projectDir)
            addParameters("watch", "--project", projectDir, "--app-id", s.appId.trim())
            val modules = s.modules.trim().ifBlank { "app" }
            addParameters("--module", modules)
            if (s.sdkPath.isNotBlank()) addParameters("--sdk", s.sdkPath.trim())
            s.extraArgs.trim().takeIf { it.isNotEmpty() }
                ?.split(Regex("\\s+"))?.let { addParameters(it) }
        }
        return cmd
    }

    private fun balloon(title: String, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(title, content, type)
            .notify(project)
    }

    override fun dispose() {
        stop()
    }

    companion object {
        const val NOTIFICATION_GROUP = "Compose Hot Reload"
        private val LOG = logger<HotReloadService>()
        fun getInstance(project: Project): HotReloadService = project.service()
    }
}
