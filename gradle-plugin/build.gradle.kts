plugins {
    `java-gradle-plugin`
    `kotlin-dsl`
}

group = "dev.hotreload"
version = "0.1"

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
