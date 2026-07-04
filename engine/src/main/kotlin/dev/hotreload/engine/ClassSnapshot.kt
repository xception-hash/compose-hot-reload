package dev.hotreload.engine

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.extension
import kotlin.io.path.readBytes
import kotlin.streams.toList

/**
 * A single compiled class file in a snapshot, with its content hash and extracted facts.
 */
class SnapshotEntry(val file: Path, val contentHash: Long, val facts: ClassFacts)

/**
 * Scans a classes directory and produces a snapshot keyed by JVM internal name.
 */
object ClassSnapshot {

    fun scan(classesDir: Path): Map<String, SnapshotEntry> {
        if (!Files.exists(classesDir)) return emptyMap()

        val result = mutableMapOf<String, SnapshotEntry>()
        Files.walk(classesDir).use { stream ->
            stream.toList()
                .filter { Files.isRegularFile(it) && it.extension == "class" }
                .filter { !it.toString().contains("META-INF") }
                .forEach { file ->
                    val bytes = file.readBytes()
                    val facts = FactsExtractor.extract(bytes)
                    val contentHash = hashBytes(bytes)
                    result[facts.internalName] = SnapshotEntry(file, contentHash, facts)
                }
        }
        return result
    }

    private fun hashBytes(bytes: ByteArray): Long {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(bytes)
        var result = 0L
        for (i in 0 until 8) {
            result = (result shl 8) or (hash[i].toLong() and 0xFF)
        }
        return result
    }
}
