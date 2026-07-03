buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        // AGP 9 bundles KGP 2.2.10; pin the project's Kotlin to our chosen version instead.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.0")
    }
}

plugins {
    id("com.android.application") version "9.2.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.0" apply false
}
