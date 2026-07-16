package dev.hotreload.idea

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class AndroidSdkResolverTest {
    @Test fun `local properties sdk dir wins over everything else`(@TempDir tempDir: File) {
        val projectDir = File(tempDir, "project").apply { mkdirs() }
        val sdkDir = File(tempDir, "sdk-from-local-properties").apply { mkdirs() }
        val envSdkDir = File(tempDir, "sdk-from-env").apply { mkdirs() }
        File(projectDir, "local.properties").writeText("sdk.dir=${sdkDir.absolutePath}\n")

        val result = AndroidSdkResolver.discover(
            projectDir.absolutePath,
            mapOf("ANDROID_HOME" to envSdkDir.absolutePath),
            userHome = tempDir.absolutePath,
            isWindows = false,
        )
        assertEquals(sdkDir.absolutePath, result)
    }

    @Test fun `ANDROID_HOME from the passed env map is used when there is no local properties`(@TempDir tempDir: File) {
        val projectDir = File(tempDir, "project").apply { mkdirs() }
        val envSdkDir = File(tempDir, "sdk-from-env").apply { mkdirs() }

        val result = AndroidSdkResolver.discover(
            projectDir.absolutePath,
            mapOf("ANDROID_HOME" to envSdkDir.absolutePath),
            userHome = tempDir.absolutePath,
            isWindows = false,
        )
        assertEquals(envSdkDir.absolutePath, result)
    }

    @Test fun `a candidate pointing at a non-existent dir is skipped in favor of the next existing one`(@TempDir tempDir: File) {
        val projectDir = File(tempDir, "project").apply { mkdirs() }
        val realSdkDir = File(tempDir, "sdk-real").apply { mkdirs() }

        val result = AndroidSdkResolver.discover(
            projectDir.absolutePath,
            mapOf(
                "ANDROID_HOME" to File(tempDir, "does-not-exist").absolutePath,
                "ANDROID_SDK_ROOT" to realSdkDir.absolutePath,
            ),
            userHome = tempDir.absolutePath,
            isWindows = false,
        )
        assertEquals(realSdkDir.absolutePath, result)
    }

    @Test fun `nothing exists returns null`(@TempDir tempDir: File) {
        val projectDir = File(tempDir, "project").apply { mkdirs() }

        val result = AndroidSdkResolver.discover(
            projectDir.absolutePath,
            emptyMap(),
            userHome = File(tempDir, "no-such-home").absolutePath,
            isWindows = false,
        )
        assertNull(result)
    }

    @Test fun `a tilde value in ANDROID_HOME expands against the passed userHome`(@TempDir tempDir: File) {
        val projectDir = File(tempDir, "project").apply { mkdirs() }
        val home = File(tempDir, "home").apply { mkdirs() }
        val sdkDir = File(home, "Android/Sdk").apply { mkdirs() }

        val result = AndroidSdkResolver.discover(
            projectDir.absolutePath,
            mapOf("ANDROID_HOME" to "~/Android/Sdk"),
            userHome = home.absolutePath,
            isWindows = false,
        )
        assertEquals(sdkDir.absolutePath, result)
    }
}
