plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("fixture.build-logic")
}

android {
    namespace = "dev.hotreload.fixture.agp9"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.hotreload.fixture.agp9"
        // The injected runtime must merge into apps whose install floor predates hot reload's
        // API-30 device requirement; it disables itself on older devices.
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildFeatures {
        compose = true
    }

    buildTypes {
        create("qa") {
            initWith(getByName("debug"))
            isDebuggable = true
            // Coverage rewrites classes while packaging, after Kotlin's class outputs. The
            // zero-touch bootstrap must disable it so installed DEX and patch shapes match.
            enableAndroidTestCoverage = true
            enableUnitTestCoverage = true
            applicationIdSuffix = ".qa"
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
