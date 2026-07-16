buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        // AGP 9 supplies built-in Kotlin; this pins the Compose compiler plugin version.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.0")
    }
}

plugins {
    id("com.android.application") version "9.2.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.0" apply false
}
