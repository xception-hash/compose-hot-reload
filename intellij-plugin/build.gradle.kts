import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    kotlin("jvm") version "2.4.0"
    id("org.jetbrains.intellij.platform") version "2.5.0"
}

group = "dev.hotreload"
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // Build/run against IntelliJ IDEA Community; the plugin also loads in Android Studio
        // (it only uses core platform APIs: status bar, actions, project settings).
        intellijIdeaCommunity(providers.gradleProperty("platformVersion").get())
        testFramework(TestFrameworkType.Platform)
    }

    // CliProtocol is pure Kotlin, so its tests are plain JUnit 5 (no platform fixture needed).
    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(21)
}

intellijPlatform {
    pluginConfiguration {
        id = "dev.hotreload.intellij"
        name = "Compose Hot Reload"
        version = providers.gradleProperty("pluginVersion").get()
        description = "Flutter-style hot reload for Jetpack Compose on Android, driven from the IDE. " +
            "Spawns the hotreload CLI and shows live reload status in the status bar."
        vendor {
            name = "Compose Hot Reload (OSS)"
        }
        ideaVersion {
            // JDK 21 baseline (2024.2+). Leave the upper bound open so new IDE builds load it.
            sinceBuild = "242"
            untilBuild = provider { null }
        }
    }
}

tasks.test {
    useJUnitPlatform()
}
