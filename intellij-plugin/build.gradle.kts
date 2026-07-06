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
    }

    // CliProtocol is pure Kotlin, so its tests are plain JUnit 5 (no platform fixture needed).
    // We deliberately do NOT add testFramework(TestFrameworkType.Platform): it registers
    // com.intellij.tests.JUnit5TestSessionListener via META-INF/services, which cannot
    // instantiate outside the full IDE test runtime and breaks the plain-JUnit5 :test task.
    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // The IntelliJ Platform Gradle Plugin wires its (JUnit4-based) test runtime into the :test
    // task, so org.junit.rules.TestRule must be present even though our tests are pure JUnit5.
    testRuntimeOnly("junit:junit:4.13.2")
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
