package dev.hotreload.gradle

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.GradleException

/**
 * Zero-config Gradle plugin for Compose Hot Reload.
 *
 * Applicable to three module types, keyed off whichever plugin is applied:
 * - `com.android.application`: the device runtime lives here — wires runtime-client into
 *   every debuggable build type's `<name>Implementation` configuration, sets
 *   `jniLibs.useLegacyPackaging = true` (needed for JVMTI agent loading), and the
 *   deterministic-class-shape compiler flags.
 * - `com.android.library` and `org.jetbrains.kotlin.jvm`: the SAME class-shape compiler
 *   flags only — no runtime-client dependency (the on-device runtime belongs to the app
 *   module alone).
 * - every module applying the Compose compiler plugin: FunctionKeyMeta emission, so changed
 *   composable groups can be invalidated in configured mode just as they are in zero-touch.
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

        // Set to true the moment the class-shape flags actually reach the `kotlin`
        // extension. If a supported plugin is applied but this stays false, the flags
        // silently went nowhere (apply-order or an unsupported Kotlin setup) and we fail
        // the build in afterEvaluate rather than let watch-time redefine mysteriously break.
        var flagsApplied = false

        // FunctionKeyMeta is what lets the engine invalidate the exact changed Compose groups.
        // Apply it to every Compose module, including Android libraries: zero-touch already does
        // this, and relying on a compiler version's default leaves configured library edits with
        // no stable invalidation key on older production targets.
        var composeMetadataApplied = false
        project.pluginManager.withPlugin("org.jetbrains.kotlin.plugin.compose") {
            if (addFreeCompilerArgs(
                    project,
                    listOf(
                        "-P",
                        "plugin:androidx.compose.compiler.plugins.kotlin:generateFunctionKeyMetaAnnotations=true",
                    ),
                )
            ) composeMetadataApplied = true
        }

        // Opt-in (T24): `-Photreload.liveLiterals=true` turns on the Compose compiler's
        // live-literals v2 codegen so string/number/boolean constants inside composables
        // become `LiveLiterals$*Kt` helpers we can update in place (sub-100ms path). Off by
        // default because the instrumentation adds debug-build overhead. Verified option name
        // (Kotlin 2.4 built-in Compose compiler): `liveLiteralsEnabled` = LIVE_LITERALS_V2.
        val liveLiteralsOn = project.findProperty("hotreload.liveLiterals") == "true"
        val liveLiteralsFlags =
            if (liveLiteralsOn) {
                listOf(
                    "-P",
                    "plugin:androidx.compose.compiler.plugins.kotlin:liveLiteralsEnabled=true",
                )
            } else {
                emptyList()
            }

        // Application module: device runtime + packaging + flags (incl. FunctionKeyMeta).
        project.pluginManager.withPlugin("com.android.application") {
            // Group matches what JitPack serves (com.github.<user>.<repo>) AND what the
            // samples' composite build substitutes on — one coordinate for both paths.
            // Version must equal the release git tag verbatim (JitPack version == tag).
            // Shared by the build-type wiring below and the release tripwire's group/name
            // check so the coordinate is never duplicated.
            val runtimeCoord = "com.github.xception-hash.compose-hot-reload:runtime-client:0.2.0"
            val (runtimeGroup, runtimeName) = runtimeCoord.split(":")

            val android = project.extensions.getByType(ApplicationExtension::class.java)
            android.packaging.jniLibs.useLegacyPackaging = true

            // Shared by the finalizeDsl wiring below and the onVariants tripwire.
            val androidComponents =
                project.extensions.getByType(ApplicationAndroidComponentsExtension::class.java)

            // Wire runtime-client into every debuggable build type's `<name>Implementation`
            // configuration (AGP creates one per build type). Must run in finalizeDsl:
            // build types are final there, whereas `onVariants` fires too late to add to
            // `<name>Implementation` configurations. Flavored variants inherit build-type
            // configs, so flavors need nothing extra.
            androidComponents.finalizeDsl { ext ->
                ext.buildTypes.forEach { bt ->
                    // JaCoCo instruments classes while packaging, after Kotlin writes the
                    // class directory the engine watches. A patch compiled from that directory
                    // then lacks JaCoCo's synthetic members (for example `$jacocoInit`), and
                    // ART rejects an otherwise body-only redefine because the installed and
                    // patch method shapes differ. Hot reload is debug-only, so coverage must be
                    // disabled before variants finalize, including when a convention plugin
                    // enabled it in the target build.
                    bt.enableAndroidTestCoverage = false
                    bt.enableUnitTestCoverage = false
                    if (!bt.isDebuggable) return@forEach
                    project.dependencies.add("${bt.name}Implementation", runtimeCoord)
                }
            }

            if (addFreeCompilerArgs(
                    project,
                    classShapeFlags + liveLiteralsFlags,
                )
            ) flagsApplied = true

            // Release-variant tripwire (T32 item 1): defense-in-depth on top of the
            // per-debuggable-build-type wiring above and the AAR's own runtime
            // debuggable-guard (the last line). If runtime-client ever lands on a
            // NON-debuggable variant's runtime classpath — a hand-added
            // `implementation`/`releaseImplementation` is exactly the mistake this catches
            // — fail configuration with a clear message rather than ship the
            // arbitrary-code injection surface in a release build.
            //
            // Uses the AGP variant API: `onVariants` (configureEach-style — sees late
            // variants, unlike a `configurations.forEach` at afterEvaluate) and the
            // variant-API `debuggable` boolean (NOT a name-based `!debug` match, which
            // false-positives on custom debuggable build types). Inspects the variant's
            // declared runtime dependencies (no resolution) by group + name.
            androidComponents.onVariants { variant ->
                if (variant.debuggable) return@onVariants
                val onReleaseClasspath = variant.runtimeConfiguration.allDependencies.any {
                    it.group == runtimeGroup && it.name == runtimeName
                }
                if (onReleaseClasspath) {
                    throw GradleException(
                        "The dev.hotreload plugin on project '${project.path}' found " +
                            "com.github.xception-hash.compose-hot-reload:runtime-client on the " +
                            "runtime classpath of the non-debuggable variant '${variant.name}'. " +
                            "The hot-reload runtime opens an on-device code-injection surface and " +
                            "must never ship in a release/non-debuggable build. Remove the " +
                            "runtime-client dependency from this variant — the plugin already wires " +
                            "it into every debuggable build type automatically."
                    )
                }
            }
        }

        // Library and pure-JVM modules: class-shape flags only, no device runtime.
        project.pluginManager.withPlugin("com.android.library") {
            if (addFreeCompilerArgs(project, classShapeFlags)) flagsApplied = true
        }
        project.pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
            if (addFreeCompilerArgs(project, classShapeFlags)) flagsApplied = true
        }

        // Fail fast on an unsupported module type.
        project.afterEvaluate {
            if (project.plugins.hasPlugin("org.jetbrains.kotlin.plugin.compose") && !composeMetadataApplied) {
                // The Compose plugin can be applied after dev.hotreload. Retry once after project
                // evaluation, matching the zero-touch bootstrap's apply-order tolerance.
                composeMetadataApplied = addFreeCompilerArgs(
                    project,
                    listOf(
                        "-P",
                        "plugin:androidx.compose.compiler.plugins.kotlin:generateFunctionKeyMetaAnnotations=true",
                    ),
                )
            }
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
            // A supported plugin is applied, but the class-shape flags never landed: the
            // `kotlin` extension was absent when the plugin-applied hook ran. Without these
            // flags patched classes fail to redefine at watch time with confusing errors, so
            // stop the build now.
            if (!flagsApplied) {
                throw GradleException(
                    "The dev.hotreload plugin on project '${project.path}' could not apply its " +
                        "Kotlin compiler flags: the `kotlin` extension was not found. This usually " +
                        "means dev.hotreload was applied before the Android/Kotlin plugin, or the " +
                        "module uses an unsupported Kotlin setup (e.g. AGP without built-in Kotlin). " +
                        "Apply dev.hotreload after the Android/Kotlin plugin."
                )
            }
            if (project.plugins.hasPlugin("org.jetbrains.kotlin.plugin.compose") && !composeMetadataApplied) {
                throw GradleException(
                    "The dev.hotreload plugin on project '${project.path}' could not enable " +
                        "Compose FunctionKeyMeta generation. Apply dev.hotreload after the " +
                        "Android/Kotlin and Compose plugins.",
                )
            }
        }
    }

    /**
     * Append [args] to the module's `kotlin { compilerOptions { freeCompilerArgs } }`.
     * Accessed reflectively by name so the plugin needs no compile-time dependency on the
     * standalone KGP — the same `kotlin` extension is provided by AGP's built-in Kotlin
     * (application/library) and by `org.jetbrains.kotlin.jvm`.
     *
     * Returns true if the flags were applied; false if the `kotlin` extension was absent
     * (so the caller can fail loud rather than ship a build missing the flags).
     */
    private fun addFreeCompilerArgs(project: Project, args: List<String>): Boolean {
        val kotlinExt = project.extensions.findByName("kotlin") ?: return false
        val compilerOptions = kotlinExt.javaClass
            .getMethod("getCompilerOptions")
            .invoke(kotlinExt)
        val freeCompilerArgs = compilerOptions.javaClass
            .getMethod("getFreeCompilerArgs")
            .invoke(compilerOptions)
        val addAll = freeCompilerArgs.javaClass
            .getMethod("addAll", Iterable::class.java)
        addAll.invoke(freeCompilerArgs, args)
        return true
    }
}
