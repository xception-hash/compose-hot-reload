package dev.hotreload.engine

import com.google.gson.Gson
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

object ApkLocator {
    data class Located(val apk: Path, val source: String) // "output-metadata.json" | "newest-apk fallback"

    private data class OutputElement(val outputFile: String? = null)
    private data class OutputMetadata(
        val applicationId: String? = null,
        val variantName: String? = null,
        val elements: List<OutputElement>? = null
    )

    fun locate(candidateDirs: List<Path>, variant: String, applicationId: String?): Located? {
        val gson = Gson()
        var jsonMatched = false

        for (dir in candidateDirs) {
            if (!Files.isDirectory(dir)) continue
            val metadataFile = dir.resolve("output-metadata.json")
            if (Files.isRegularFile(metadataFile)) {
                val metadata = try {
                    gson.fromJson(metadataFile.readText(), OutputMetadata::class.java)
                } catch (e: Exception) {
                    println("apk: unreadable output-metadata.json in $dir — skipping")
                    continue
                }
                if (metadata == null) {
                    println("apk: unreadable output-metadata.json in $dir — skipping")
                    continue
                }

                if (metadata.variantName != null && metadata.variantName != variant) {
                    println("apk: skipping $dir — output-metadata.json is variant '${metadata.variantName}' (want '$variant')")
                    continue
                }
                if (metadata.applicationId != null && applicationId != null && metadata.applicationId != applicationId) {
                    println("apk: skipping $dir — output-metadata.json is applicationId '${metadata.applicationId}' (want '$applicationId')")
                    continue
                }

                val existingElements = metadata.elements.orEmpty().mapNotNull { elem ->
                    elem.outputFile?.let { dir.resolve(it) }
                }.filter { Files.isRegularFile(it) }

                if (existingElements.isEmpty()) {
                    continue
                }

                val newestApk = existingElements.maxByOrNull { Files.getLastModifiedTime(it).toMillis() }
                if (newestApk != null) {
                    jsonMatched = true
                    return Located(newestApk, "output-metadata.json")
                }
            }
        }

        if (!jsonMatched) {
            println("apk: no matching output-metadata.json — newest-APK fallback may pick a stale variant")
            for (dir in candidateDirs) {
                if (!Files.isDirectory(dir)) continue
                val apk = Files.walk(dir).use { stream ->
                    stream.filter { Files.isRegularFile(it) && it.extension == "apk" }
                        .max(Comparator.comparingLong { Files.getLastModifiedTime(it).toMillis() })
                        .orElse(null)
                }
                if (apk != null) {
                    return Located(apk, "newest-apk fallback")
                }
            }
        }

        return null
    }
}
