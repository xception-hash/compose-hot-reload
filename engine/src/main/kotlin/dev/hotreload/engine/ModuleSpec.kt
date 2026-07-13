package dev.hotreload.engine

import java.nio.file.Files
import java.nio.file.Path

/**
 * A watched Gradle module. [gradlePath] identifies the project in Gradle while
 * [relativeDir] identifies its physical directory, since large builds commonly
 * map logical project names to layered source directories.
 */
class ModuleSpec private constructor(
    val request: Request,
    val layout: Layout,
    val variant: String,
    projectDir: Path,
    val classesDir: Path,
) {
    data class Request(
        val gradlePath: String,
        val relativeDir: String,
        val variant: String? = null,
    ) {
        val displayName: String get() = gradlePath.removePrefix(":")

        companion object {
            /**
             * Accepted forms:
             * - `app`
             * - `libs/core`
             * - `:feature:core=features/core`
             */
            fun parse(value: String): Request {
                val parts = value.split('=', limit = 2)
                val rawGradlePath = parts[0].trim()
                require(rawGradlePath.isNotEmpty()) { "module Gradle path must not be empty" }

                val gradlePath = ":" + rawGradlePath
                    .removePrefix(":")
                    .replace('/', ':')
                    .trim(':')
                val relativeDir = if (parts.size == 2) {
                    parts[1].trim()
                } else {
                    rawGradlePath.removePrefix(":").replace(':', '/')
                }
                require(relativeDir.isNotEmpty()) { "module directory must not be empty" }
                require(!Path.of(relativeDir).isAbsolute) {
                    "module directory must be relative to the project root: $relativeDir"
                }
                return Request(gradlePath, relativeDir)
            }

            fun applyVariantOverrides(
                requests: List<Request>,
                values: List<String>,
            ): List<Request> {
                val overrides = values.associate { value ->
                    val parts = value.split('=', limit = 2)
                    require(parts.size == 2) {
                        "module variant must use GradlePath=variant: $value"
                    }
                    val gradlePath = parse(parts[0]).gradlePath
                    val variant = parts[1].trim()
                    require(variant.isNotEmpty()) { "module variant must not be empty: $value" }
                    gradlePath to variant
                }
                val knownPaths = requests.mapTo(mutableSetOf()) { it.gradlePath }
                val unknownPaths = overrides.keys - knownPaths
                require(unknownPaths.isEmpty()) {
                    "module variant specified for unwatched module(s): ${unknownPaths.joinToString()}"
                }
                return requests.map { request ->
                    request.copy(variant = overrides[request.gradlePath])
                }
            }
        }
    }

    enum class Layout { AGP_BUILT_IN, AGP_KGP, JVM }

    val name: String = request.displayName
    val dir: Path = projectDir.resolve(request.relativeDir)
    val gradlePath: String = request.gradlePath
    val compileTask: String = when (layout) {
        Layout.AGP_BUILT_IN, Layout.AGP_KGP -> "$gradlePath:compile${variant.taskSegment()}Kotlin"
        Layout.JVM -> "$gradlePath:compileKotlin"
    }

    val sourceRoots: List<Path> = sourceSetNames(variant).flatMap { sourceSet ->
        listOf(dir.resolve("src/$sourceSet/kotlin"), dir.resolve("src/$sourceSet/java"))
    }

    /** Resource roots ordered from general to variant-specific. */
    val resDirs: List<Path> = if (layout == Layout.JVM) {
        emptyList()
    } else {
        sourceSetNames(variant).map { dir.resolve("src/$it/res") }
    }

    val assembleTask: String = "$gradlePath:assemble${variant.taskSegment()}"
    val installTask: String = "$gradlePath:install${variant.taskSegment()}"
    val apkOutputDirs: List<Path> = run {
        val buildType = listOf("debug", "release").firstOrNull {
            variant.endsWith(it, ignoreCase = true)
        }
        val flavor = buildType?.let { variant.dropLast(it.length) }
            ?.takeIf { it.isNotEmpty() }
            ?.replaceFirstChar(Char::lowercaseChar)
        listOfNotNull(
            flavor?.let { dir.resolve("build/outputs/apk/$it/$buildType") },
            dir.resolve("build/outputs/apk/$variant"),
            dir.resolve("build/outputs/apk"),
        ).distinct()
    }

    companion object {
        /**
         * Probe both AGP 9 built-in Kotlin output and the standalone KGP output used by
         * AGP 8 projects. Must run after the initial compilation.
         */
        fun probe(projectDir: Path, request: Request, variant: String): ModuleSpec {
            val moduleVariant = request.variant ?: variant
            val moduleDir = projectDir.resolve(request.relativeDir)
            val taskSegment = moduleVariant.taskSegment()
            val candidates = listOf(
                Layout.AGP_BUILT_IN to moduleDir.resolve(
                    "build/intermediates/built_in_kotlinc/$moduleVariant/compile${taskSegment}Kotlin/classes",
                ),
                Layout.AGP_KGP to moduleDir.resolve("build/tmp/kotlin-classes/$moduleVariant"),
                Layout.JVM to moduleDir.resolve("build/classes/kotlin/main"),
            )
            val (layout, classesDir) = candidates.firstOrNull { Files.isDirectory(it.second) }
                ?: throw IllegalStateException(
                    "module '${request.gradlePath}' (${request.relativeDir}) has no compiled classes under " +
                        candidates.joinToString { it.second.toString() } +
                        " — is it in the app's dependency graph and does it contain Kotlin sources?",
                )
            return ModuleSpec(request, layout, moduleVariant, projectDir, classesDir)
        }

        internal fun sourceSetNames(variant: String): List<String> {
            val buildType = listOf("debug", "release").firstOrNull {
                variant.endsWith(it, ignoreCase = true)
            }
            val flavor = buildType?.let { variant.dropLast(it.length) }?.takeIf { it.isNotEmpty() }
            return listOfNotNull("main", flavor?.replaceFirstChar(Char::lowercaseChar), buildType, variant)
                .distinct()
        }
    }
}

internal fun String.taskSegment(): String = replaceFirstChar(Char::uppercaseChar)
