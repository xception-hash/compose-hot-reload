package dev.hotreload.engine

import java.nio.file.Path

/**
 * Wrapper around the `adb` command-line tool for port forwarding.
 *
 * Note: the spec originally described `forward(devicePort: Int)` using `tcp:` on the
 * device side, but the actual protocol uses abstract Unix sockets
 * (`localabstract:<name>`), so the parameter is a String socket name instead.
 */
class Adb(private val adb: Path) {

    /**
     * `adb forward tcp:0 localabstract:<socketName>` — returns the allocated local port.
     * Non-zero exit → [IllegalStateException] with stderr.
     */
    fun forward(socketName: String): Int {
        val process = ProcessBuilder(
            adb.toString(), "forward", "tcp:0", "localabstract:$socketName",
        )
            .redirectErrorStream(false)
            .start()

        val stdout = process.inputStream.bufferedReader().readText().trim()
        val stderr = process.errorStream.bufferedReader().readText().trim()
        val exitCode = process.waitFor()

        if (exitCode != 0) {
            throw IllegalStateException(
                "adb forward failed (exit $exitCode): $stderr — check `adb devices`"
            )
        }

        return stdout.toIntOrNull()
            ?: throw IllegalStateException("adb forward returned unexpected output: $stdout — check `adb devices`")
    }

    /**
     * Removes a previously established forward.
     */
    fun removeForward(localPort: Int) {
        val process = ProcessBuilder(
            adb.toString(), "forward", "--remove", "tcp:$localPort",
        )
            .redirectErrorStream(false)
            .start()

        val stderr = process.errorStream.bufferedReader().readText().trim()
        val exitCode = process.waitFor()

        if (exitCode != 0) {
            throw IllegalStateException(
                "adb forward --remove failed (exit $exitCode): $stderr — check `adb devices`"
            )
        }
    }
}
