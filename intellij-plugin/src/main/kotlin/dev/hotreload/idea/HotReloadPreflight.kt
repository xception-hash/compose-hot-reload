package dev.hotreload.idea

/** Parsed result of a `hotreload doctor` run used by the pre-Start preflight. */
data class PreflightResult(
    val ok: Boolean,
    val failures: List<String>,
    val warnings: List<String>,
    val rawOutput: String,
    val exitCode: Int,
)

/** Pure presentation model for the preflight balloon — the service stays a thin renderer. */
data class PreflightNotice(
    val title: String,
    val body: String,
    val reportDetail: String,
    val reportLines: List<String>,
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
        return PreflightResult(
            ok = exitCode == 0,
            failures = collect("FAIL"),
            warnings = collect("WARN"),
            rawOutput = output,
            exitCode = exitCode,
        )
    }

    /** Render a [PreflightResult] into balloon-ready text. When doctor produced no `[FAIL]`
     *  lines but still exited non-zero (a fatal abort before any check ran), fall back to the
     *  raw output so the user isn't left with an empty bullet list. */
    fun notice(pf: PreflightResult): PreflightNotice {
        val hasFailures = pf.failures.isNotEmpty()
        val title = if (hasFailures) "Hot reload preflight found problems" else "Hot reload preflight could not run"
        val rawLines = pf.rawOutput.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList()
        val body = if (hasFailures) {
            buildString {
                append("The hot reload environment check found problems:\n")
                pf.failures.forEach { append("• ").append(it).append('\n') }
                append("\nFix these, or click \"Start anyway\" to launch regardless.")
            }
        } else {
            buildString {
                append("The environment check could not complete (exit ${pf.exitCode}):\n")
                val detailLines = if (rawLines.isEmpty()) listOf("(no output)") else rawLines.takeLast(8)
                detailLines.forEach { append(it).append('\n') }
                append("\nFix this, or click \"Start anyway\" to launch regardless.")
            }
        }
        return PreflightNotice(
            title = title,
            body = body,
            reportDetail = "Preflight failed (exit ${pf.exitCode})",
            reportLines = rawLines.takeLast(30),
        )
    }
}
