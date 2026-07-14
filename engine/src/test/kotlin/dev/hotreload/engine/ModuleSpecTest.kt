package dev.hotreload.engine

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class ModuleSpecTest {
    @TempDir
    lateinit var tempDir: Path
    @Test
    fun parsesMappedModule() {
        val request = ModuleSpec.Request.parse(":mega-app=layers/mega-app")

        assertEquals(":mega-app", request.gradlePath)
        assertEquals("layers/mega-app", request.relativeDir)
    }

    @Test
    fun probesStandaloneKgpVariantOutput() {
        val root = Files.createTempDirectory("module-spec")
        try {
            val request = ModuleSpec.Request.parse(":app=applications/main")
            val classes = root.resolve("applications/main/build/tmp/kotlin-classes/stageDebug")
            Files.createDirectories(classes)

            val spec = ModuleSpec.probe(root, request, "stageDebug")

            assertEquals(ModuleSpec.Layout.AGP_KGP, spec.layout)
            assertEquals(":app:compileStageDebugKotlin", spec.compileTask)
            assertEquals(":app:assembleStageDebug", spec.assembleTask)
            assertEquals(classes, spec.classesDir)
            assertEquals(
                listOf("main", "stage", "debug", "stageDebug"),
                ModuleSpec.sourceSetNames("stageDebug"),
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun appliesPerModuleVariantOverride() {
        val requests = listOf(
            ModuleSpec.Request.parse(":mega-app=layers/mega-app"),
            ModuleSpec.Request.parse(":feature-x=layers/shared/feature-x"),
        )

        val overridden = ModuleSpec.Request.applyVariantOverrides(
            requests,
            listOf(":feature-x=vendorProductionDebug"),
        )

        assertEquals(null, overridden[0].variant)
        assertEquals("vendorProductionDebug", overridden[1].variant)
    }

    @Test
    fun rejectsVariantOverrideForUnwatchedModule() {
        assertFailsWith<IllegalArgumentException> {
            ModuleSpec.Request.applyVariantOverrides(
                listOf(ModuleSpec.Request.parse("app")),
                listOf(":feature-x=vendorProductionDebug"),
            )
        }
    }

    @Test
    fun probesOverriddenModuleVariantOutput() {
        val root = Files.createTempDirectory("module-spec-override")
        try {
            val request = ModuleSpec.Request.applyVariantOverrides(
                listOf(ModuleSpec.Request.parse(":feature-x=modules/feature-x")),
                listOf(":feature-x=vendorProductionDebug"),
            ).single()
            val classes = root.resolve("modules/feature-x/build/tmp/kotlin-classes/vendorProductionDebug")
            Files.createDirectories(classes)

            val spec = ModuleSpec.probe(root, request, "stageDebug")

            assertEquals("vendorProductionDebug", spec.variant)
            assertEquals(classes, spec.classesDir)
            assertEquals(":feature-x:compileVendorProductionDebugKotlin", spec.compileTask)
            assertEquals(
                listOf("main", "vendorProduction", "debug", "vendorProductionDebug"),
                ModuleSpec.sourceSetNames(spec.variant),
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun probePicksMetadataClassesDirOverConventions() {
        val root = tempDir
        val request = ModuleSpec.Request.parse(":app=app")

        val metaDir = root.resolve("app/build/metadata-classes")
        Files.createDirectories(metaDir)
        Files.writeString(metaDir.resolve("Example.class"), "mock bytecode")

        val conventionDir = root.resolve("app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes")
        Files.createDirectories(conventionDir)
        Files.writeString(conventionDir.resolve("Convention.class"), "mock bytecode")

        val metadata = ModuleMetadata(
            classOutputDirs = listOf("app/build/metadata-classes"),
            compileTask = ":app:compileDebugKotlin"
        )

        val spec = ModuleSpec.probe(root, request, "debug", metadata)

        assertEquals(metaDir, spec.classesDir)
        assertEquals(ModuleSpec.Layout.AGP_BUILT_IN, spec.layout)
    }

    @Test
    fun metadataDirEmptyFallsBackToConventions() {
        val root = tempDir
        val request = ModuleSpec.Request.parse(":app=app")

        val metaDir = root.resolve("app/build/metadata-classes")
        Files.createDirectories(metaDir)

        val conventionDir = root.resolve("app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes")
        Files.createDirectories(conventionDir)
        Files.writeString(conventionDir.resolve("Convention.class"), "mock bytecode")

        val metadata = ModuleMetadata(
            classOutputDirs = listOf("app/build/metadata-classes"),
            compileTask = ":app:compileDebugKotlin"
        )

        val out = java.io.ByteArrayOutputStream()
        val oldOut = System.out
        System.setOut(java.io.PrintStream(out))
        try {
            val spec = ModuleSpec.probe(root, request, "debug", metadata)
            assertEquals(conventionDir, spec.classesDir)
            assertTrue(out.toString().contains("metadata: :app classes dirs missing on disk — using convention probe"))
        } finally {
            System.setOut(oldOut)
        }
    }

    @Test
    fun metadataAllDirsMissingFallsBackToConventionsAndPrints() {
        val root = tempDir
        val request = ModuleSpec.Request.parse(":app=app")

        val conventionDir = root.resolve("app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes")
        Files.createDirectories(conventionDir)
        Files.writeString(conventionDir.resolve("Convention.class"), "mock bytecode")

        val metadata = ModuleMetadata(
            classOutputDirs = listOf("app/build/non-existent-metadata-classes"),
            compileTask = ":app:compileDebugKotlin"
        )

        val out = java.io.ByteArrayOutputStream()
        val oldOut = System.out
        System.setOut(java.io.PrintStream(out))
        try {
            val spec = ModuleSpec.probe(root, request, "debug", metadata)
            assertEquals(conventionDir, spec.classesDir)
            assertTrue(out.toString().contains("metadata: :app classes dirs missing on disk — using convention probe"))
        } finally {
            System.setOut(oldOut)
        }
    }

    @Test
    fun metadataOverridesEverything() {
        val root = tempDir
        val request = ModuleSpec.Request.parse(":app=app")

        val metaDir = root.resolve("app/build/metadata-classes")
        Files.createDirectories(metaDir)
        Files.writeString(metaDir.resolve("Example.class"), "mock bytecode")

        val metadata = ModuleMetadata(
            classOutputDirs = listOf("app/build/metadata-classes"),
            compileTask = ":custom:compileTask",
            assembleTask = ":custom:assembleTask",
            installTask = ":custom:installTask",
            sourceDirs = listOf("app/src/custom-sources"),
            resDirs = listOf("app/src/custom-res"),
            apkOutputDir = "app/build/custom-apk-out"
        )

        val spec = ModuleSpec.probe(root, request, "debug", metadata)

        assertEquals(":custom:compileTask", spec.compileTask)
        assertEquals(":custom:assembleTask", spec.assembleTask)
        assertEquals(":custom:installTask", spec.installTask)
        assertEquals(listOf(root.resolve("app/src/custom-sources")), spec.sourceRoots)
        assertEquals(listOf(root.resolve("app/src/custom-res")), spec.resDirs)
        assertTrue(spec.apkOutputDirs.contains(root.resolve("app/build/custom-apk-out")))
    }
}
