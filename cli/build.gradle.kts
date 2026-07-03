plugins {
    application
}

dependencies {
    implementation(project(":engine"))
}

application {
    mainClass.set("dev.hotreload.cli.MainKt")
}
