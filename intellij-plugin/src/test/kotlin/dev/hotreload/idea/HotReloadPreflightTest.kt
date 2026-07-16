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
}
