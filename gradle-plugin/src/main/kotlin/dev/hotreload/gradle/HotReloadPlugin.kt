package dev.hotreload.gradle

import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.GradleException

/**
 * Zero-config Gradle plugin for Compose Hot Reload.
 *
 * Applied to an Android application module, this plugin automatically configures:
 * 1. debugImplementation dependency on runtime-client
 * 2. jniLibs.useLegacyPackaging = true (needed for JVMTI agent loading)
 * 3. Kotlin free compiler args for deterministic class shapes and FunctionKeyMeta emission
 */
class HotReloadPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        // Fail fast if not an Android application module.
        project.afterEvaluate {
            if (!project.plugins.hasPlugin("com.android.application")) {
                throw GradleException(
                    "The dev.hotreload plugin requires the com.android.application plugin " +
                        "to be applied first."
                )
            }
        }

        project.plugins.withId("com.android.application") {
            // 1. Add runtime-client as a debugImplementation dependency.
            project.dependencies.add(
                "debugImplementation",
                "dev.hotreload:runtime-client:0.1"
            )

            // 2. Configure jniLibs.useLegacyPackaging = true.
            val android = project.extensions.getByType(ApplicationExtension::class.java)
            android.packaging.jniLibs.useLegacyPackaging = true

            // 3. Configure Kotlin free compiler args via the built-in kotlin extension.
            //    AGP 9 provides a kotlin {} extension on Android projects; access it by name
            //    to avoid a compile-time dependency on the standalone KGP.
            project.extensions.findByName("kotlin")?.let { kotlinExt ->
                // kotlin { compilerOptions { freeCompilerArgs.addAll(...) } }
                val compilerOptions = kotlinExt.javaClass
                    .getMethod("getCompilerOptions")
                    .invoke(kotlinExt)
                val freeCompilerArgs = compilerOptions.javaClass
                    .getMethod("getFreeCompilerArgs")
                    .invoke(compilerOptions)
                @Suppress("UNCHECKED_CAST")
                val addAll = freeCompilerArgs.javaClass
                    .getMethod("addAll", Iterable::class.java)
                addAll.invoke(
                    freeCompilerArgs,
                    listOf(
                        "-P",
                        "plugin:androidx.compose.compiler.plugins.kotlin:generateFunctionKeyMetaAnnotations=true",
                        "-Xlambdas=class",
                        "-Xsam-conversions=class",
                        "-Xstring-concat=inline",
                    )
                )
            }
        }
    }
}
