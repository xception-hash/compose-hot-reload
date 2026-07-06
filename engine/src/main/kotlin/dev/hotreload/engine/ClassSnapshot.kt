package dev.hotreload.engine

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.extension
import kotlin.io.path.readBytes
import kotlin.streams.toList

/**
 * A single compiled class file in a snapshot, with its content hash, raw `.class` [bytes]
 * and extracted facts. The bytes are kept so a later save can prime this class for the
 * interpreter against its as-of-snapshot (i.e. on-device) baseline — the file on disk is
 * overwritten by the next compile (T27).
 */
class SnapshotEntry(val file: Path, val contentHash: Long, val bytes: ByteArray, val facts: ClassFacts)

/**
 * Scans one or more classes directories (one per module) into a single snapshot
 * keyed by JVM internal name. The diff/classify pipeline is module-agnostic —
 * each entry carries its own file path, so dexing works from any module's output.
 */
object ClassSnapshot {

    fun scan(classesDir: Path): Map<String, SnapshotEntry> = scan(listOf(classesDir))

    fun scan(classesDirs: List<Path>): Map<String, SnapshotEntry> {
        val result = mutableMapOf<String, SnapshotEntry>()
        for (classesDir in classesDirs) {
            if (!Files.exists(classesDir)) continue
            Files.walk(classesDir).use { stream ->
                stream.toList()
                    .filter { Files.isRegularFile(it) && it.extension == "class" }
                    .filter { !it.toString().contains("META-INF") }
                    .forEach { file ->
                        val bytes = file.readBytes()
                        val facts = FactsExtractor.extract(bytes)
                        val contentHash = hashBytes(bytes)
                        val previous = result.put(facts.internalName, SnapshotEntry(file, contentHash, bytes, facts))
                        // Two modules compiling the same class would make the diff ambiguous.
                        check(previous == null || previous.file == file) {
                            "class ${facts.internalName} compiled by two modules: ${previous!!.file} and $file"
                        }
                    }
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
