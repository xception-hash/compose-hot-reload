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
 * [javaHome] lets the CLI run on its pinned JBR while an older target build stays on its
 * required JDK. JAVA_HOME is aligned with the selected Tooling API daemon for build checks.
 */
class GradleCompiler(
    projectDir: File,
    private val extraArgs: List<String> = emptyList(),
    private val javaHome: File? = null,
) : AutoCloseable {

    private val connection: ProjectConnection = GradleConnector.newConnector()
        .forProjectDirectory(projectDir)
        .connect()

    /**
     * Runs one or more Gradle tasks (e.g. `:app:compileDebugKotlin`). A batch stays in a
     * single build invocation so Gradle retains its declared task dependency ordering.
     * Returns [CompileResult] — a failed build returns `success=false` with output,
     * it does NOT throw.
     */
    fun compile(vararg tasks: String): CompileResult {
        require(tasks.isNotEmpty()) { "at least one Gradle task is required" }
        val out = ByteArrayOutputStream()
        val start = System.currentTimeMillis()
        return try {
            connection.newBuild()
                .forTasks(*tasks)
                .apply { if (extraArgs.isNotEmpty()) withArguments(extraArgs) }
                .apply {
                    if (javaHome != null) {
                        setJavaHome(javaHome)
                        setEnvironmentVariables(System.getenv() + ("JAVA_HOME" to javaHome.absolutePath))
                    }
                }
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
