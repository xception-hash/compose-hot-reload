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

    @Test fun `doctorArguments starts with the doctor head and carries the project dir`() {
        val config = HotReloadWatchConfig(projectDir = "/repo/app")
        val args = config.doctorArguments()
        assertEquals("doctor", args.first())
        assertTrue(args.containsAll(listOf("--project", "/repo/app")))
    }

    @Test fun `doctorArguments emits flags for appId, variant, device, sdk and zeroTouch`() {
        val config = HotReloadWatchConfig(
            projectDir = "/repo/app", appId = "dev.example.qa", variant = "qaDebug",
            device = "emulator-5554", sdkPath = "/sdk", zeroTouch = true,
        )
        assertEquals(
            listOf(
                "doctor", "--project", "/repo/app", "--app-id", "dev.example.qa", "--variant", "qaDebug",
                "--module", "app", "--device", "emulator-5554", "--sdk", "/sdk", "--zero-touch",
            ),
            config.doctorArguments(),
        )
    }

    @Test fun `doctorArguments for a minimal config still emits module app`() {
        val config = HotReloadWatchConfig(projectDir = "/repo/app")
        assertEquals(listOf("doctor", "--project", "/repo/app", "--module", "app"), config.doctorArguments())
    }

    @Test fun `from expands a leading tilde in path-valued fields but leaves other fields untouched`() {
        val state = HotReloadSettings.State().apply {
            appId = "dev.example.app"
            sdkPath = "~/Library/Android/sdk"
            cliLauncherPath = "~/cli/bin/cli"
            targetJdk = "~/jdks/17"
            appModule = "~notexpanded"
        }
        val config = HotReloadWatchConfig.from(state, "/repo/app", home = "/home/u")

        assertEquals("/home/u/Library/Android/sdk", config.sdkPath)
        assertEquals("/home/u/cli/bin/cli", config.cliLauncherPath)
        assertEquals("/home/u/jdks/17", config.targetJdk)
        // Non-tilde and non-path fields pass through unchanged.
        assertEquals("~notexpanded", config.appModule)

        val absoluteState = HotReloadSettings.State().apply {
            appId = "dev.example.app"
            sdkPath = "/already/absolute"
        }
        assertEquals(
            "/already/absolute",
            HotReloadWatchConfig.from(absoluteState, "/repo/app", home = "/home/u").sdkPath,
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
