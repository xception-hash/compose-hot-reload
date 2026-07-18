package dev.hotreload.idea

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CliProtocolTest {

    /** Fold every non-blank line of a fixture into a status, returning the final one. */
    private fun replay(fixture: String, from: HotReloadStatus = HotReloadStatus.STARTING): HotReloadStatus {
        val text = checkNotNull(javaClass.getResourceAsStream("/fixtures/$fixture")) { "missing fixture $fixture" }
            .bufferedReader().readText()
        return text.lineSequence().fold(from) { acc, line -> CliProtocol.advance(acc, line) }
    }

    private fun advanceAll(vararg lines: String, from: HotReloadStatus = HotReloadStatus.STARTING): HotReloadStatus =
        lines.fold(from) { acc, line -> CliProtocol.advance(acc, line) }

    // ---- fixture replays (whole transcripts copied from real watch logs) ----

    @Test fun `successful session ends Ready with latency`() {
        val s = replay("session-success.txt")
        assertEquals(HotReloadState.READY, s.state)
        assertEquals(87L, s.lastLatencyMs)
        assertEquals(0, s.errorCount)
    }

    @Test fun `signature change ends Rebuild-needed with reason`() {
        val s = replay("rebuild-needed.txt")
        assertEquals(HotReloadState.REBUILD_NEEDED, s.state)
        assertTrue(s.detail!!.contains("signature changed"), "tooltip should carry the reason: ${s.detail}")
    }

    @Test fun `compile error ends Error with first diagnostic as tooltip`() {
        val s = replay("compile-error.txt")
        assertEquals(HotReloadState.ERROR, s.state)
        assertEquals(1, s.errorCount)
        assertTrue(s.detail!!.contains("unresolved reference: notAThing"), "tooltip = first error line: ${s.detail}")
    }

    @Test fun `recomposition failure after a swap ends Error`() {
        val s = replay("recompose-error.txt")
        assertEquals(HotReloadState.ERROR, s.state)
        assertEquals(1, s.errorCount)
        assertTrue(s.detail!!.contains("count must be non-negative"))
    }

    @Test fun `literal, resource and no-change lines all resolve to Ready`() {
        val s = replay("resource-and-literal.txt")
        assertEquals(HotReloadState.READY, s.state)
        // last swap line of the transcript is "no bytecode changes (12ms)"
        assertEquals("no bytecode changes", s.detail)
    }

    @Test fun `fatal startup line ends Error`() {
        val s = replay("fatal-startup.txt")
        assertEquals(HotReloadState.ERROR, s.state)
        assertTrue(s.detail!!.contains("protocol version mismatch"))
    }

    // ---- targeted transition tests (each state entered explicitly) ----

    @Test fun `watching banner is Ready`() {
        val s = CliProtocol.advance(HotReloadStatus.STARTING, "watching /a/b (10 classes)")
        assertEquals(HotReloadState.READY, s.state)
    }

    @Test fun `changed line is Reloading`() {
        val s = CliProtocol.advance(ready(), "changed: Counter.kt")
        assertEquals(HotReloadState.RELOADING, s.state)
    }

    @Test fun `hot-swapped flashes latency and clears prior errors`() {
        val errored = HotReloadStatus(state = HotReloadState.ERROR, errorCount = 3)
        val s = advanceAll("changed: Counter.kt", "hot-swapped: 1 redefined in 74ms", from = errored)
        assertEquals(HotReloadState.READY, s.state)
        assertEquals(74L, s.lastLatencyMs)
        assertEquals(0, s.errorCount)
    }

    @Test fun `interpreted completion is Ready with latency`() {
        val s = advanceAll(
            "changed: ForYouScreen.kt",
            "interpreted: 1 changed class (91ms)",
            from = ready(),
        )
        assertEquals(HotReloadState.READY, s.state)
        assertEquals(91L, s.lastLatencyMs)
    }

    @Test fun `resource-swapped is Ready with latency`() {
        val s = CliProtocol.advance(ready(), "resource-swapped: hotreload-overlay-x-1 in 640ms")
        assertEquals(HotReloadState.READY, s.state)
        assertEquals(640L, s.lastLatencyMs)
    }

    @Test fun `literal-pushed is Ready with latency`() {
        val s = CliProtocol.advance(ready(), "literal-pushed: Int\$fun-Counter = 42 in 22ms")
        assertEquals(HotReloadState.READY, s.state)
        assertEquals(22L, s.lastLatencyMs)
    }

    @Test fun `cannot hot-swap is Rebuild-needed`() {
        val s = CliProtocol.advance(ready(), "cannot hot-swap: member removed: bar")
        assertEquals(HotReloadState.REBUILD_NEEDED, s.state)
        assertEquals("member removed: bar", s.detail)
    }

    @Test fun `resource set changed is Rebuild-needed`() {
        val s = CliProtocol.advance(ready(), "resource set changed (added new_id) — value-only hot reload can't remap resource IDs")
        assertEquals(HotReloadState.REBUILD_NEEDED, s.state)
    }

    @Test fun `run a full install alone still yields Rebuild-needed`() {
        val s = CliProtocol.advance(ready(), "run a full install (e.g. ./gradlew :app:installDebug), relaunch, then restart watch")
        assertEquals(HotReloadState.REBUILD_NEEDED, s.state)
    }

    @Test fun `device rejection leaves Reloading for actionable Rebuild-needed`() {
        val s = advanceAll(
            "changed: Counter.kt",
            "cannot hot-swap: device error 67: redefine rejected",
            "run a full install (e.g. ./gradlew :app:installDebug), relaunch, then restart watch",
            from = ready(),
        )
        assertEquals(HotReloadState.REBUILD_NEEDED, s.state)
        assertTrue(s.detail!!.contains("device error 67"))
    }

    @Test fun `literal fallback line is benign`() {
        val before = ready()
        val s = CliProtocol.advance(before, "literal fast path failed (boom) — falling back to full swap")
        assertEquals(before.state, s.state)
    }

    @Test fun `error tally increments once per failed batch, not per diagnostic line`() {
        var s = HotReloadStatus.STARTING.copy(state = HotReloadState.READY)
        // batch 1: two error: lines but one terminal failure → count 1
        s = advanceAll(
            "changed: A.kt",
            "A.kt:1: error: one",
            "A.kt:2: error: two",
            "compile failed — fix and save again",
            from = s,
        )
        assertEquals(1, s.errorCount)
        // batch 2: another failure → count 2
        s = advanceAll("changed: A.kt", "A.kt:1: error: three", "compile failed — fix and save again", from = s)
        assertEquals(2, s.errorCount)
        // a good swap clears the tally
        s = advanceAll("changed: A.kt", "hot-swapped: 1 redefined in 50ms", from = s)
        assertEquals(0, s.errorCount)
    }

    @Test fun `blank and unknown lines are inert`() {
        val before = ready()
        assertEquals(before, CliProtocol.advance(before, ""))
        assertEquals(before, CliProtocol.advance(before, "   "))
        assertEquals(before, CliProtocol.advance(before, "modules: :app: AGP (/x)"))
        assertEquals(before, CliProtocol.advance(before, "device: api=36 protocol=6"))
    }

    @Test fun `firstErrorLine resets at the start of each batch`() {
        val s = advanceAll("changed: A.kt", "A.kt:1: error: stale", "hot-swapped: 1 redefined in 10ms", from = ready())
        assertEquals(HotReloadState.READY, s.state)
        assertNull(s.firstErrorLine)
    }

    private fun ready() = HotReloadStatus(state = HotReloadState.READY)
}
