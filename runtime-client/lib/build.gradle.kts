plugins {
    id("com.android.library")
    id("maven-publish")
}

group = "dev.hotreload"
version = "0.1.0"

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

    // Required for components["release"] to exist below (AGP won't create the
    // publishable software component otherwise → "SoftwareComponent 'release' not found").
    publishing {
        singleVariant("release")
    }
}

dependencies {
    implementation("androidx.startup:startup-runtime:1.2.0")
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = "dev.hotreload"
                artifactId = "runtime-client"
                version = project.version.toString()
            }
        }
    }
}
