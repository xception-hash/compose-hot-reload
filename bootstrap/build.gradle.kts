import java.util.zip.ZipFile
import org.gradle.api.tasks.bundling.Jar

plugins {
    `java-library`
}

dependencies {
    // The bootstrap implementation uses only Gradle's public API. AGP and Kotlin are reached
    // through plugin callbacks/reflection so this JAR remains loadable across AGP 8 and 9.
    compileOnly(gradleApi())
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<JavaCompile>().configureEach {
    // The root build runs on JDK 21, but target projects may run Gradle on JDK 17.
    options.release.set(17)
}

val bootstrapJar = tasks.named<Jar>("jar")
val verifyBootstrapPackaging = tasks.register("verifyBootstrapPackaging") {
    group = "verification"
    description = "Verifies the bootstrap JAR is Java 17 and does not bundle AGP or Kotlin classes."
    dependsOn(bootstrapJar)
    inputs.file(bootstrapJar.flatMap { it.archiveFile })

    doLast {
        check(!pluginManager.hasPlugin("org.jetbrains.kotlin.jvm")) {
            "the Java-only bootstrap project must not apply the Kotlin JVM plugin"
        }
        val archive = bootstrapJar.get().archiveFile.get().asFile
        ZipFile(archive).use { zip ->
            val implementation = zip.getEntry("dev/hotreload/bootstrap/BootstrapPlugin.class")
                ?: error("bootstrap JAR is missing BootstrapPlugin.class")
            val header = zip.getInputStream(implementation).use { it.readNBytes(8) }
            check(header.size == 8 && header.copyOfRange(0, 4).contentEquals(byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte()))) {
                "BootstrapPlugin.class has an invalid class-file header"
            }
            val major = ((header[6].toInt() and 0xff) shl 8) or (header[7].toInt() and 0xff)
            check(major == 61) { "BootstrapPlugin.class major version is $major, expected Java 17 (61)" }

            val forbidden = mutableListOf<String>()
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val name = entries.nextElement().name
                if (name.startsWith("com/android/") || name.startsWith("kotlin/") || name.endsWith(".kotlin_module")) {
                    forbidden += name
                }
            }
            check(forbidden.isEmpty()) {
                "bootstrap JAR bundles forbidden AGP/Kotlin classes: ${forbidden.joinToString()}"
            }
        }
    }
}

tasks.named("test") {
    dependsOn(verifyBootstrapPackaging)
}
