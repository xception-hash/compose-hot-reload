package dev.hotreload.engine

import dev.hotreload.protocol.Protocol
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

class FingerprintTest {

    @TempDir
    lateinit var tempDir: Path

    private fun config(
        modules: List<ModuleSpec.Request> = listOf(ModuleSpec.Request(":app", "app")),
        variant: String = "debug",
        literals: Boolean = false,
        gradleArgs: List<String> = emptyList(),
        projectJavaHome: Path? = null,
        integrationMode: IntegrationMode = IntegrationMode.CONFIGURED,
    ) = ProjectConfig(
        projectDir = Path.of("/proj"),
        modules = modules,
        applicationId = "dev.hotreload.sample",
        variant = variant,
        literals = literals,
        gradleArgs = gradleArgs,
        projectJavaHome = projectJavaHome,
        integrationMode = integrationMode,
    )

    @Test
    fun ofMapsFieldsAndSorts() {
        val cfg = config(
            modules = listOf(
                ModuleSpec.Request(":core", "core", variant = "release"),
                ModuleSpec.Request(":app", "app"),
            ),
            gradleArgs = listOf("-Pfoo"),
            projectJavaHome = Path.of("/opt/jdk"),
        )
        val fp = Fingerprint.of(cfg, "emulator-5554", "abc123", "/proj/app/x.apk")

        assertEquals("dev.hotreload.sample", fp.applicationId)
        assertEquals("emulator-5554", fp.deviceSerial)
        assertEquals("/proj", fp.projectDir)
        assertEquals("debug", fp.variant)
        // sorted "gradlePath=relativeDir"
        assertEquals(listOf(":app=app", ":core=core"), fp.modules)
        // only modules with an explicit variant, sorted
        assertEquals(listOf(":core=release"), fp.moduleVariants)
        assertEquals(listOf("-Pfoo"), fp.gradleArgs)
        assertEquals("/opt/jdk", fp.projectJavaHome)
        assertEquals(Protocol.VERSION, fp.protocolVersion)
        assertEquals(Fingerprint.REQUIRED_COMPILER_FLAGS, fp.compilerFlags)
        assertEquals(IntegrationMode.CONFIGURED, fp.integrationMode)
        assertNull(fp.runtimeArtifactSha256)
        assertEquals("abc123", fp.apkSha256)
        assertEquals("/proj/app/x.apk", fp.apkPath)
    }

    @Test
    fun mismatchesEmptyOnIdenticalConfig() {
        val cfg = config()
        val fp = Fingerprint.of(cfg, "s", "sha", "/x.apk")
        assertEquals(emptyList(), fp.mismatches(cfg))
    }

    @Test
    fun variantMismatchReported() {
        val fp = Fingerprint.of(config(variant = "debug"), "s", "sha", "/x.apk")
        val m = fp.mismatches(config(variant = "release"))
        assertEquals(1, m.size)
        assertTrue(m.single().startsWith("variant:"), m.single())
    }

    @Test
    fun modulesMismatchReported() {
        val fp = Fingerprint.of(config(), "s", "sha", "/x.apk")
        val m = fp.mismatches(config(modules = listOf(
            ModuleSpec.Request(":app", "app"),
            ModuleSpec.Request(":core", "core"),
        )))
        assertEquals(1, m.size)
        assertTrue(m.single().startsWith("modules:"), m.single())
    }

    @Test
    fun moduleVariantsMismatchReported() {
        val fp = Fingerprint.of(config(modules = listOf(ModuleSpec.Request(":app", "app"))), "s", "sha", "/x.apk")
        val m = fp.mismatches(config(modules = listOf(ModuleSpec.Request(":app", "app", variant = "debug"))))
        assertEquals(1, m.size)
        assertTrue(m.single().startsWith("moduleVariants:"), m.single())
    }

    @Test
    fun literalsMismatchReported() {
        val fp = Fingerprint.of(config(literals = false), "s", "sha", "/x.apk")
        val m = fp.mismatches(config(literals = true))
        assertEquals(1, m.size)
        assertTrue(m.single().startsWith("literals:"), m.single())
    }

    @Test
    fun integrationModeMismatchReported() {
        val fp = Fingerprint.of(config(), "s", "sha", "/x.apk")
        val m = fp.mismatches(config(integrationMode = IntegrationMode.ZERO_TOUCH))
        assertTrue(m.any { it.startsWith("integrationMode:") }, m.toString())
        assertTrue(m.any { it.startsWith("runtimeArtifactSha256:") }, m.toString())
    }

    @Test
    fun zeroTouchFingerprintIncludesBundledRuntimeHash() {
        val cfg = config(integrationMode = IntegrationMode.ZERO_TOUCH)
        val fp = Fingerprint.of(cfg, "s", "sha", "/x.apk")

        assertEquals(IntegrationMode.ZERO_TOUCH, fp.integrationMode)
        assertEquals(ZeroTouchArtifacts.runtimeArtifactSha256(), fp.runtimeArtifactSha256)
        assertTrue(fp.runtimeArtifactSha256?.isNotBlank() == true)
    }

    @Test
    fun zeroTouchRuntimeArtifactMismatchReported() {
        val cfg = config(integrationMode = IntegrationMode.ZERO_TOUCH)
        val fp = Fingerprint.of(cfg, "s", "sha", "/x.apk")
            .copy(runtimeArtifactSha256 = "different")

        val mismatch = fp.mismatches(cfg)
        assertEquals(1, mismatch.size)
        assertTrue(mismatch.single().startsWith("runtimeArtifactSha256:"), mismatch.single())
    }

    @Test
    fun gradleArgsMismatchReported() {
        val fp = Fingerprint.of(config(gradleArgs = emptyList()), "s", "sha", "/x.apk")
        val m = fp.mismatches(config(gradleArgs = listOf("-Pbar")))
        assertEquals(1, m.size)
        assertTrue(m.single().startsWith("gradleArgs:"), m.single())
    }

    @Test
    fun projectJavaHomeMismatchReported() {
        val fp = Fingerprint.of(config(projectJavaHome = null), "s", "sha", "/x.apk")
        val m = fp.mismatches(config(projectJavaHome = Path.of("/opt/jdk")))
        assertEquals(1, m.size)
        assertTrue(m.single().startsWith("projectJavaHome:"), m.single())
    }

    @Test
    fun protocolVersionMismatchReported() {
        val cfg = config()
        val fp = Fingerprint.of(cfg, "s", "sha", "/x.apk").copy(protocolVersion = Protocol.VERSION + 999)
        val m = fp.mismatches(cfg)
        assertEquals(1, m.size)
        assertTrue(m.single().startsWith("protocolVersion:"), m.single())
    }

    @Test
    fun informationalFieldsDoNotTriggerMismatch() {
        val cfg = config()
        val fp = Fingerprint.of(cfg, "s", "sha", "/x.apk")
            .copy(hostJavaMajor = 999, preparedAt = 0, apkSha256 = "different", apkPath = "/other.apk")
        assertEquals(emptyList(), fp.mismatches(cfg))
    }

    @Test
    fun gsonRoundTrip() {
        val store = FingerprintStore(tempDir)
        val fp = Fingerprint.of(
            config(gradleArgs = listOf("-Pfoo"), projectJavaHome = Path.of("/opt/jdk")),
            "emulator-5554", "abc123", "/x.apk",
        )
        store.save(fp)
        assertEquals(fp, store.load("emulator-5554", "dev.hotreload.sample"))
    }
}
