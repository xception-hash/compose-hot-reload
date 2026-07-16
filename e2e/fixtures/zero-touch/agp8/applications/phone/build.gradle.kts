plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.hotreload.fixture.agp8"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.hotreload.fixture.agp8"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildFeatures {
        compose = true
    }

    flavorDimensions += "channel"
    productFlavors {
        create("internal") {
            dimension = "channel"
            applicationIdSuffix = ".internal"
        }
    }

    buildTypes {
        create("stage") {
            initWith(getByName("debug"))
            isDebuggable = true
            applicationIdSuffix = ".stage"
            matchingFallbacks += "debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":feature"))

    val composeBom = platform("androidx.compose:compose-bom:2026.02.01")
    implementation(composeBom)
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.10.1")
}
