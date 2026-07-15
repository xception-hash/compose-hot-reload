package dev.hotreload.engine

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ProjectConfigTest {

    @Test
    fun selectAppModuleReordersToMatchedEntry() {
        val a = ModuleSpec.Request.parse("a")
        val b = ModuleSpec.Request.parse("b")
        val result = ProjectConfig.selectAppModule(listOf(a, b), ":b", null)
        assertEquals(listOf(b, a), result)
    }

    @Test
    fun selectAppModuleAcceptsBareAndColonPrefixedPaths() {
        val app = ModuleSpec.Request.parse("app")
        val other = ModuleSpec.Request.parse("other")

        val viaBare = ProjectConfig.selectAppModule(listOf(other, app), "app", null)
        assertEquals(listOf(app, other), viaBare)

        val viaColon = ProjectConfig.selectAppModule(listOf(other, app), ":app", null)
        assertEquals(listOf(app, other), viaColon)
    }

    @Test
    fun selectAppModuleRejectsUnknownPath() {
        val a = ModuleSpec.Request.parse("a")
        val b = ModuleSpec.Request.parse("b")
        val e = assertFailsWith<IllegalArgumentException> {
            ProjectConfig.selectAppModule(listOf(a, b), ":nope", null)
        }
        assertTrue(":nope" in (e.message ?: ""), "message should name the unresolved path: ${e.message}")
    }

    @Test
    fun selectAppModuleDirOverridesOnlySelectedAppModule() {
        val a = ModuleSpec.Request.parse("a")
        val b = ModuleSpec.Request.parse("b")
        val result = ProjectConfig.selectAppModule(listOf(a, b), ":b", "custom/dir")
        assertEquals(listOf(b.copy(relativeDir = "custom/dir"), a), result)
    }

    @Test
    fun selectAppModuleBothNullReturnsIdenticalList() {
        val modules = listOf(ModuleSpec.Request.parse("a"), ModuleSpec.Request.parse("b"))
        val result = ProjectConfig.selectAppModule(modules, null, null)
        assertEquals(modules, result)
        assertSame(modules, result)
    }

    @Test
    fun blankVariantRejected() {
        assertFailsWith<IllegalArgumentException> {
            ProjectConfig(
                projectDir = Path.of("."),
                modules = listOf(ModuleSpec.Request.parse("app")),
                applicationId = "dev.hotreload.test",
                variant = "  ",
            )
        }
    }

    @Test
    fun emptyModulesRejected() {
        assertFailsWith<IllegalArgumentException> {
            ProjectConfig(
                projectDir = Path.of("."),
                modules = emptyList(),
                applicationId = "dev.hotreload.test",
            )
        }
    }

    // ---- T33d: applicationId / launchActivity / deviceSerial validation ----

    @Test
    fun applicationIdWithShellMetacharacterRejected() {
        val e = assertFailsWith<IllegalArgumentException> {
            ProjectConfig(
                projectDir = Path.of("."),
                modules = listOf(ModuleSpec.Request.parse("app")),
                applicationId = "foo;id",
            )
        }
        assertTrue("applicationId" in (e.message ?: ""))
    }

    @Test
    fun normalApplicationIdAccepted() {
        val config = ProjectConfig(
            projectDir = Path.of("."),
            modules = listOf(ModuleSpec.Request.parse("app")),
            applicationId = "com.example.app.debug",
        )
        assertEquals("com.example.app.debug", config.applicationId)
    }

    @Test
    fun launchActivityMainActivityAccepted() {
        val config = ProjectConfig(
            projectDir = Path.of("."),
            modules = listOf(ModuleSpec.Request.parse("app")),
            applicationId = "com.example.app",
            launchActivity = ".MainActivity",
        )
        assertEquals(".MainActivity", config.launchActivity)
    }

    @Test
    fun launchActivityOuterDollarInnerAccepted() {
        val config = ProjectConfig(
            projectDir = Path.of("."),
            modules = listOf(ModuleSpec.Request.parse("app")),
            applicationId = "com.example.app",
            launchActivity = "Outer\$Inner",
        )
        assertEquals("Outer\$Inner", config.launchActivity)
    }

    @Test
    fun launchActivityWithSpaceRejected() {
        val e = assertFailsWith<IllegalArgumentException> {
            ProjectConfig(
                projectDir = Path.of("."),
                modules = listOf(ModuleSpec.Request.parse("app")),
                applicationId = "com.example.app",
                launchActivity = "foo bar",
            )
        }
        assertTrue("launchActivity" in (e.message ?: ""))
    }

    @Test
    fun defaultsDeviceSerialAndLaunchActivityNull() {
        val config = ProjectConfig(
            projectDir = Path.of("."),
            modules = listOf(ModuleSpec.Request.parse("app")),
            applicationId = "com.example.app",
        )
        assertEquals(null, config.deviceSerial)
        assertEquals(null, config.launchActivity)
        assertEquals(IntegrationMode.CONFIGURED, config.integrationMode)
    }

    @Test
    fun zeroTouchIntegrationModeAccepted() {
        val config = ProjectConfig(
            projectDir = Path.of("."),
            modules = listOf(ModuleSpec.Request.parse("app")),
            applicationId = "com.example.app",
            integrationMode = IntegrationMode.ZERO_TOUCH,
        )
        assertEquals(IntegrationMode.ZERO_TOUCH, config.integrationMode)
    }

    @Test
    fun reservedBootstrapGradlePropertiesRejectedInAllSupportedForms() {
        val forms = listOf(
            listOf("-Pdev.hotreload.bootstrap.jar=/tmp/plugin.jar"),
            listOf("-P", "dev.hotreload.bootstrap.runtimeAar=/tmp/runtime.aar"),
            listOf("--project-prop=dev.hotreload.bootstrap.modules=:app"),
            listOf("--project-prop", "dev.hotreload.bootstrap.appModule=:app"),
            listOf("-Dorg.gradle.project.dev.hotreload.bootstrap.variant=debug"),
            listOf("-D", "org.gradle.project.dev.hotreload.bootstrap.variant=debug"),
            listOf("--system-prop=org.gradle.project.dev.hotreload.bootstrap.variant=debug"),
            listOf("--system-prop", "org.gradle.project.dev.hotreload.bootstrap.variant=debug"),
        )

        for (args in forms) {
            val error = assertFailsWith<IllegalArgumentException> {
                validateUserGradleArgs(args)
            }
            assertTrue("dev.hotreload.bootstrap." in (error.message ?: ""), "args=$args: ${error.message}")
        }
    }

    @Test
    fun similarlyNamedGradlePropertyIsNotReserved() {
        validateUserGradleArgs(listOf("-Pdev.hotreload.bootstrapper.value=true"))
    }

    @Test
    fun projectConfigRejectsReservedBootstrapGradleProperty() {
        assertFailsWith<IllegalArgumentException> {
            ProjectConfig(
                projectDir = Path.of("."),
                modules = listOf(ModuleSpec.Request.parse("app")),
                applicationId = "com.example.app",
                gradleArgs = listOf("-Pdev.hotreload.bootstrap.jar=/tmp/plugin.jar"),
            )
        }
    }
}
