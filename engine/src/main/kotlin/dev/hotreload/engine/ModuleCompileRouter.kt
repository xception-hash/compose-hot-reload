package dev.hotreload.engine

import java.nio.file.Path

/**
 * Maps a debounced Kotlin-source batch to the explicitly watched Gradle modules that own it.
 *
 * Supplying the resulting tasks to one Gradle invocation deliberately leaves dependency ordering
 * to Gradle's task graph. In particular, this does not rely on an app Kotlin task to compile an
 * independently changed Android library.
 */
internal object ModuleCompileRouter {
    internal data class Target(
        val gradlePath: String,
        val compileTask: String,
        val sourceRoots: List<Path>,
    )

    fun tasksFor(changedSources: Collection<Path>, targets: List<Target>): List<String> {
        val changed = changedSources.map { it.toAbsolutePath().normalize() }
        return targets
            .filter { target ->
                target.sourceRoots.any { root ->
                    val normalizedRoot = root.toAbsolutePath().normalize()
                    changed.any { it.startsWith(normalizedRoot) }
                }
            }
            .map { it.compileTask }
            .distinct()
    }
}
