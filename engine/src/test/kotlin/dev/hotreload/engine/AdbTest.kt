package dev.hotreload.engine

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AdbTest {

    @Test
    fun commandPrefixWithoutSerial() {
        val adb = Adb(Path.of("adb"))
        assertEquals(listOf("adb"), adb.commandPrefix)
    }

    @Test
    fun commandPrefixWithSerial() {
        val adb = Adb(Path.of("adb"), "emulator-5554")
        assertEquals(listOf("adb", "-s", "emulator-5554"), adb.commandPrefix)
    }

    @Test
    fun parsesInstalledMinSdkFromPackageDump() {
        val dump = """
            Packages:
              Package [dev.hotreload.test]:
                versionCode=42 minSdk=23 targetSdk=36
        """.trimIndent()

        assertEquals(23, Adb.parseInstalledMinSdk(dump))
    }

    @Test
    fun missingOrInvalidInstalledMinSdkIsUnknown() {
        assertNull(Adb.parseInstalledMinSdk("versionCode=42 targetSdk=36"))
        assertNull(Adb.parseInstalledMinSdk("minSdk=0 targetSdk=36"))
    }
}
