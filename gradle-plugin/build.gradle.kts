plugins {
    `java-gradle-plugin`
    `kotlin-dsl`
    `maven-publish`
}

// Target projects may legitimately run their Gradle daemon on the supported JDK 17 lane.
// Keep the plugin consumable there even though this standalone build itself uses JBR 21.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}
tasks.withType<org.gradle.api.tasks.compile.JavaCompile>().configureEach {
    options.release.set(17)
}
java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(17))
}

// Match the public JitPack coordinate injected by the plugin and documented for consumers.
group = "com.github.xception-hash.compose-hot-reload"
version = "0.2.0"

dependencies {
    compileOnly("com.android.tools.build:gradle-api:9.2.1")
}

gradlePlugin {
    plugins {
        create("hotReload") {
            id = "dev.hotreload"
            implementationClass = "dev.hotreload.gradle.HotReloadPlugin"
        }
    }
}

publishing {
    publications {
        // java-gradle-plugin auto-creates a MavenPublication; JitPack picks it up.
    }
}
