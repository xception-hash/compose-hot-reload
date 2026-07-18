package dev.hotreload.idea

/**
 * Widget states, driven purely by parsing the `hotreload watch` CLI's stdout/stderr.
 * The plugin never embeds the engine (T26 decision) — the CLI's log lines are the stable,
 * machine-readable surface we own, so this file is the entire coupling to the engine.
 */
enum class HotReloadState { OFF, STARTING, READY, RELOADING, ERROR, REBUILD_NEEDED }

/**
 * A snapshot of what the widget should show. [detail] is the tooltip text (the swap latency,
 * the reason a rebuild is needed, or the first compile/recompose error line). [firstErrorLine]
 * is internal batch bookkeeping so the tooltip on an [ERROR] is the *first* `error:` line the
 * failing compile printed, not the terminal "compile failed" banner.
 */
data class HotReloadStatus(
    val state: HotReloadState = HotReloadState.OFF,
    val errorCount: Int = 0,
    val lastLatencyMs: Long? = null,
    val detail: String? = null,
    val firstErrorLine: String? = null,
) {
    companion object {
        val OFF = HotReloadStatus()
        val STARTING = HotReloadStatus(state = HotReloadState.STARTING, detail = "starting hotreload watch…")
    }
}

/**
 * Stateless line classifier + state machine. Prefixes are copied verbatim from the engine's
 * logging — keep them in sync with:
 *   engine/src/main/kotlin/dev/hotreload/engine/WatchSession.kt
 *   engine/src/main/kotlin/dev/hotreload/engine/ResourceSwapper.kt
 * If a needed line is missing or ambiguous, the fix is here or in a review note — never by
 * changing the engine's output (T26 out-of-scope).
 */
object CliProtocol {
    // --- exact engine line prefixes (see the files named above) ---
    private const val READY_BANNER = "watching "               // "watching <roots> (N classes)"
    private const val SAVE_STARTED = "changed: "               // WatchSession.onSave (printed after a leading \n)
    private const val SWAPPED = "hot-swapped: "                // "... in Nms"
    private const val INTERPRETED = "interpreted: "            // "... (Nms)"
    private const val RESOURCE_SWAPPED = "resource-swapped: "  // "... in Nms"
    private const val LITERAL_PUSHED = "literal-pushed: "      // "... in Nms"
    private const val NO_CHANGES = "no bytecode changes"       // "no bytecode changes (Nms)"

    private const val CANNOT_HOTSWAP = "cannot hot-swap: "     // reason follows
    private const val RES_SET_CHANGED = "resource set changed" // value-only overlay can't remap ids
    private const val RUN_FULL_INSTALL = "run a full install"  // the reinstall instruction (sticky)

    private const val COMPILE_FAILED = "compile failed — fix and save again"
    private const val RESOURCE_BUILD_FAILED = "resource build failed — fix and save again"
    private const val RECOMPOSE_FAILED = "recomposition failed: "
    private const val NO_APK = "no debug APK under"
    private const val FATAL = "hotreload: "                    // Main.fail(...) on stderr, then the process exits

    // live-literals best-effort path falls back to a full swap — NOT an error state.
    private const val LITERAL_FALLBACK = "literal fast path failed"

    private val LATENCY_IN = Regex("""in (\d+)ms""")
    private val LATENCY_PAREN = Regex("""\((\d+)ms\)""")

    /** True for a raw kotlinc/AGP diagnostic line (they precede the terminal "compile failed"). */
    private fun isRawError(line: String): Boolean =
        line.startsWith("e: ") || line.contains("error:")

    private fun latency(line: String): Long? =
        (LATENCY_IN.find(line) ?: LATENCY_PAREN.find(line))?.groupValues?.get(1)?.toLongOrNull()

    /**
     * Fold one CLI line into the running status. Lines are processed in emission order, so the
     * last meaningful line of a save batch wins (Reloading → Ready / Error / Rebuild-needed).
     */
    fun advance(prev: HotReloadStatus, rawLine: String): HotReloadStatus {
        val line = rawLine.trim()
        if (line.isEmpty()) return prev

        return when {
            // --- session ready (the one-time startup banner) ---
            line.startsWith(READY_BANNER) ->
                prev.copy(state = HotReloadState.READY, errorCount = 0, detail = "ready", firstErrorLine = null)

            // --- a save started: begin a fresh batch ---
            line.startsWith(SAVE_STARTED) ->
                prev.copy(state = HotReloadState.RELOADING, detail = line, firstErrorLine = null)

            // --- successful swap: back to Ready, clear the error tally, flash the latency ---
            line.startsWith(SWAPPED) || line.startsWith(INTERPRETED) ||
                line.startsWith(RESOURCE_SWAPPED) || line.startsWith(LITERAL_PUSHED) ->
                prev.copy(
                    state = HotReloadState.READY,
                    errorCount = 0,
                    lastLatencyMs = latency(line),
                    detail = latency(line)?.let { "reloaded in ${it}ms" } ?: "reloaded",
                    firstErrorLine = null,
                )

            line.startsWith(NO_CHANGES) ->
                prev.copy(state = HotReloadState.READY, errorCount = 0, detail = "no bytecode changes", firstErrorLine = null)

            // --- rebuild-needed (sticky until the next successful swap) ---
            line.startsWith(CANNOT_HOTSWAP) ->
                prev.copy(state = HotReloadState.REBUILD_NEEDED, detail = line.removePrefix(CANNOT_HOTSWAP))
            line.startsWith(RES_SET_CHANGED) ->
                prev.copy(state = HotReloadState.REBUILD_NEEDED, detail = line)
            line.startsWith(RUN_FULL_INSTALL) ->
                // Reinforces a rebuild; only meaningful right after one of the two lines above.
                if (prev.state == HotReloadState.REBUILD_NEEDED) prev
                else prev.copy(state = HotReloadState.REBUILD_NEEDED, detail = "rebuild required — run a full install")

            // --- errors ---
            line.startsWith(LITERAL_FALLBACK) -> prev // benign: falls back to the full swap
            line.startsWith(RECOMPOSE_FAILED) ->
                enterError(prev, line.removePrefix(RECOMPOSE_FAILED))
            line == COMPILE_FAILED || line == RESOURCE_BUILD_FAILED ->
                enterError(prev, prev.firstErrorLine ?: line)
            line.startsWith(NO_APK) ->
                enterError(prev, line)
            line.startsWith(FATAL) ->
                enterError(prev, line.removePrefix(FATAL))

            // raw compiler diagnostics: remember the first one this batch (tooltip source),
            // but stay in Reloading — the terminal "compile failed" line commits the Error.
            isRawError(line) ->
                if (prev.firstErrorLine == null) prev.copy(firstErrorLine = line) else prev

            else -> prev
        }
    }

    /** Enter (or stay in) Error, incrementing the tally once per failed batch. */
    private fun enterError(prev: HotReloadStatus, message: String): HotReloadStatus {
        val alreadyErrored = prev.state == HotReloadState.ERROR
        return prev.copy(
            state = HotReloadState.ERROR,
            errorCount = if (alreadyErrored) prev.errorCount else prev.errorCount + 1,
            detail = message.ifBlank { "reload failed" },
            firstErrorLine = null,
        )
    }
}
