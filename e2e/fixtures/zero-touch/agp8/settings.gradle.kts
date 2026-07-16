pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    // Keep the fixture runnable from a warmed offline cache without requiring the
    // small plugin-marker POMs. The Compose plugin has its own implementation module;
    // Android/JVM plugin IDs are both implemented by kotlin-gradle-plugin.
    resolutionStrategy {
        eachPlugin {
            when {
                requested.id.id == "org.jetbrains.kotlin.plugin.compose" ->
                    useModule("org.jetbrains.kotlin:compose-compiler-gradle-plugin:${requested.version}")
                requested.id.id.startsWith("org.jetbrains.kotlin.") ->
                    useModule("org.jetbrains.kotlin:kotlin-gradle-plugin:${requested.version}")
            }
        }
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "zero-touch-agp8"
include(":mobile", ":feature", ":core", ":unwatched")
project(":mobile").projectDir = file("applications/phone")
project(":feature").projectDir = file("features/card")
project(":core").projectDir = file("shared/domain")
project(":unwatched").projectDir = file("tools/unwatched")
