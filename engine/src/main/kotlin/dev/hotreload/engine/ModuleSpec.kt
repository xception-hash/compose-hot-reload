package dev.hotreload.engine

import java.nio.file.Files
import java.nio.file.Path

/**
 * One watched Gradle module. Everything the session needs per module derives from
 * (name, layout): the ground truth (docs/multi-module-ground-truth.md §2–3) is that
 * AGP modules (application AND library — same layout) and pure kotlin-jvm modules
 * differ in exactly two things: the compiled-classes dir and the compile task name.
 */
class ModuleSpec(
    /** Module path relative to the project dir, '/'-separated (e.g. "app", "libs/core"). */
    val name: String,
    val layout: Layout,
    projectDir: Path,
) {
    enum class Layout { AGP, JVM }

    val dir: Path = projectDir.resolve(name)
    val gradlePath: String = ":" + name.replace('/', ':')
    val sourceRoots: List<Path> = listOf(dir.resolve("src/main/kotlin"), dir.resolve("src/main/java"))
    val classesDir: Path = dir.resolve(classesSubdir(layout))
    val compileTask: String = when (layout) {
        Layout.AGP -> "$gradlePath:compileDebugKotlin"
        Layout.JVM -> "$gradlePath:compileKotlin" // kotlin-jvm has no variants
    }

    /** Resource root; only AGP modules contribute resources to the merged table. */
    val resDir: Path? = if (layout == Layout.AGP) dir.resolve("src/main/res") else null

    companion object {
        private fun classesSubdir(layout: Layout) = when (layout) {
            Layout.AGP -> "build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes"
            Layout.JVM -> "build/classes/kotlin/main"
        }

        /**
         * Resolve a module's layout by probing which classes dir the initial build
         * produced. AGP wins when both exist (a stale build/classes can survive a
         * kotlin-jvm → AGP migration). Must run AFTER the initial compile.
         */
        fun probe(projectDir: Path, name: String): ModuleSpec {
            val layout = Layout.entries.firstOrNull {
                Files.isDirectory(projectDir.resolve(name).resolve(classesSubdir(it)))
            } ?: throw IllegalStateException(
                "module '$name' has no compiled classes under either known layout " +
                    "(${Layout.entries.joinToString { classesSubdir(it) }}) — " +
                    "is it in the app's dependency graph and does it contain Kotlin sources?",
            )
            return ModuleSpec(name, layout, projectDir)
        }
    }
}
