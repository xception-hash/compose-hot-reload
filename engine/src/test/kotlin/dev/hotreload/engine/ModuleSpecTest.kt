package dev.hotreload.engine

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ModuleSpecTest {
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
}
