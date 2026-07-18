plugins {
    `java-gradle-plugin`
}

gradlePlugin {
    plugins {
        create("fixtureBuildLogic") {
            id = "fixture.build-logic"
            implementationClass = "dev.hotreload.fixture.BuildLogicPlugin"
        }
    }
}
