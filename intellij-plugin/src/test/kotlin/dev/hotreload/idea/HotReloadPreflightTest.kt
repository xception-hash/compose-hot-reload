package dev.hotreload.idea

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HotReloadPreflightTest {
    @Test fun `all checks passing parses warnings without failures`() {
        val result = HotReloadPreflight.parse(0, "[OK] a\n[WARN] b")
        assertTrue(result.ok)
        assertEquals(listOf("b"), result.warnings)
        assertTrue(result.failures.isEmpty())
    }

    @Test fun `failing checks collect stripped failure messages`() {
        val result = HotReloadPreflight.parse(1, "[OK] a\n[FAIL] no device (fix: ...)\n[FAIL] app not installed")
        assertTrue(!result.ok)
        assertEquals(listOf("no device (fix: ...)", "app not installed"), result.failures)
    }

    @Test fun `ok is driven by exit code, not by the presence of FAIL text`() {
        // A nonzero exit with no [FAIL] lines (e.g. doctor itself crashed) is still not ok.
        val crashed = HotReloadPreflight.parse(1, "[OK] a\n[WARN] b")
        assertTrue(!crashed.ok)
        assertTrue(crashed.failures.isEmpty())

        // A zero exit is ok even if the output happens to contain the literal text "[FAIL]"
        // inside another line (parse only matches lines that start with the tag).
        val zeroExit = HotReloadPreflight.parse(0, "note: earlier this said [FAIL] but was fixed")
        assertTrue(zeroExit.ok)
        assertTrue(zeroExit.failures.isEmpty())
    }

    @Test fun `parse retains raw output and exit code`() {
        val result = HotReloadPreflight.parse(1, "hotreload: --sdk not given and ANDROID_HOME not set")
        assertEquals(1, result.exitCode)
        assertEquals("hotreload: --sdk not given and ANDROID_HOME not set", result.rawOutput)
    }

    @Test fun `notice for a fatal abort with no FAIL lines shows the raw output, not an empty bullet list`() {
        val fatal = "hotreload: --sdk not given and ANDROID_HOME not set"
        val pf = HotReloadPreflight.parse(1, fatal)
        val notice = HotReloadPreflight.notice(pf)

        assertEquals("Hot reload preflight could not run", notice.title)
        assertTrue(notice.body.contains(fatal))
        assertTrue(!notice.body.contains("•"))
        assertTrue(notice.reportLines.contains(fatal))
    }

    @Test fun `notice for FAIL lines still returns the found-problems title and bullet body`() {
        val pf = HotReloadPreflight.parse(1, "[OK] a\n[FAIL] no device (fix: ...)\n[FAIL] app not installed")
        val notice = HotReloadPreflight.notice(pf)

        assertEquals("Hot reload preflight found problems", notice.title)
        assertTrue(notice.body.contains("•"))
        assertTrue(notice.body.contains("no device (fix: ...)"))
    }
}
