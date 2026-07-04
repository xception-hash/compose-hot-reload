plugins {
    id("com.android.library")
}

group = "dev.hotreload"
version = "0.1"

android {
    namespace = "dev.hotreload.client"
    compileSdk = 36

    defaultConfig {
        minSdk = 30
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    ndkVersion = "28.2.13676358"
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    sourceSets {
        getByName("main") {
            kotlin.srcDir("../../protocol/src/main/kotlin")
        }
    }
}

dependencies {
    implementation("androidx.startup:startup-runtime:1.2.0")
}

