package dev.hotreload.idea

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URLDecoder
import java.nio.charset.StandardCharsets.UTF_8

class GitHubIssueUrlTest {

    @Test fun `basic URL shape`() {
        val url = GitHubIssueUrl.build("a crash", "some body text")
        assertTrue(
            url.startsWith("${GitHubIssueUrl.REPO_URL}/issues/new?"),
            "should start with the repo issues/new endpoint: $url",
        )
        assertTrue(url.contains("labels=crash"), "should carry the crash label: $url")
        assertTrue(url.contains("title=a+crash"), "title should be encoded in the query: $url")
    }

    @Test fun `encodes special characters`() {
        val url = GitHubIssueUrl.build(
            "title with spaces",
            "line one\nline two & more # stuff ünïcödé 日本語",
        )
        // Extract the body query param and decode it back to verify round-trip fidelity.
        val bodyParam = Regex("""body=([^&]*)&""").find(url)?.groupValues?.get(1)
            ?: error("no body param found in $url")
        val decoded = URLDecoder.decode(bodyParam, UTF_8)
        assertEquals("line one\nline two & more # stuff ünïcödé 日本語", decoded)

        // Raw '&' and '#' must not appear unencoded in the query string (they'd corrupt it).
        val query = url.substringAfter('?')
        assertTrue(!query.contains("more & "), "raw & must be encoded: $url")
        assertTrue(!query.contains("#"), "raw # must be encoded: $url")

        // Spaces encode as '+' (form-style), which GitHub's query parser accepts.
        assertTrue(url.contains("title=title+with+spaces"), "spaces should encode as +: $url")
    }

    @Test fun `small input is not truncated`() {
        val body = "short body"
        val url = GitHubIssueUrl.build("short title", body)
        assertTrue(!url.contains("truncated"), "small body should not be truncated: $url")
        val bodyParam = Regex("""body=([^&]*)&""").find(url)?.groupValues?.get(1)
            ?: error("no body param found in $url")
        assertEquals(body, URLDecoder.decode(bodyParam, UTF_8))
    }

    @Test fun `huge body is truncated from the top, keeping the tail, and result fits the cap`() {
        val tail = "TAIL-MARKER-END-OF-BODY"
        val huge = "x".repeat(50_000) + tail
        val url = GitHubIssueUrl.build("crash title", huge)

        assertTrue(url.length <= 7000, "url must be capped at ~7000 chars, was ${url.length}")

        val bodyParam = Regex("""body=([^&]*)&""").find(url)?.groupValues?.get(1)
            ?: error("no body param found in $url")
        val decodedBody = URLDecoder.decode(bodyParam, UTF_8)

        assertTrue(decodedBody.endsWith(tail), "truncated body should end with the original tail: ${decodedBody.takeLast(80)}")
        assertTrue(decodedBody.contains("truncated"), "truncated body should carry a truncation marker")
        assertTrue(decodedBody.length < huge.length, "truncated body should be shorter than the original")
    }

    @Test fun `absurdly long title is hard-truncated and the url still fits`() {
        val hugeTitle = "T".repeat(10_000)
        val url = GitHubIssueUrl.build(hugeTitle, "body")
        assertTrue(url.length <= 7000, "url must be capped at ~7000 chars, was ${url.length}")
    }
}
