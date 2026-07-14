package dev.hotreload.engine

import com.google.gson.Gson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure-JVM tests (no Gradle invocation) for the T33c discovery model: parses the committed
 * fixture `inspect-fixture.json` and exercises [DiscoveryReport.suggestedWatchCommand].
 */
class GradleDiscoveryTest {

    private fun loadFixture(): DiscoveryReport {
        val text = requireNotNull(
            javaClass.getResourceAsStream("/dev/hotreload/inspect-fixture.json"),
        ) { "inspect-fixture.json missing from test resources" }.use { it.readBytes().toString(Charsets.UTF_8) }
        return Gson().fromJson(text, DiscoveryReport::class.java)
    }

    private fun loadMultiAppFixture(): DiscoveryReport {
        val text = requireNotNull(
            javaClass.getResourceAsStream("/dev/hotreload/inspect-fixture-multiapp.json"),
        ) { "inspect-fixture-multiapp.json missing from test resources" }.use { it.readBytes().toString(Charsets.UTF_8) }
        return Gson().fromJson(text, DiscoveryReport::class.java)
    }

    @Test
    fun `fixture parses with types variants tasks and deps in the right fields`() {
        val report = loadFixture()
        assertEquals(1, report.schemaVersion)
        assertEquals("9.6.1", report.gradleVersion)
        assertEquals("fixture-project", report.rootProjectName)

        val byPath = report.projects.orEmpty().associateBy { it.gradlePath }
        assertEquals("androidApp", byPath[":app"]?.type)
        assertEquals("androidLib", byPath[":feature"]?.type)
        assertEquals("kotlinJvm", byPath[":core"]?.type)

        val appVariants = byPath[":app"]?.variants.orEmpty().associateBy { it.name }
        assertEquals(false, appVariants["release"]?.debuggable)
        assertEquals(true, appVariants["stageDebug"]?.debuggable)
        assertEquals(listOf("stage"), appVariants["stageDebug"]?.flavors)
        assertEquals("debug", appVariants["stageDebug"]?.buildType)
        assertEquals(":app:compileStageDebugKotlin", appVariants["stageDebug"]?.tasks?.compileKotlin)
        assertEquals(":app:assembleStageDebug", appVariants["stageDebug"]?.tasks?.assemble)
        assertEquals(":app:installStageDebug", appVariants["stageDebug"]?.tasks?.install)
        assertEquals(listOf(":feature"), appVariants["stageDebug"]?.projectDependencies)

        val featureVariant = byPath[":feature"]?.variants.orEmpty().first()
        assertEquals(listOf(":core"), featureVariant.projectDependencies)

        val jvm = byPath[":core"]?.jvm
        assertEquals(listOf("core/src/main/kotlin"), jvm?.sourceDirs)
        assertEquals(listOf("core/build/classes/kotlin/main"), jvm?.classOutputDirs)
        assertTrue(jvm?.projectDependencies.orEmpty().isEmpty())
    }

    @Test
    fun `absent optional fields land as null or empty without crashing`() {
        val report = loadFixture()
        val byPath = report.projects.orEmpty().associateBy { it.gradlePath }

        // Library variants never got a "debuggable"/"applicationId" key in the fixture.
        val featureVariant = byPath[":feature"]?.variants.orEmpty().first()
        assertNull(featureVariant.debuggable)
        assertNull(featureVariant.applicationId)
        assertNull(featureVariant.apkOutputDir)

        // The kotlinJvm project never got a "variants" key.
        assertNull(byPath[":core"]?.variants)
    }

    @Test
    fun `suggestedWatchCommand picks the debuggable variant and includes the transitive module closure`() {
        val report = loadFixture()
        val suggestion = report.suggestedWatchCommand()
        requireNotNull(suggestion)

        // release is non-debuggable and must be skipped in favor of stageDebug.
        assertTrue(suggestion.contains("--app-id dev.hotreload.fixture.stage"))
        // Not the plain "debug" name, so --variant must be present.
        assertTrue(suggestion.contains("--variant stageDebug"))
        // Transitive closure: :app -> :feature -> :core, each rendered as path=dir.
        assertTrue(suggestion.contains(":app=app"))
        assertTrue(suggestion.contains(":feature=feature"))
        assertTrue(suggestion.contains(":core=core"))
        assertTrue(suggestion.startsWith("hotreload watch --project /abs/path/fixture-project"))
    }

