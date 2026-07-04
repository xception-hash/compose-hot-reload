package dev.hotreload.engine

import io.methvin.watcher.DirectoryWatcher
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.io.path.extension

/**
 * Watches source + resource roots for changes and delivers debounced batches of changed
 * file paths. Kotlin sources (`.kt`) drive class hot-swap; `res/values` XML (`.xml`)
 * drives resource hot-swap (T17). The consumer routes each path by extension/location.
 */
class SourceWatcher(
    roots: List<Path>,
    private val onBatch: (Set<Path>) -> Unit,
) : AutoCloseable {

    private val pending = mutableSetOf<Path>()
    private val lock = ReentrantLock()
    private var debounceTask: ScheduledFuture<*>? = null
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "source-watcher-debounce").apply { isDaemon = true }
    }

    private val watcher: DirectoryWatcher = DirectoryWatcher.builder()
        .paths(roots)
        .listener { event ->
            val path = event.path()
            if (path.extension == "kt" || path.extension == "xml") {
                lock.withLock {
                    pending.add(path)
                    debounceTask?.cancel(false)
                    debounceTask = scheduler.schedule(::flush, DEBOUNCE_MS, TimeUnit.MILLISECONDS)
                }
            }
        }
        .build()

    /**
     * Starts watching in a non-blocking daemon thread.
     */
    fun start() {
        watcher.watchAsync().whenComplete { _, error ->
            // The future is otherwise unobserved — a registration/IO failure here would
            // silently stop all file events while the session looks healthy.
            if (error != null) {
                System.err.println("source watcher died: $error")
                error.printStackTrace()
            }
        }
    }

    private fun flush() {
        val batch: Set<Path>
        lock.withLock {
            batch = pending.toSet()
            pending.clear()
        }
        if (batch.isNotEmpty()) {
            try {
                onBatch(batch)
            } catch (t: Throwable) {
                // The scheduler captures exceptions in an unobserved ScheduledFuture —
                // without this the save is dropped and the session looks healthy.
                System.err.println("save processing failed (session continues):")
                t.printStackTrace()
            }
        }
    }

    override fun close() {
        watcher.close()
        scheduler.shutdownNow()
    }

    private companion object {
        const val DEBOUNCE_MS = 150L
    }
}
