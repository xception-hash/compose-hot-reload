plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("dev.hotreload")
}

android {
    namespace = "dev.hotreload.capture.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.hotreload.capture"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "1"
    }

    buildFeatures { compose = true }
}

dependencies {
    implementation(project(":feature"))
    val composeBom = platform("androidx.compose:compose-bom:2026.06.01")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
}
