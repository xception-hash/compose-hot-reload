// protocol module — stdlib only (sources are also compiled into the Android runtime-client)
dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
