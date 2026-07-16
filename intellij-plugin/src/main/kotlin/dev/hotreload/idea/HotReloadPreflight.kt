package dev.hotreload.idea

/** Parsed result of a `hotreload doctor` run used by the pre-Start preflight. */
data class PreflightResult(
    val ok: Boolean,
    val failures: List<String>,
    val warnings: List<String>,
)

/**
 * Pure parser for `hotreload doctor` output. Doctor prints `[OK]`, `[WARN]`, `[FAIL]` lines and
 * exits 0 iff no `[FAIL]` occurred; `ok` is driven by the exit code, the line lists give the
 * preflight notification something actionable to show.
 */
object HotReloadPreflight {
    fun parse(exitCode: Int, output: String): PreflightResult {
        fun collect(tag: String) = output.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("[$tag]") }
            .map { it.removePrefix("[$tag]").trim() }
            .toList()
        return PreflightResult(ok = exitCode == 0, failures = collect("FAIL"), warnings = collect("WARN"))
    }
}
