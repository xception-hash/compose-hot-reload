pluginManagement {
    includeBuild("../../gradle-plugin")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "multi-module"
includeBuild("../../runtime-client")
include(":app", ":feature", ":core")
