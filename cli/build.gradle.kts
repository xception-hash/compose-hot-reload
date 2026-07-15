import java.util.jar.JarInputStream
import java.util.zip.ZipFile
import org.gradle.api.tasks.bundling.Zip

plugins {
    application
}

dependencies {
    implementation(project(":engine"))
}

application {
    mainClass.set("dev.hotreload.cli.MainKt")
}

val zeroTouchPayloads = setOf(
    "dev/hotreload/bootstrap/bootstrap.jar",
    "dev/hotreload/bootstrap/runtime-client.aar",
    "dev/hotreload/bootstrap/zero-touch.init.gradle",
)

val distZipTask = tasks.named<Zip>("distZip")

tasks.register("verifyZeroTouchDistribution") {
    group = "verification"
    description = "Verifies that the CLI distribution's engine JAR contains zero-touch payloads."
    dependsOn(distZipTask)
    inputs.file(distZipTask.flatMap { it.archiveFile })

    doLast {
        val archive = distZipTask.get().archiveFile.get().asFile
        ZipFile(archive).use { zip ->
            val engineEntries = mutableListOf<java.util.zip.ZipEntry>()
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.name.endsWith("/lib/engine.jar")) engineEntries += entry
            }
            check(engineEntries.size == 1) {
                "expected exactly one */lib/engine.jar in $archive, found ${engineEntries.map { it.name }}"
            }

            val bundled = mutableSetOf<String>()
            zip.getInputStream(engineEntries.single()).use { input ->
                JarInputStream(input).use { jar ->
                    var entry = jar.nextJarEntry
                    while (entry != null) {
                        bundled += entry.name
                        entry = jar.nextJarEntry
                    }
                }
            }
            val missing = zeroTouchPayloads - bundled
            check(missing.isEmpty()) {
                "CLI distribution engine JAR is missing zero-touch payloads: ${missing.joinToString()}"
            }
        }
    }
}
