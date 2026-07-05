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
