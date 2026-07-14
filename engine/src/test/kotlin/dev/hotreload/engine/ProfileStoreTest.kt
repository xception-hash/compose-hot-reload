package dev.hotreload.engine

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

class ProfileStoreTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun saveLoadRoundTrip() {
        val store = ProfileStore(tempDir)
        val profile = Profile(project = "/some/project", appId = "my.app")
        val savedPath = store.save("my-app", profile)
        assertEquals(tempDir.resolve("projects/my-app.toml"), savedPath)
        
        val loaded = store.load("my-app")
        assertEquals(profile, loaded)
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
