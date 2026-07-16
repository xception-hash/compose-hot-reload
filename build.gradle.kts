plugins {
    kotlin("jvm") version "2.4.0" apply false
}

subprojects {
    repositories {
        google()
        mavenCentral()
        maven("https://repo.gradle.org/gradle/libs-releases")
    }

    // :bootstrap is deliberately Java-only and emits Java 17 bytecode for target builds
    // running Gradle on JDK 17. The host engine/CLI/protocol remain Kotlin/JDK 21.
    if (path != ":bootstrap") {
        apply(plugin = "org.jetbrains.kotlin.jvm")
        configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
            jvmToolchain(21)
        }
    }
}
