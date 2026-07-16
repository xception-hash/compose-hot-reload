plugins {
    `java-gradle-plugin`
    `kotlin-dsl`
    `maven-publish`
}

group = "dev.hotreload"
version = "0.1.6"

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
