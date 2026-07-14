package dev.hotreload.engine

import com.google.gson.Gson
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
