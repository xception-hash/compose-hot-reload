plugins {
    `java-gradle-plugin`
    `kotlin-dsl`
    `maven-publish`
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
