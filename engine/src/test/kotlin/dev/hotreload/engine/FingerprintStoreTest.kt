package dev.hotreload.engine

import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class FingerprintStoreTest {

    @TempDir
    lateinit var tempDir: Path

    private fun fingerprint(serial: String = "emulator-5554") = Fingerprint.of(
        ProjectConfig(
            projectDir = Path.of("/proj"),
            modules = listOf(ModuleSpec.Request(":app", "app")),
            applicationId = "dev.hotreload.sample",
        ),
        serial, "sha", "/x.apk",
    )

    @Test
    fun saveLoadRoundTrip() {
        val store = FingerprintStore(tempDir)
        val fp = fingerprint()
        val saved = store.save(fp)
        assertEquals(tempDir.resolve("fingerprints/emulator-5554_dev.hotreload.sample.json"), saved)
        assertEquals(fp, store.load("emulator-5554", "dev.hotreload.sample"))
    }

    @Test
    fun absentReturnsNull() {
        val store = FingerprintStore(tempDir)
        assertNull(store.load("nope", "dev.hotreload.sample"))
    }

    @Test
    fun unparsableReturnsNullAndPrintsLoudLine() {
        val store = FingerprintStore(tempDir)
        val file = store.path("emulator-5554", "dev.hotreload.sample")
        Files.createDirectories(file.parent)
        Files.writeString(file, "{ this is not valid json ")

        val captured = ByteArrayOutputStream()
        val original = System.out
        System.setOut(PrintStream(captured))
        try {
            assertNull(store.load("emulator-5554", "dev.hotreload.sample"))
        } finally {
            System.setOut(original)
        }
        assertTrue(
            captured.toString().contains("fingerprint: ignoring unreadable fingerprint file"),
            captured.toString(),
        )
    }

    @Test
    fun serialIsSanitizedWithoutPathEscape() {
        val store = FingerprintStore(tempDir)
        val kept = store.path("emulator-5554", "dev.hotreload.sample")
        assertEquals("emulator-5554_dev.hotreload.sample.json", kept.fileName.toString())

        val weird = store.path("weird/serial:1", "dev.hotreload.sample")
        assertEquals("weird_serial_1_dev.hotreload.sample.json", weird.fileName.toString())
        // no path escape — file stays directly under <baseDir>/fingerprints/
        assertEquals(tempDir.resolve("fingerprints"), weird.parent)
    }
}
