package dev.hotreload.engine

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class ProfileStoreTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun saveLoadRoundTrip() {
        val store = ProfileStore(tempDir)
        val profile = Profile(
            project = "/some/project",
            appId = "my.app",
            device = "emulator-5554",
            integrationMode = IntegrationMode.ZERO_TOUCH,
        )
        val savedPath = store.save("my-app", profile)
        assertEquals(tempDir.resolve("projects/my-app.toml"), savedPath)
        assertTrue("zero-touch = true" in Files.readString(savedPath))
        assertTrue("device = \"emulator-5554\"" in Files.readString(savedPath))
        
        val loaded = store.load("my-app")
        assertEquals(profile, loaded)
    }

    @Test
    fun legacyProfileDefaultsToConfiguredMode() {
        val store = ProfileStore(tempDir)
        val file = store.path("legacy")
        Files.createDirectories(file.parent)
        Files.writeString(file, "schema = 1\nproject = \"/some/project\"\n")

        assertEquals(IntegrationMode.CONFIGURED, store.load("legacy").integrationMode)
    }

    @Test
    fun falseZeroTouchValueMeansConfiguredMode() {
        val store = ProfileStore(tempDir)
        val file = store.path("configured")
        Files.createDirectories(file.parent)
        Files.writeString(file, "schema = 1\nproject = \"/some/project\"\nzero-touch = false\n")

        assertEquals(IntegrationMode.CONFIGURED, store.load("configured").integrationMode)
    }

    @Test
    fun nonBooleanZeroTouchValueRejected() {
        val store = ProfileStore(tempDir)
        val file = store.path("broken")
        Files.createDirectories(file.parent)
        Files.writeString(file, "schema = 1\nproject = \"/some/project\"\nzero-touch = \"yes\"\n")

        val error = assertFailsWith<IllegalArgumentException> { store.load("broken") }
        assertTrue("'zero-touch' must be boolean" in (error.message ?: ""))
    }

    @Test
    fun reservedBootstrapPropertyInProfileRejected() {
        val store = ProfileStore(tempDir)
        val file = store.path("reserved")
        Files.createDirectories(file.parent)
        Files.writeString(
            file,
            "schema = 1\nproject = \"/some/project\"\ngradle-args = [\"-Pdev.hotreload.bootstrap.jar=/tmp/x\"]\n",
        )

        val error = assertFailsWith<IllegalArgumentException> { store.load("reserved") }
        assertTrue("reserved for zero-touch bootstrap internals" in (error.message ?: ""))
    }

    @Test
    fun overwriteUpdates() {
        val store = ProfileStore(tempDir)
        val p1 = Profile(project = "/first")
        val p2 = Profile(project = "/second")
        store.save("my-app", p1)
        assertEquals("/first", store.load("my-app").project)
        
        store.save("my-app", p2)
        assertEquals("/second", store.load("my-app").project)
    }

    @Test
    fun loadMissingThrows() {
        val store = ProfileStore(tempDir)
        val e = assertFailsWith<IllegalArgumentException> {
            store.load("nope")
        }
        val expectedPath = tempDir.resolve("projects/nope.toml").toString()
        assertTrue(e.message!!.contains("profile 'nope' not found: $expectedPath"))
    }

    @Test
    fun nameGuardRejections() {
        val store = ProfileStore(tempDir)
        val badNames = listOf("../evil", "a/b", "a;b", "")
        for (badName in badNames) {
            val e1 = assertFailsWith<IllegalArgumentException> {
                store.load(badName)
            }
            assertTrue(e1.message!!.contains("invalid profile name"))

            val e2 = assertFailsWith<IllegalArgumentException> {
                store.save(badName, Profile(project = "/path"))
            }
            assertTrue(e2.message!!.contains("invalid profile name"))

            val e3 = assertFailsWith<IllegalArgumentException> {
                store.path(badName)
            }
            assertTrue(e3.message!!.contains("invalid profile name"))
        }
    }

    @Test
    fun acceptsValidName() {
        val store = ProfileStore(tempDir)
        val path = store.path("my-app.v2")
        assertEquals(tempDir.resolve("projects/my-app.v2.toml"), path)
    }

    @Test
    fun saveLoadDiscoveryRoundTrip() {
        val store = ProfileStore(tempDir)
        val json = "{\"schemaVersion\":1}"
        val savedPath = store.saveDiscovery("my-app", json)
        assertEquals(tempDir.resolve("projects/my-app.discovery.json"), savedPath)

        val loaded = store.loadDiscovery("my-app")
        assertEquals(json, loaded)
    }

    @Test
    fun loadDiscoveryAbsentReturnsNull() {
        val store = ProfileStore(tempDir)
        assertNull(store.loadDiscovery("missing-discovery"))
    }

    @Test
    fun nameGuardRejectionsDiscovery() {
        val store = ProfileStore(tempDir)
        val badNames = listOf("../evil", "a/b", "a;b", "")
        for (badName in badNames) {
            val e1 = assertFailsWith<IllegalArgumentException> {
                store.loadDiscovery(badName)
            }
            assertTrue(e1.message!!.contains("invalid profile name"))

            val e2 = assertFailsWith<IllegalArgumentException> {
                store.saveDiscovery(badName, "{}")
            }
            assertTrue(e2.message!!.contains("invalid profile name"))

            val e3 = assertFailsWith<IllegalArgumentException> {
                store.discoveryPath(badName)
            }
            assertTrue(e3.message!!.contains("invalid profile name"))
        }
    }
}
