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
    private val metadata: ModuleMetadata? = null,
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
    val compileTask: String = metadata?.compileTask ?: when (layout) {
        Layout.AGP_BUILT_IN, Layout.AGP_KGP -> "$gradlePath:compile${variant.taskSegment()}Kotlin"
        Layout.JVM -> "$gradlePath:compileKotlin"
    }

    val sourceRoots: List<Path> = metadata?.sourceDirs?.takeIf { it.isNotEmpty() }
        ?.map { projectDir.resolve(it) }
        ?: sourceSetNames(variant).flatMap { sourceSet ->
            listOf(dir.resolve("src/$sourceSet/kotlin"), dir.resolve("src/$sourceSet/java"))
        }

    /** Resource roots ordered from general to variant-specific. */
    val resDirs: List<Path> = metadata?.resDirs?.takeIf { it.isNotEmpty() }
        ?.map { projectDir.resolve(it) }
        ?: if (layout == Layout.JVM) {
            emptyList()
        } else {
            sourceSetNames(variant).map { dir.resolve("src/$it/res") }
        }

    val assembleTask: String = metadata?.assembleTask ?: "$gradlePath:assemble${variant.taskSegment()}"
    val installTask: String = metadata?.installTask ?: "$gradlePath:install${variant.taskSegment()}"
    val apkOutputDirs: List<Path> = run {
        val buildType = listOf("debug", "release").firstOrNull {
            variant.endsWith(it, ignoreCase = true)
        }
        val flavor = buildType?.let { variant.dropLast(it.length) }
            ?.takeIf { it.isNotEmpty() }
            ?.replaceFirstChar(Char::lowercaseChar)
        val conventionList = listOfNotNull(
            flavor?.let { dir.resolve("build/outputs/apk/$it/$buildType") },
            dir.resolve("build/outputs/apk/$variant"),
            dir.resolve("build/outputs/apk"),
        )
        val prepended = listOfNotNull(metadata?.apkOutputDir?.let { projectDir.resolve(it) }) + conventionList
        prepended.distinct()
    }

    companion object {
        /**
         * Probe both AGP 9 built-in Kotlin output and the standalone KGP output used by
         * AGP 8 projects. Must run after the initial compilation.
         */
        fun probe(
            projectDir: Path,
            request: Request,
            variant: String,
            metadata: ModuleMetadata? = null,
        ): ModuleSpec {
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

            var chosenLayout: Layout? = null
            var chosenClassesDir: Path? = null
            val metadataDirsTried = mutableListOf<Path>()

            if (metadata != null && metadata.classOutputDirs.isNotEmpty()) {
                val resolvedMetadataDirs = metadata.classOutputDirs.map { projectDir.resolve(it) }
                metadataDirsTried.addAll(resolvedMetadataDirs)
                val foundDir = resolvedMetadataDirs.firstOrNull { dir ->
                    Files.isDirectory(dir) && Files.walk(dir).use { stream ->
                        stream.anyMatch { Files.isRegularFile(it) && it.fileName?.toString()?.endsWith(".class") == true }
                    }
                }
                if (foundDir != null) {
                    chosenClassesDir = foundDir
                    val matchingCandidate = candidates.firstOrNull { it.second == foundDir }
                    chosenLayout = if (matchingCandidate != null) {
                        matchingCandidate.first
                    } else {
                        // layout when a metadata dir wins: the layout of the convention candidate equal
                        // to the chosen dir if any; else by discovered type — androidApp/androidLib ->
                        // AGP_BUILT_IN, kotlinJvm -> JVM.
                        // (Layout then only feeds convention fallbacks for task names — the type-derived
                        // value picks the right naming family; document this in a comment.)
                        if (metadata.compileTask != null || metadata.assembleTask != null || metadata.installTask != null || metadata.resDirs.isNotEmpty()) {
                            Layout.AGP_BUILT_IN
                        } else {
                            Layout.JVM
                        }
                    }
                } else {
                    println("metadata: ${request.gradlePath} classes dirs missing on disk — using convention probe")
                }
            }

            if (chosenClassesDir == null || chosenLayout == null) {
                val found = candidates.firstOrNull { Files.isDirectory(it.second) }
                if (found != null) {
                    chosenLayout = found.first
                    chosenClassesDir = found.second
                } else {
                    val allTried = candidates.map { it.second } + metadataDirsTried
                    throw IllegalStateException(
                        "module '${request.gradlePath}' (${request.relativeDir}) has no compiled classes under " +
                            allTried.joinToString { it.toString() } +
                            " — is it in the app's dependency graph and does it contain Kotlin sources?",
                    )
                }
            }

            return ModuleSpec(request, chosenLayout, moduleVariant, projectDir, chosenClassesDir, metadata)
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
