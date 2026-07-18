package dev.hotreload.idea

import java.io.InputStream
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * Collects a child process's output without allowing either OS pipe to block the other.
 *
 * Callers deliberately receive stdout and stderr independently: `hotreload inspect --json`
 * writes machine-readable JSON to stdout while Gradle diagnostics belong on stderr.
 */
data class CollectedProcessOutput(
    val exitCode: Int,
    val stdout: ByteArray,
    val stderr: ByteArray,
)

internal object ProcessOutputCollector {
    fun collect(process: Process): CollectedProcessOutput {
        val executor = Executors.newFixedThreadPool(2, ReaderThreadFactory())
        val stdout = executor.submit<ByteArray> { readStream(process, process.inputStream) }
        val stderr = executor.submit<ByteArray> { readStream(process, process.errorStream) }
        try {
            val exitCode = process.waitFor()
            return CollectedProcessOutput(exitCode, await(stdout, process), await(stderr, process))
        } catch (error: InterruptedException) {
            cancelAndTerminate(process, stdout, stderr)
            Thread.currentThread().interrupt()
            throw IllegalStateException("interrupted while collecting child process output", error)
        } finally {
            executor.shutdownNow()
            if (process.isAlive) process.destroyForcibly()
        }
    }

    private fun readStream(process: Process, stream: InputStream): ByteArray = try {
        stream.use { it.readBytes() }
    } catch (error: Throwable) {
        if (process.isAlive) process.destroyForcibly()
        throw error
    }

    private fun await(reader: Future<ByteArray>, process: Process): ByteArray = try {
        reader.get()
    } catch (error: ExecutionException) {
        if (process.isAlive) process.destroyForcibly()
        throw IllegalStateException("failed to read child process output", error.cause ?: error)
    }

    private fun cancelAndTerminate(process: Process, vararg readers: Future<ByteArray>) {
        readers.forEach { it.cancel(true) }
        if (process.isAlive) process.destroyForcibly()
    }

    private class ReaderThreadFactory : ThreadFactory {
        private val number = AtomicInteger()

        override fun newThread(runnable: Runnable): Thread =
            Thread(runnable, "hotreload-process-output-${number.incrementAndGet()}").apply { isDaemon = true }
    }
}
