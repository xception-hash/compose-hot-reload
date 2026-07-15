val zeroTouchBootstrap = configurations.create("zeroTouchBootstrap") {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    zeroTouchBootstrap(project(":bootstrap"))

    implementation(project(":protocol"))
    implementation("io.methvin:directory-watcher:0.19.1")
    implementation("org.ow2.asm:asm:9.9")
    implementation("org.ow2.asm:asm-tree:9.9")
    implementation("org.ow2.asm:asm-util:9.9")
    implementation("org.gradle:gradle-tooling-api:9.6.1")
    implementation("com.google.code.gson:gson:2.11.0")
    runtimeOnly("org.slf4j:slf4j-nop:2.0.16")

    testImplementation(kotlin("test"))
}

val runtimeClientAar = layout.projectDirectory.file(
    "../runtime-client/lib/build/outputs/aar/runtime-client-release.aar",
)
val generatedZeroTouchResources = layout.buildDirectory.dir("generated/zeroTouchResources")

val generateZeroTouchResources = tasks.register<Sync>("generateZeroTouchResources") {
    dependsOn(gradle.includedBuild("runtime-client").task(":runtime-client:assembleRelease"))

    from(zeroTouchBootstrap) {
        into("dev/hotreload/bootstrap")
        rename { "bootstrap.jar" }
    }
    from(runtimeClientAar) {
        into("dev/hotreload/bootstrap")
        rename { "runtime-client.aar" }
    }
    into(generatedZeroTouchResources)
}

sourceSets["main"].resources.srcDir(generateZeroTouchResources)

tasks.test {
    useJUnitPlatform()
}
