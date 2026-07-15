package dev.hotreload.idea

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HotReloadCommandTest {
    @Test fun `legacy settings retain the original watch defaults`() {
        val state = HotReloadSettings.State().apply { appId = "dev.example.app" }
        val config = HotReloadWatchConfig.from(state, "/repo/app")

        assertEquals(
            listOf("watch", "--project", "/repo/app", "--app-id", "dev.example.app", "--module", "app"),
            config.watchArguments(),
        )
    }

    @Test fun `structured fields produce individual repeatable tokens`() {
        val config = HotReloadWatchConfig(
            projectDir = "/repo with spaces", profile = "qa", appModule = ":app", appId = "dev.example.qa",
            variant = "qaDebug", targetJdk = "/jdks/17", watchedModules = ":app,:feature",
            device = "emulator-5554", sdkPath = "/sdk", literals = true, zeroTouch = true,
            gradleArgs = listOf("-Pmode=qa", "--offline"), advancedTokens = listOf("--build-tools", "36.0.0"),
        )
        assertEquals(
            listOf(
                "watch", "--project", "/repo with spaces", "--profile", "qa", "--app-module", ":app",
                "--app-id", "dev.example.qa", "--variant", "qaDebug", "--project-java-home", "/jdks/17",
                "--module", ":app,:feature", "--device", "emulator-5554", "--sdk", "/sdk", "--literals",
                "--zero-touch", "--gradle-arg", "-Pmode=qa", "--gradle-arg", "--offline", "--build-tools", "36.0.0",
            ),
            config.watchArguments(),
        )
        assertEquals(
            listOf("inspect", "--project", "/repo with spaces", "--json", "--profile", "qa", "--project-java-home", "/jdks/17", "--zero-touch", "--gradle-arg", "-Pmode=qa", "--gradle-arg", "--offline"),
            config.inspectArguments(),
        )
    }

    @Test fun `rendered command quotes tokens without changing their boundaries`() {
        assertEquals("cli watch --project '/repo with spaces' --gradle-arg '-Pname=two words'", renderCommand(
            listOf("cli", "watch", "--project", "/repo with spaces", "--gradle-arg", "-Pname=two words"),
        ))
    }

    @Test fun `schema one discovery exposes debuggable choices and dependency closure`() {
        val choices = DiscoveryParser.parse(
            """{"schemaVersion":1,"projects":[
              {"gradlePath":":app","type":"androidApp","variants":[
                {"name":"debug","debuggable":true,"applicationId":"dev.example","projectDependencies":[":feature"]},
                {"name":"release","debuggable":false,"applicationId":"dev.example"}]},
              {"gradlePath":":feature","type":"androidLib","variants":[
                {"name":"debug","projectDependencies":[":core"]}]},
              {"gradlePath":":core","type":"kotlinJvm"}
            ]}""",
        )
        assertEquals(listOf(":app"), choices.appModules)
        assertEquals(listOf("debug"), choices.variants(":app"))
        assertEquals("dev.example", choices.appId(":app", "debug"))
        assertEquals(listOf(":app", ":feature", ":core"), choices.moduleClosure(":app", "debug"))
    }

    @Test fun `unknown discovery schema is rejected`() {
        val error = runCatching { DiscoveryParser.parse("{\"schemaVersion\":2}") }.exceptionOrNull()
        assertTrue(error?.message?.contains("expected schema 1") == true)
    }
}
