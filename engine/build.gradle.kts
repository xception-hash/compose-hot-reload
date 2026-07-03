dependencies {
    implementation(project(":protocol"))
    implementation("io.methvin:directory-watcher:0.19.1")
    implementation("org.ow2.asm:asm:9.9")
    implementation("org.ow2.asm:asm-tree:9.9")

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
