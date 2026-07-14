package dev.hotreload.engine

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class ApkLocatorTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun jsonPickBeatsNewerStaleApkInLaterCandidateDir() {
        val dir1 = tempDir.resolve("dir1")
        val dir2 = tempDir.resolve("dir2")
        Files.createDirectories(dir1)
        Files.createDirectories(dir2)

        val json = """
            {
                "variantName": "debug",
                "applicationId": "com.example",
                "elements": [
                    { "outputFile": "app-debug.apk" }
                ]
            }
        """.trimIndent()
        Files.writeString(dir1.resolve("output-metadata.json"), json)
        val apk1 = dir1.resolve("app-debug.apk")
        Files.writeString(apk1, "apk1 content")
        Files.setLastModifiedTime(apk1, java.nio.file.attribute.FileTime.fromMillis(1000))

        val apk2 = dir2.resolve("app-debug.apk")
        Files.writeString(apk2, "apk2 content")
        Files.setLastModifiedTime(apk2, java.nio.file.attribute.FileTime.fromMillis(5000))

        val located = ApkLocator.locate(listOf(dir1, dir2), "debug", "com.example")
        requireNotNull(located)
        assertEquals(apk1, located.apk)
        assertEquals("output-metadata.json", located.source)
    }

    @Test
    fun variantMismatchSkipsToNextDir() {
        val dir1 = tempDir.resolve("dir1")
        val dir2 = tempDir.resolve("dir2")
        Files.createDirectories(dir1)
        Files.createDirectories(dir2)

        val json1 = """
            {
                "variantName": "release",
                "applicationId": "com.example",
                "elements": [
                    { "outputFile": "app-release.apk" }
                ]
            }
        """.trimIndent()
        Files.writeString(dir1.resolve("output-metadata.json"), json1)
        val apk1 = dir1.resolve("app-release.apk")
        Files.writeString(apk1, "apk1")

        val json2 = """
            {
                "variantName": "debug",
                "applicationId": "com.example",
                "elements": [
                    { "outputFile": "app-debug.apk" }
                ]
            }
        """.trimIndent()
        Files.writeString(dir2.resolve("output-metadata.json"), json2)
        val apk2 = dir2.resolve("app-debug.apk")
        Files.writeString(apk2, "apk2")

        val located = ApkLocator.locate(listOf(dir1, dir2), "debug", "com.example")
        requireNotNull(located)
        assertEquals(apk2, located.apk)
    }

    @Test
    fun applicationIdMismatchSkips() {
        val dir1 = tempDir.resolve("dir1")
        val dir2 = tempDir.resolve("dir2")
        Files.createDirectories(dir1)
        Files.createDirectories(dir2)

        val json1 = """
            {
                "variantName": "debug",
                "applicationId": "com.other",
                "elements": [
                    { "outputFile": "app-debug.apk" }
                ]
            }
        """.trimIndent()
        Files.writeString(dir1.resolve("output-metadata.json"), json1)
        val apk1 = dir1.resolve("app-debug.apk")
        Files.writeString(apk1, "apk1")

        val json2 = """
            {
                "variantName": "debug",
                "applicationId": "com.example",
                "elements": [
                    { "outputFile": "app-debug.apk" }
                ]
            }
        """.trimIndent()
        Files.writeString(dir2.resolve("output-metadata.json"), json2)
        val apk2 = dir2.resolve("app-debug.apk")
        Files.writeString(apk2, "apk2")

        val located = ApkLocator.locate(listOf(dir1, dir2), "debug", "com.example")
        requireNotNull(located)
        assertEquals(apk2, located.apk)
    }

    @Test
    fun twoElementsReturnsNewestExisting() {
        val dir = tempDir.resolve("dir")
        Files.createDirectories(dir)

        val json = """
            {
                "variantName": "debug",
                "applicationId": "com.example",
                "elements": [
                    { "outputFile": "app-debug-1.apk" },
                    { "outputFile": "app-debug-2.apk" }
                ]
            }
        """.trimIndent()
        Files.writeString(dir.resolve("output-metadata.json"), json)
        val apk1 = dir.resolve("app-debug-1.apk")
        val apk2 = dir.resolve("app-debug-2.apk")
        Files.writeString(apk1, "apk1")
        Files.writeString(apk2, "apk2")

        Files.setLastModifiedTime(apk1, java.nio.file.attribute.FileTime.fromMillis(2000))
        Files.setLastModifiedTime(apk2, java.nio.file.attribute.FileTime.fromMillis(4000))

        val located = ApkLocator.locate(listOf(dir), "debug", "com.example")
        requireNotNull(located)
        assertEquals(apk2, located.apk)
    }

    @Test
    fun elementsMissingOnDiskSkips() {
        val dir1 = tempDir.resolve("dir1")
        val dir2 = tempDir.resolve("dir2")
        Files.createDirectories(dir1)
        Files.createDirectories(dir2)

        val json1 = """
            {
                "variantName": "debug",
                "applicationId": "com.example",
                "elements": [
                    { "outputFile": "nonexistent.apk" }
                ]
            }
        """.trimIndent()
        Files.writeString(dir1.resolve("output-metadata.json"), json1)

        val json2 = """
            {
                "variantName": "debug",
                "applicationId": "com.example",
                "elements": [
                    { "outputFile": "app-debug.apk" }
                ]
            }
        """.trimIndent()
        Files.writeString(dir2.resolve("output-metadata.json"), json2)
        val apk2 = dir2.resolve("app-debug.apk")
        Files.writeString(apk2, "apk2")

        val located = ApkLocator.locate(listOf(dir1, dir2), "debug", "com.example")
        requireNotNull(located)
        assertEquals(apk2, located.apk)
    }

    @Test
    fun malformedJsonFallsback() {
        val dir = tempDir.resolve("dir")
        Files.createDirectories(dir)

        Files.writeString(dir.resolve("output-metadata.json"), "malformed garbage")
        val apk = dir.resolve("app-debug.apk")
        Files.writeString(apk, "apk")

        val located = ApkLocator.locate(listOf(dir), "debug", "com.example")
        requireNotNull(located)
        assertEquals(apk, located.apk)
        assertEquals("newest-apk fallback", located.source)
    }

    @Test
    fun noJsonAnywhereReturnsNewest() {
        val dir = tempDir.resolve("dir")
        Files.createDirectories(dir)

        val apk1 = dir.resolve("app-debug-old.apk")
        val apk2 = dir.resolve("app-debug-new.apk")
        Files.writeString(apk1, "apk1")
        Files.writeString(apk2, "apk2")

        Files.setLastModifiedTime(apk1, java.nio.file.attribute.FileTime.fromMillis(1000))
        Files.setLastModifiedTime(apk2, java.nio.file.attribute.FileTime.fromMillis(5000))

        val located = ApkLocator.locate(listOf(dir), "debug", "com.example")
        requireNotNull(located)
        assertEquals(apk2, located.apk)
        assertEquals("newest-apk fallback", located.source)
    }
}
