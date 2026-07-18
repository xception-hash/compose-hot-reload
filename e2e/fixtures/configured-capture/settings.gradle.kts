pluginManagement {
    includeBuild("../../../gradle-plugin")
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

rootProject.name = "configured-capture"
includeBuild("../../../runtime-client")
include(":app", ":feature")
