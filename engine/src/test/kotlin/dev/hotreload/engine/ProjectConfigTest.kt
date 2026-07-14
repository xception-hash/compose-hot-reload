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
}
