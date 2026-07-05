package dev.hotreload.engine

import org.gradle.tooling.GradleConnector
import org.gradle.tooling.ProjectConnection
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Result of a Gradle compilation.
 */
class CompileResult(val success: Boolean, val durationMs: Long, val output: String)

/**
 * Wrapper over the Gradle Tooling API that keeps a warm daemon connection
 * for fast incremental compiles.
 *
 * [extraArgs] are appended to every build (e.g. `-Photreload.liveLiterals=true` for the
 * T24 fast path): the class output the watch compiles must match how the installed APK was
 * built, or the generated `LiveLiterals$*Kt` helpers would be absent/mismatched.
 */
class GradleCompiler(projectDir: File, private val extraArgs: List<String> = emptyList()) : AutoCloseable {

    private val connection: ProjectConnection = GradleConnector.newConnector()
        .forProjectDirectory(projectDir)
        .connect()

    /**
     * Runs a Gradle task (e.g. `:app:compileDebugKotlin`).
     * Returns [CompileResult] — a failed build returns `success=false` with output,
     * it does NOT throw.
     */
    fun compile(task: String): CompileResult {
        val out = ByteArrayOutputStream()
        val start = System.currentTimeMillis()
        return try {
            connection.newBuild()
                .forTasks(task)
                .apply { if (extraArgs.isNotEmpty()) withArguments(extraArgs) }
                .setStandardOutput(out)
                .setStandardError(out)
                .run()
            val duration = System.currentTimeMillis() - start
            CompileResult(true, duration, out.toString(Charsets.UTF_8))
        } catch (_: Exception) {
            val duration = System.currentTimeMillis() - start
            CompileResult(false, duration, out.toString(Charsets.UTF_8))
        }
    }

    override fun close() {
        connection.close()
    }
}