    @Test
    fun `suggestedWatchCommand omits --variant for plain debug`() {
        val report = DiscoveryReport(
            schemaVersion = 1,
            gradleVersion = "9.6.1",
            rootProjectName = "plain",
            rootDir = "/abs/plain",
            javaHome = "/abs/jdk",
            projects = listOf(
                DiscoveredProject(
                    gradlePath = ":app",
                    projectDir = "app",
                    type = "androidApp",
                    pluginIds = listOf("com.android.application"),
                    variants = listOf(
                        DiscoveredVariant(
                            name = "debug",
                            buildType = "debug",
                            flavors = emptyList(),
                            debuggable = true,
                            applicationId = "dev.hotreload.plain",
                            tasks = VariantTasks(":app:compileDebugKotlin", ":app:assembleDebug", ":app:installDebug"),
                            projectDependencies = emptyList(),
                        ),
                    ),
                ),
            ),
        )
        val suggestion = report.suggestedWatchCommand()
        requireNotNull(suggestion)
        assertTrue(!suggestion.contains("--variant"))
        assertTrue(suggestion.contains(":app=app"))
    }

    @Test
    fun `suggestedWatchCommand is null when no debuggable app exists`() {
        val report = DiscoveryReport(
            schemaVersion = 1,
            gradleVersion = "9.6.1",
            rootProjectName = "norelease",
            rootDir = "/abs/norelease",
            javaHome = "/abs/jdk",
            projects = listOf(
                DiscoveredProject(
                    gradlePath = ":app",
                    projectDir = "app",
                    type = "androidApp",
                    pluginIds = listOf("com.android.application"),
                    variants = listOf(
                        DiscoveredVariant(
                            name = "release",
                            buildType = "release",
                            flavors = emptyList(),
                            debuggable = false,
                            applicationId = "dev.hotreload.norelease",
                            tasks = VariantTasks(":app:compileReleaseKotlin", ":app:assembleRelease", null),
                            projectDependencies = emptyList(),
                        ),
                    ),
                ),
            ),
        )
        assertNull(report.suggestedWatchCommand())
    }

    @Test
    fun `suggestedWatchCommand is null when there is no androidApp project`() {
        val report = DiscoveryReport(
            schemaVersion = 1,
            gradleVersion = "9.6.1",
            rootProjectName = "libonly",
            rootDir = "/abs/libonly",
            javaHome = "/abs/jdk",
            projects = listOf(
                DiscoveredProject(
                    gradlePath = ":core",
                    projectDir = "core",
                    type = "kotlinJvm",
                    pluginIds = listOf("org.jetbrains.kotlin.jvm"),
                    jvm = JvmInfo(classOutputDirs = emptyList(), sourceDirs = emptyList(), projectDependencies = emptyList()),
                ),
            ),
        )
        assertNull(report.suggestedWatchCommand())
    }

    // ---- resolveWatchPlan tests (T33d) ----

    @Test
    fun `resolveWatchPlan defaults on fixture — app first, closure order, stageDebug, fixture appId`() {
        val report = loadFixture()
        // Fixture has only stageDebug as debuggable (no "debug" variant), so it's the sole one picked.
        val plan = report.resolveWatchPlan(null, null, emptyList(), emptyList())
        assertEquals("stageDebug", plan.variantName)
        assertEquals("dev.hotreload.fixture.stage", plan.applicationId)
        assertEquals(listOf(":app", ":feature", ":core"), plan.modules.map { it.gradlePath })
        assertEquals(":app", plan.modules.first().gradlePath)
    }

    @Test
    fun `resolveWatchPlan explicit variant picked`() {
        val report = loadFixture()
        val plan = report.resolveWatchPlan(null, "stageDebug", emptyList(), emptyList())
        assertEquals("stageDebug", plan.variantName)
    }

    @Test
    fun `resolveWatchPlan unknown variant throws IAE listing debuggables`() {
        val report = loadFixture()
        val e = assertFailsWith<IllegalArgumentException> {
            report.resolveWatchPlan(null, "fooBar", emptyList(), emptyList())
        }
        assertTrue("fooBar" in (e.message ?: ""))
        assertTrue("stageDebug" in (e.message ?: ""))
    }

