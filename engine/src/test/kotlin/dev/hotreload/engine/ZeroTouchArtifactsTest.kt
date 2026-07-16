package dev.hotreload.engine

import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ZeroTouchArtifactsTest {
    private fun config(mode: IntegrationMode, literals: Boolean = false) = ProjectConfig(
        projectDir = Path.of("/target"),
        modules = listOf(
            ModuleSpec.Request(":mobile", "applications/mobile"),
            ModuleSpec.Request(":feature", "features/feature"),
        ),
        applicationId = "com.example.app",
        variant = "qaDebug",
        literals = literals,
        integrationMode = mode,
    )

    @Test
    fun configuredArgumentsRemainUnchanged() {
        val cfg = config(IntegrationMode.CONFIGURED, literals = true).copy(
            gradleArgs = listOf("--offline", "-Pexample=true"),
        )
        GradleInvocation.open(cfg).use { invocation ->
            assertEquals(
                listOf("--offline", "-Pexample=true", "-Photreload.liveLiterals=true"),
                invocation.arguments,
            )
            assertEquals(null, invocation.runtimeArtifactSha256)
        }
    }

    @Test
    fun zeroTouchArgumentsUseExtractedPayloadsAndCleanThemUp() {
        val invocation = GradleInvocation.open(config(IntegrationMode.ZERO_TOUCH, literals = true))
        val args = invocation.arguments
        val script = Path.of(args[args.indexOf("--init-script") + 1])
        val jar = Path.of(args.single { it.startsWith("-Pdev.hotreload.bootstrap.jar=") }.substringAfter('='))
        val aar = Path.of(args.single { it.startsWith("-Pdev.hotreload.bootstrap.runtimeAar=") }.substringAfter('='))

        assertTrue(Files.isRegularFile(script))
        assertTrue(Files.isRegularFile(jar))
        assertTrue(Files.isRegularFile(aar))
        assertTrue("-Pdev.hotreload.bootstrap.modules=:mobile,:feature" in args)
        assertTrue("-Pdev.hotreload.bootstrap.appModule=:mobile" in args)
        assertTrue("-Pdev.hotreload.bootstrap.variant=qaDebug" in args)
        assertEquals(ZeroTouchArtifacts.runtimeArtifactSha256(), invocation.runtimeArtifactSha256)

        val root = script.parent
        invocation.close()
        assertFalse(Files.exists(root), "bootstrap temp directory must be removed after the Gradle session")
    }

    @Test
    fun bundledBootstrapIsJava17AndAgpFree() {
        ZeroTouchArtifacts.extract().use { artifacts ->
            ZipFile(artifacts.bootstrapJar.toFile()).use { zip ->
                val entry = assertNotNull(zip.getEntry("dev/hotreload/bootstrap/BootstrapPlugin.class"))
                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                val major = ((bytes[6].toInt() and 0xff) shl 8) or (bytes[7].toInt() and 0xff)
                assertEquals(61, major, "bootstrap class must be Java 17 compatible")

                val names = zip.entries().asSequence().map { it.name }.toList()
                assertFalse(names.any { it.startsWith("com/android/") }, "bootstrap jar must not bundle/link AGP classes")
                assertFalse(names.any { it.startsWith("kotlin/") }, "bootstrap jar must not bundle Kotlin runtime classes")
            }
        }
    }

    @Test
    fun bundledRuntimeHasPortableMetadataInitializerAndBothAbis() {
        ZeroTouchArtifacts.extract().use { artifacts ->
            ZipFile(artifacts.runtimeAar.toFile()).use { zip ->
                val props = Properties().apply {
                    zip.getInputStream(assertNotNull(zip.getEntry("META-INF/com/android/build/gradle/aar-metadata.properties"))).use(::load)
                }
                assertEquals("30", props.getProperty("minCompileSdk"))
                assertNotNull(zip.getEntry("AndroidManifest.xml"))
                assertNotNull(zip.getEntry("classes.jar"))
                assertNotNull(zip.getEntry("jni/arm64-v8a/libhotreload_agent.so"))
                assertNotNull(zip.getEntry("jni/x86_64/libhotreload_agent.so"))
            }
        }
    }
}
