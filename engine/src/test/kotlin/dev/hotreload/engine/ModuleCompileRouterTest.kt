package dev.hotreload.engine

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class ModuleCompileRouterTest {
    @Test
    fun `library-only batch compiles its selected variant without relying on app task`() {
        val root = Files.createTempDirectory("compile-routing")
        val appSource = root.resolve("app/src/main/kotlin/App.kt")
        val librarySource = root.resolve("feature/src/demo/kotlin/Feature.kt")
        Files.createDirectories(appSource.parent)
        Files.createDirectories(librarySource.parent)
        Files.writeString(appSource, "package test")
        Files.writeString(librarySource, "package test")

        val tasks = ModuleCompileRouter.tasksFor(
            changedSources = listOf(librarySource),
            targets = listOf(
                ModuleCompileRouter.Target(":app", ":app:compileDemoDebugKotlin", listOf(appSource.parent)),
                ModuleCompileRouter.Target(":feature", ":feature:compileDemoDebugKotlin", listOf(librarySource.parent)),
            ),
        )

        assertEquals(listOf(":feature:compileDemoDebugKotlin"), tasks)
    }

    @Test
    fun `mixed debounced batch keeps both module tasks in one declared order`() {
        val root = Files.createTempDirectory("compile-routing")
        val appSource = root.resolve("app/src/main/kotlin/App.kt")
        val librarySource = root.resolve("feature/src/main/kotlin/Feature.kt")
        Files.createDirectories(appSource.parent)
        Files.createDirectories(librarySource.parent)
        Files.writeString(appSource, "package test")
        Files.writeString(librarySource, "package test")
        val targets = listOf(
            ModuleCompileRouter.Target(":app", ":app:compileDebugKotlin", listOf(appSource.parent)),
            ModuleCompileRouter.Target(":feature", ":feature:compileDebugKotlin", listOf(librarySource.parent)),
        )

        assertEquals(
            listOf(":app:compileDebugKotlin", ":feature:compileDebugKotlin"),
            ModuleCompileRouter.tasksFor(listOf(appSource, librarySource), targets),
        )
    }
}
