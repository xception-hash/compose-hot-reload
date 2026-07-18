package dev.hotreload.idea

import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.exists
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertTimeoutPreemptively

class ProcessOutputCollectorTest {
    @Test
    fun `drains a full stderr pipe before later stdout`() {
        withChild("large-stderr") { process ->
            val output = assertTimeoutPreemptively(Duration.ofSeconds(15)) {
                ProcessOutputCollector.collect(process)
            }
            assertEquals(0, output.exitCode)
            assertEquals("stdout-after-stderr", output.stdout.toString(StandardCharsets.UTF_8))
            assertArrayEquals(ByteArray(2 * 1024 * 1024) { 'e'.code.toByte() }, output.stderr)
        }
    }

    @Test
    fun `preserves distinct streams for a nonzero exit`() {
        withChild("nonzero") { process ->
            val output = ProcessOutputCollector.collect(process)
            assertEquals(23, output.exitCode)
            assertEquals("standard-output", output.stdout.toString(StandardCharsets.UTF_8))
            assertEquals("standard-error", output.stderr.toString(StandardCharsets.UTF_8))
        }
    }

    private fun withChild(mode: String, assertion: (Process) -> Unit) {
        val process = ProcessBuilder(javaExecutable().toString(), "-cp", System.getProperty("java.class.path"), Child::class.java.name, mode)
            .start()
        try {
            assertion(process)
        } finally {
            if (process.isAlive) process.destroyForcibly()
            process.waitFor()
        }
    }

    private fun javaExecutable(): Path {
        val bin = Path.of(System.getProperty("java.home"), "bin")
        val executable = bin.resolve(if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java")
        check(executable.exists()) { "test JDK executable not found: $executable" }
        return executable
    }

    object Child {
        @JvmStatic
        fun main(args: Array<String>) {
            when (args.single()) {
                "large-stderr" -> {
                    System.err.write(ByteArray(2 * 1024 * 1024) { 'e'.code.toByte() })
                    System.err.flush()
                    print("stdout-after-stderr")
                }
                "nonzero" -> {
                    print("standard-output")
                    System.err.print("standard-error")
                    System.exit(23)
                }
                else -> error("unknown child mode")
            }
        }
    }
}
