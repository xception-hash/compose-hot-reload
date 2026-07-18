package dev.hotreload.engine

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import org.objectweb.asm.ClassReader

/**
 * Compiles a single `.class` file to a `.dex` using the external `d8` tool.
 *
 * Desugaring deliberately stays enabled and uses the installed APK's minSdk. In particular, an
 * APK below API 24 moves interface static/default methods to `$-CC` companion classes; patch call
 * sites must be rewritten to those same owners or recomposition fails with `NoSuchMethodError`.
 * [classpath] lets D8 identify interface owners without folding those dependency classes into the
 * patch dex.
 */
class DexCompiler(
    private val d8: Path,
    private val minApi: Int = 30,
    private val classpath: List<Path> = emptyList(),
) {

    data class Output(
        val primaryDex: ByteArray,
        /** Additional one-class dexes emitted by D8 desugaring, keyed by binary class name. */
        val syntheticDexes: Map<String, ByteArray>,
    )

    /**
     * D8 output for a coupled patch set. Every requested input owns exactly one primary DEX;
     * desugaring support DEXes are produced once from the complete input set.
     */
    data class BatchOutput(
        val primaryDexes: Map<String, ByteArray>,
        val syntheticDexes: Map<String, ByteArray>,
    )

    init {
        require(minApi > 0) { "minApi must be positive: $minApi" }
    }

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
        return dexBytesOutput(classBytes).primaryDex
    }

    fun dexBytesOutput(classBytes: ByteArray): Output {
        val tmp = Files.createTempFile("stub-", ".class")
        return try {
            tmp.writeBytes(classBytes)
            dexOneOutput(tmp)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    fun dexOne(classFile: Path): ByteArray = dexOneOutput(classFile).primaryDex

    fun dexOneOutput(classFile: Path): Output {
        val internalName = ClassReader(classFile.readBytes()).className
        val batch = dexOutputs(listOf(classFile))
        return Output(batch.primaryDexes.getValue(internalName), batch.syntheticDexes)
    }

    /**
     * Dex every changed primary together. This is essential for compiler-generated lambda
     * families: running D8 per class lets each invocation independently choose support-class
     * ownership, while an ART patch injects those support definitions into one classloader.
     */
    fun dexOutputs(classFiles: Collection<Path>): BatchOutput {
        require(classFiles.isNotEmpty()) { "at least one class file is required" }
        val tmpDir = Files.createTempDirectory("d8-")
        try {
            val inputs = classFiles.distinct()
            val internalNames = inputs.associateWith { ClassReader(it.readBytes()).className }
            val command = buildList {
                add(d8.toString())
                // ART redefine accepts exactly one class definition. Default desugaring may emit
                // companions/global synthetics, so keep them in separate files and return only the
                // dex corresponding to the requested input class.
                addAll(listOf("--file-per-class", "--min-api", minApi.toString()))
                classpath.distinct().forEach { entry ->
                    addAll(listOf("--classpath", entry.toString()))
                }
                addAll(listOf("--output", tmpDir.toString()))
                addAll(inputs.map(Path::toString))
            }
            val process = ProcessBuilder(command)
                .redirectErrorStream(false)
                .start()

            val stderr = process.errorStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                throw IllegalStateException("d8 exited with code $exitCode: $stderr")
            }

            val primaries = internalNames.values.associateWith { internalName ->
                tmpDir.resolve("$internalName.dex").readBytes()
            }
            val synthetics = Files.walk(tmpDir).use { files ->
                files.filter { file ->
                    Files.isRegularFile(file) &&
                        file.fileName.toString().endsWith(".dex") &&
                        file !in internalNames.values.map { tmpDir.resolve("$it.dex") }
                }
                    .iterator()
                    .asSequence()
                    .associate { file ->
                        val relative = tmpDir.relativize(file)
                        val binaryName = relative.iterator().asSequence()
                            .joinToString(".") { it.toString() }
                            .removeSuffix(".dex")
                        binaryName to file.readBytes()
                    }
            }
            return BatchOutput(primaries, synthetics)
        } finally {
            // Clean up temp dir
            Files.walk(tmpDir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }
}
