pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        // The IntelliJ Platform Gradle Plugin and its metadata:
        maven("https://cache-redirector.jetbrains.com/intellij-dependencies")
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "hotreload-intellij-plugin"

// Wire the root compose-hot-reload build so we can depend on :cli:installDist
// and bundle the CLI distribution inside the plugin zip.
includeBuild("..")