    @Test
    fun `resolveWatchPlan non-debuggable variant throws IAE`() {
        val report = loadFixture()
        val e = assertFailsWith<IllegalArgumentException> {
            report.resolveWatchPlan(null, "release", emptyList(), emptyList())
        }
        assertTrue("release" in (e.message ?: ""))
        assertTrue("not found or not debuggable" in (e.message ?: ""))
    }

    @Test
    fun `resolveWatchPlan no debug + sole debuggable picked`() {
        // Fixture has only stageDebug as debuggable — picking it automatically.
        val report = loadFixture()
        val plan = report.resolveWatchPlan(null, null, emptyList(), emptyList())
        assertEquals("stageDebug", plan.variantName)
    }

    @Test
    fun `resolveWatchPlan multiple debuggables without --variant throws`() {
        val report = DiscoveryReport(
            schemaVersion = 1,
            projects = listOf(
                DiscoveredProject(
                    gradlePath = ":app",
                    projectDir = "app",
                    type = "androidApp",
                    variants = listOf(
                        DiscoveredVariant(name = "freeDebug", debuggable = true, applicationId = "a"),
                        DiscoveredVariant(name = "paidDebug", debuggable = true, applicationId = "b"),
                    ),
                ),
            ),
        )
        val e = assertFailsWith<IllegalArgumentException> {
            report.resolveWatchPlan(null, null, emptyList(), emptyList())
        }
        assertTrue("multiple debuggable variants" in (e.message ?: ""))
        assertTrue("freeDebug" in (e.message ?: ""))
        assertTrue("paidDebug" in (e.message ?: ""))
    }

    @Test
    fun `resolveWatchPlan two androidApp projects throws IAE listing paths`() {
        val report = loadMultiAppFixture()
        val e = assertFailsWith<IllegalArgumentException> {
            report.resolveWatchPlan(null, null, emptyList(), emptyList())
        }
        assertTrue("multiple application modules" in (e.message ?: ""))
        assertTrue(":app1" in (e.message ?: ""))
        assertTrue(":app2" in (e.message ?: ""))
    }

    @Test
    fun `resolveWatchPlan with appModulePath selects that app`() {
        val report = loadMultiAppFixture()
        val plan = report.resolveWatchPlan(":app1", null, emptyList(), emptyList())
        assertEquals(":app1", plan.modules.first().gradlePath)
        assertEquals("dev.hotreload.app1", plan.applicationId)
    }

    @Test
    fun `resolveWatchPlan appModulePath naming a library throws IAE`() {
        val report = loadMultiAppFixture()
        val e = assertFailsWith<IllegalArgumentException> {
            report.resolveWatchPlan(":shared", null, emptyList(), emptyList())
        }
        assertTrue("not an Android application module" in (e.message ?: ""))
    }

    @Test
    fun `resolveWatchPlan exclude removes a module`() {
        val report = loadFixture()
        val plan = report.resolveWatchPlan(null, null, emptyList(), listOf(":core"))
        assertEquals(listOf(":app", ":feature"), plan.modules.map { it.gradlePath })
    }

    @Test
    fun `resolveWatchPlan exclude app throws IAE`() {
        val report = loadFixture()
        val e = assertFailsWith<IllegalArgumentException> {
            report.resolveWatchPlan(null, null, emptyList(), listOf(":app"))
        }
        assertTrue("cannot exclude the app module" in (e.message ?: ""))
    }

    @Test
    fun `resolveWatchPlan exclude unknown throws IAE`() {
        val report = loadFixture()
        val e = assertFailsWith<IllegalArgumentException> {
            report.resolveWatchPlan(null, null, emptyList(), listOf(":nope"))
        }
        assertTrue("not a watched module" in (e.message ?: ""))
    }

    @Test
    fun `resolveWatchPlan include whitelists, app kept implicitly`() {
        val report = loadFixture()
        val plan = report.resolveWatchPlan(null, null, listOf(":feature"), emptyList())
        assertEquals(listOf(":app", ":feature"), plan.modules.map { it.gradlePath })
    }

    @Test
    fun `resolveWatchPlan include unknown throws IAE`() {
        val report = loadFixture()
        val e = assertFailsWith<IllegalArgumentException> {
            report.resolveWatchPlan(null, null, listOf(":nope"), emptyList())
        }
        assertTrue("not a watched module" in (e.message ?: ""))
    }
}
