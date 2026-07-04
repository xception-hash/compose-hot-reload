plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
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

    packaging {
        jniLibs {
            // Extract .so files to nativeLibraryDir so Debug.attachJvmtiAgent can load by path.
            useLegacyPackaging = true

        }
    }
}

kotlin {
    compilerOptions {
        // Emit androidx.compose.runtime.internal.FunctionKeyMeta annotations mapping
        // composable functions to their recomposition group keys (Experiment A).
        freeCompilerArgs.addAll(
            "-P",
            "plugin:androidx.compose.compiler.plugins.kotlin:generateFunctionKeyMetaAnnotations=true",
            // Deterministic lambda/SAM classes (no invokedynamic) so a patch dex built with
            // `d8 --no-desugaring` matches the shapes already installed on device.
            "-Xlambdas=class",
            "-Xsam-conversions=class",
            // String templates must not compile to invokedynamic StringConcatFactory:
            // patch dex is built with `d8 --no-desugaring`, and ART blocks that hidden
            // API at link time (NoSuchMethodError inside recomposition).
            "-Xstring-concat=inline",
        )
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.01")
    implementation(composeBom)
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
    debugImplementation("dev.hotreload:runtime-client:0.1")
}
