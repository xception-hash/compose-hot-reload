package dev.hotreload.gradle

import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.GradleException

/**
 * Zero-config Gradle plugin for Compose Hot Reload.
 *
 * Applicable to three module types, keyed off whichever plugin is applied:
 * - `com.android.application`: the device runtime lives here — adds a
 *   `debugImplementation` on runtime-client, sets `jniLibs.useLegacyPackaging = true`
 *   (needed for JVMTI agent loading), and the deterministic-class-shape compiler flags
 *   (plus FunctionKeyMeta emission).
 * - `com.android.library` and `org.jetbrains.kotlin.jvm`: the SAME class-shape compiler
 *   flags only — no runtime-client dependency (the on-device runtime belongs to the app
 *   module alone).
 *
 * Applying it to any other module type fails the build with a message naming the project.
 */
class HotReloadPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        // Deterministic class shapes: `class` lambdas/SAM conversions and inline string
        // concat (no invokedynamic / makeConcatWithConstants) so patched classes redefine
        // cleanly. Applied to every supported module type.
        val classShapeFlags = listOf(
            "-Xlambdas=class",
            "-Xsam-conversions=class",
            "-Xstring-concat=inline",
        )

        // Application module: device runtime + packaging + flags (incl. FunctionKeyMeta).
        project.pluginManager.withPlugin("com.android.application") {
            project.dependencies.add(
                "debugImplementation",
                "dev.hotreload:runtime-client:0.1"
            )

            val android = project.extensions.getByType(ApplicationExtension::class.java)
            android.packaging.jniLibs.useLegacyPackaging = true

            addFreeCompilerArgs(
                project,
                listOf(
                    "-P",
                    "plugin:androidx.compose.compiler.plugins.kotlin:generateFunctionKeyMetaAnnotations=true",
                ) + classShapeFlags,
            )
        }

        // Library and pure-JVM modules: class-shape flags only, no device runtime.
        project.pluginManager.withPlugin("com.android.library") {
            addFreeCompilerArgs(project, classShapeFlags)
        }
        project.pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
            addFreeCompilerArgs(project, classShapeFlags)
        }

        // Fail fast on an unsupported module type.
        project.afterEvaluate {
            val supported = listOf(
                "com.android.application",
                "com.android.library",
                "org.jetbrains.kotlin.jvm",
            )
            if (supported.none { project.plugins.hasPlugin(it) }) {
                throw GradleException(
                    "The dev.hotreload plugin on project '${project.path}' requires one of " +
                        "com.android.application, com.android.library, or org.jetbrains.kotlin.jvm " +
                        "to be applied; none was found."
                )
            }
        }
    }

    /**
     * Append [args] to the module's `kotlin { compilerOptions { freeCompilerArgs } }`.
     * Accessed reflectively by name so the plugin needs no compile-time dependency on the
     * standalone KGP — the same `kotlin` extension is provided by AGP's built-in Kotlin
     * (application/library) and by `org.jetbrains.kotlin.jvm`.
     */
    private fun addFreeCompilerArgs(project: Project, args: List<String>) {
        project.extensions.findByName("kotlin")?.let { kotlinExt ->
            val compilerOptions = kotlinExt.javaClass
                .getMethod("getCompilerOptions")
                .invoke(kotlinExt)
            val freeCompilerArgs = compilerOptions.javaClass
                .getMethod("getFreeCompilerArgs")
                .invoke(compilerOptions)
            val addAll = freeCompilerArgs.javaClass
                .getMethod("addAll", Iterable::class.java)
            addAll.invoke(freeCompilerArgs, args)
        }
    }
}
