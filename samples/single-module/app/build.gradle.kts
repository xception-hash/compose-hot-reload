plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("dev.hotreload")
}

android {
    namespace = "dev.hotreload.sample"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.hotreload.sample"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
    }

    buildFeatures {
        compose = true
    }

    buildTypes {
        getByName("debug") {
            // Regression fixture: dev.hotreload must disable both coverage modes in
            // androidComponents.finalizeDsl so packaged class shapes match watched Kotlin
            // outputs. e2e/run.sh inspects the installed debug APK for `$jacocoInit`.
            enableAndroidTestCoverage = true
            enableUnitTestCoverage = true
        }
        create("qa") {
            initWith(getByName("debug"))
            isDebuggable = true
            matchingFallbacks += "debug"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.01")
    implementation(composeBom)
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
}
