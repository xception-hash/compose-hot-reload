package dev.hotreload.engine

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes

/**
 * Compiles a single `.class` file to a `.dex` using the external `d8` tool.
 */
class DexCompiler(private val d8: Path, private val minApi: Int = 30) {

    /**
     * One class per dex: returns the `classes.dex` bytes for the single input class.
     * Non-zero exit → [IllegalStateException] with d8's stderr.
     */
    /**
     * Dex a single class from in-memory `.class` [classBytes] (e.g. StubTransform output that
     * never lands on disk). d8 reads the class name from the bytecode, so the temp file name is
     * irrelevant. Delegates to [dexOne].
     */
    fun dexBytes(classBytes: ByteArray): ByteArray {
        val tmp = Files.createTempFile("stub-", ".class")
        return try {
            tmp.writeBytes(classBytes)
            dexOne(tmp)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    fun dexOne(classFile: Path): ByteArray {
        val tmpDir = Files.createTempDirectory("d8-")
        try {
            val process = ProcessBuilder(
                d8.toString(),
                "--no-desugaring",
                "--min-api", minApi.toString(),
                "--output", tmpDir.toString(),
                classFile.toString(),
            )
                .redirectErrorStream(false)
                .start()

            val stderr = process.errorStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                throw IllegalStateException("d8 exited with code $exitCode: $stderr")
            }

            return tmpDir.resolve("classes.dex").readBytes()
        } finally {
            // Clean up temp dir
            Files.walk(tmpDir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }
}
