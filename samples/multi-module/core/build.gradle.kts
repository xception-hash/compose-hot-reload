plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    compilerOptions {
        // TODO: absorbed by dev.hotreload plugin once multi-module lands
        freeCompilerArgs.addAll(
            "-Xlambdas=class",
            "-Xsam-conversions=class",
            "-Xstring-concat=inline",
        )
    }
}
