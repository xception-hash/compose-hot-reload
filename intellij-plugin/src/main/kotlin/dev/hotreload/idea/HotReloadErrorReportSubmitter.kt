package dev.hotreload.idea

import com.intellij.ide.BrowserUtil
import com.intellij.ide.plugins.PluginManager
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.diagnostic.ErrorReportSubmitter
import com.intellij.openapi.diagnostic.IdeaLoggingEvent
import com.intellij.openapi.diagnostic.SubmittedReportInfo
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.Consumer
import java.awt.Component

/**
 * Zero-backend crash reporting: on an IDE-detected plugin exception, prefill a GitHub "new issue"
 * URL from the crash details and open it in the browser for the user to review and submit. No
 * telemetry SDK, no server of ours in the loop — only the public [ErrorReportSubmitter] extension
 * point and [BrowserUtil.browse].
 */
class HotReloadErrorReportSubmitter : ErrorReportSubmitter() {

    /** Keep the top of each stacktrace — the top frames are what matter for diagnosis. */
    private val MAX_STACK_LENGTH = 5000

    override fun getReportActionText(): String = "Report to Compose Hot Reload (GitHub)"

    override fun submit(
        events: Array<out IdeaLoggingEvent>,
        additionalInfo: String?,
        parentComponent: Component,
        consumer: Consumer<in SubmittedReportInfo>,
    ): Boolean {
        return try {
            val body = buildBody(events, additionalInfo)
            val title = buildTitle(events)
            BrowserUtil.browse(GitHubIssueUrl.build(title, body))
            consumer.consume(SubmittedReportInfo(SubmittedReportInfo.SubmissionStatus.NEW_ISSUE))
            true
        } catch (e: Exception) {
            LOG.warn("failed to submit crash report", e)
            consumer.consume(SubmittedReportInfo(SubmittedReportInfo.SubmissionStatus.FAILED))
            false
        }
    }

    private fun buildTitle(events: Array<out IdeaLoggingEvent>): String {
        val first = events.firstOrNull()
        val summary = first?.throwable?.message?.lineSequence()?.firstOrNull()?.takeIf { it.isNotBlank() }
            ?: first?.throwable?.javaClass?.name
            ?: first?.message?.lineSequence()?.firstOrNull()?.takeIf { it.isNotBlank() }
            ?: "unknown error"
        return "[crash] $summary"
    }

    private fun buildBody(events: Array<out IdeaLoggingEvent>, additionalInfo: String?): String {
        val sb = StringBuilder()
        if (!additionalInfo.isNullOrBlank()) {
            sb.append("### User comment\n\n").append(additionalInfo.trim()).append("\n\n")
        }

        sb.append("### Exception\n\n")
        events.forEachIndexed { i, event ->
            if (events.size > 1) sb.append("#### Event ${i + 1}\n\n")
            event.message?.takeIf { it.isNotBlank() }?.let { sb.append(it).append("\n\n") }
            val stack = event.throwableText
            if (!stack.isNullOrBlank()) {
                sb.append("```\n").append(truncateKeepingTop(stack, MAX_STACK_LENGTH)).append("\n```\n\n")
            }
        }

        val pluginVersion = PluginManager.getPluginByClass(javaClass)?.version ?: "unknown"
        val ideBuild = runCatching { ApplicationInfo.getInstance().build.asString() }.getOrDefault("unknown")
        sb.append("### Environment\n\n")
        sb.append("- Plugin version: `$pluginVersion`\n")
        sb.append("- IDE build: `$ideBuild`\n")
        sb.append("- OS: `${SystemInfo.OS_NAME} ${SystemInfo.OS_VERSION}`\n")

        return sb.toString()
    }

    /** Truncate a stacktrace, keeping the TOP (the most informative frames) and marking the cut. */
    private fun truncateKeepingTop(text: String, maxLength: Int): String {
        if (text.length <= maxLength) return text
        return text.take(maxLength) + "\n…[truncated]…"
    }

    companion object {
        private val LOG = logger<HotReloadErrorReportSubmitter>()
    }
}
