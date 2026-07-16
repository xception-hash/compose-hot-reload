plugins {
    id("com.android.library")
    id("maven-publish")
}

// Group = the JitPack coordinate (com.github.<user>.<repo>) so the samples' composite-build
// substitution matches the exact dependency the gradle plugin injects for published consumers.
group = "com.github.xception-hash.compose-hot-reload"
version = "0.1.6"

android {
    namespace = "dev.hotreload.client"
    compileSdk = 36

    defaultConfig {
        // Host apps may support older devices; the initializer disables itself below API 30.
        minSdk = 24
        aarMetadata {
            // The runtime uses API-30 framework types but no compileSdk-36-only resources/APIs.
            // Keep the local zero-touch AAR consumable by projects compiling against API 30+.
            minCompileSdk = 30
        }
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
    testImplementation("junit:junit:4.13.2")
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = project.group.toString()
                artifactId = "runtime-client"
                version = project.version.toString()
            }
        }
    }
}
