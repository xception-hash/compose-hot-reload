package dev.hotreload.engine

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
