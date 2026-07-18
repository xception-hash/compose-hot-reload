plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.compose")
    id("dev.hotreload")
}

android {
    namespace = "dev.hotreload.capture.feature"
    compileSdk = 36

    defaultConfig { minSdk = 30 }
    buildFeatures { compose = true }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.01")
    implementation(composeBom)
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
}
