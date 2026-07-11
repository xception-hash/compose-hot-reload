package dev.hotreload.idea

import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8

/**
 * Builds "prefilled new issue" GitHub URLs for zero-backend crash reporting (no telemetry SDK,
 * no server — the crash report *is* the GitHub issue draft the user reviews before submitting).
 * Pure Kotlin (no IntelliJ imports) so it's unit-testable without the platform test runtime.
 */
object GitHubIssueUrl {
    const val REPO_URL = "https://github.com/xception-hash/compose-hot-reload"

    /** GitHub balks at extremely long URLs; keep well under typical browser/server limits. */
    private const val MAX_URL_LENGTH = 7000

    private const val TRUNCATION_MARKER = "…[truncated]…"

    /** Hard cap on the title alone, before it's ever combined with a body. */
    private const val MAX_TITLE_LENGTH = 200

    fun build(title: String, body: String): String {
        val safeTitle = title.take(MAX_TITLE_LENGTH)
        var safeBody = body
        var url = urlFor(safeTitle, safeBody)

        // Iteratively drop characters from the TOP of the body (keeping the tail — the most
        // recent stacktrace/log lines matter most) until the encoded URL fits. Each iteration is
        // guaranteed to shrink the raw body by at least `overBy` chars, and the encoded length is
        // always >= the raw length, so this terminates.
        while (url.length > MAX_URL_LENGTH && safeBody.isNotEmpty()) {
            val overBy = url.length - MAX_URL_LENGTH
            val dropCount = (overBy + 256).coerceAtMost(safeBody.length)
            val tail = safeBody.substring(dropCount)
            safeBody = TRUNCATION_MARKER + tail
            url = urlFor(safeTitle, safeBody)
        }
        return url
    }

    private fun urlFor(title: String, body: String): String {
        val encTitle = URLEncoder.encode(title, UTF_8)
        val encBody = URLEncoder.encode(body, UTF_8)
        return "$REPO_URL/issues/new?title=$encTitle&body=$encBody&labels=crash"
    }
}
