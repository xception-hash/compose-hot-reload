plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.hotreload.multisample.feature"
    compileSdk = 36

    defaultConfig {
        minSdk = 30
    }

    buildFeatures {
        compose = true
    }
}

// TODO: absorbed by dev.hotreload plugin once multi-module lands
kotlin {
    compilerOptions {
        freeCompilerArgs.addAll(
            "-Xlambdas=class",
            "-Xsam-conversions=class",
            "-Xstring-concat=inline",
        )
    }
}

dependencies {
    implementation(project(":core"))

    val composeBom = platform("androidx.compose:compose-bom:2026.06.01")
    implementation(composeBom)
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
}
