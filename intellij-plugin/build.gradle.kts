plugins {
    kotlin("jvm") version "2.4.0"
    id("org.jetbrains.intellij.platform") version "2.5.0"
}

group = "dev.hotreload"
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // Build/run against IntelliJ IDEA Community; the plugin also loads in Android Studio
        // (it only uses core platform APIs: status bar, actions, project settings).
        intellijIdeaCommunity(providers.gradleProperty("platformVersion").get())
    }

    // CliProtocol is pure Kotlin, so its tests are plain JUnit 5 (no platform fixture needed).
    // We deliberately do NOT add testFramework(TestFrameworkType.Platform): it registers
    // com.intellij.tests.JUnit5TestSessionListener via META-INF/services, which cannot
    // instantiate outside the full IDE test runtime and breaks the plain-JUnit5 :test task.
    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // The IntelliJ Platform Gradle Plugin wires its (JUnit4-based) test runtime into the :test
    // task, so org.junit.rules.TestRule must be present even though our tests are pure JUnit5.
    testRuntimeOnly("junit:junit:4.13.2")
}

kotlin {
    jvmToolchain(21)
}

// Bake the plugin version into a bundled resource so PluginInfo can report it at runtime WITHOUT
// any IntelliJ plugin-descriptor API (all PluginManager descriptor lookups are @ApiStatus.Internal
// on 2026.2 and the Marketplace compat check rejects them).
val generatePluginProperties = tasks.register("generatePluginProperties") {
    val pluginVersion = providers.gradleProperty("pluginVersion")
    val outDir = layout.buildDirectory.dir("generated/pluginProperties")
    inputs.property("version", pluginVersion)
    outputs.dir(outDir)
    doLast {
        val pkgDir = outDir.get().dir("dev/hotreload/idea").asFile
        pkgDir.mkdirs()
        pkgDir.resolve("plugin.properties").writeText("version=${pluginVersion.get()}\n")
    }
}

sourceSets["main"].resources.srcDir(generatePluginProperties)

intellijPlatform {
    pluginConfiguration {
        id = "dev.hotreload.ide"
        name = "Compose Hot Reload"
        version = providers.gradleProperty("pluginVersion").get()
        description = "Flutter-style hot reload for Jetpack Compose on Android, driven from the IDE. " +
            "Spawns the hotreload CLI and shows live reload status in the status bar."
        changeNotes = """
            <ul>
              <li><b>0.1.4</b> — Removed all use of internal IntelliJ plugin-descriptor APIs
                  (every <code>PluginManager</code> descriptor lookup became
                  <code>@ApiStatus.Internal</code> on 2026.2). The plugin version is now baked in at
                  build time and the bundled CLI is located from the plugin's own jar; no behavior
                  change. Fixes the Marketplace compatibility rejection.</li>
              <li><b>0.1.2</b> — Replaced an internal-platform API (<code>PluginManagerCore.getPlugin</code>)
                  with <code>PluginManager.getPluginByClass</code> for resolving the bundled
                  CLI; no behavior change. Plugin ID is now <code>dev.hotreload.ide</code>.</li>
              <li><b>0.1.1</b> — The <code>hotreload</code> CLI is now bundled inside the plugin.
                  Install from disk (or the Marketplace) and hot-reload with no repo clone: leave the
                  Settings ▸ Tools ▸ Compose Hot Reload ▸ CLI launcher field blank to use it. Requires
                  the Android SDK (build-tools 36.0.0) on the machine.</li>
              <li><b>0.1.0</b> — Initial release: status-bar widget, Start/Stop, error &amp;
                  rebuild-needed balloons. Required a locally built CLI launcher.</li>
            </ul>
        """.trimIndent()
        vendor {
            name = "Compose Hot Reload (OSS)"
        }
        ideaVersion {
            // JDK 21 baseline (2024.2+). Leave the upper bound open so new IDE builds load it.
            // Verified live in Android Studio 2026.1 (build 261). Core-platform APIs only, so an
            // open upper bound is safe for the Marketplace.
            sinceBuild = "242"
            untilBuild = provider { null }
        }
    }

    // Marketplace signing + publishing (T31 Part 3). All secrets come from env vars so nothing
    // sensitive lives in the repo; the tasks are no-ops until the maintainer supplies them:
    //   CERTIFICATE_CHAIN     — PEM chain from the signing cert (openssl)
    //   PRIVATE_KEY           — PEM private key
    //   PRIVATE_KEY_PASSWORD  — password protecting the private key
    //   PUBLISH_TOKEN         — JetBrains Marketplace permanent upload token
    // Then: ./gradlew signPlugin && ./gradlew publishPlugin
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }

    // Plugin Verifier gate: internal-API / compat errors here are what the Marketplace compat
    // check rejects — run `./gradlew verifyPlugin` before any upload.
    //
    // KNOWN BLIND SPOT (do NOT trust a green verifyPlugin for internal-API): the verifier can only
    // download IDEs published to the public IntelliJ maven repo, which currently tops out at the
    // 2025.x line. Several `PluginManager` descriptor lookups (e.g. getPluginByClass) were only
    // annotated `@ApiStatus.Internal` in the platform's 262 branch (2026.2) — verified in
    // intellij-community: NOT @Internal on 251/252/253, @Internal on 262. The Marketplace runs its
    // verifier against 2026.2 RC (build 262.8665.176), so it flagged getPluginByClass while a local
    // verifyPlugin against 2025.x stayed green — that is exactly how 0.1.2/0.1.3 shipped broken.
    // Mitigation used for 0.1.4: we removed ALL platform plugin-descriptor APIs (see PluginInfo.kt)
    // rather than relying on this gate. TODO: once IC 2026.2 is in the public maven repo, add it
    // here (`ide(IntellijIdeaCommunity, "2026.2")`) so this class of drift is caught locally.
    pluginVerification {
        ides {
            ide(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.IntellijIdeaCommunity, providers.gradleProperty("platformVersion").get())
        }
    }
}

tasks.test {
    useJUnitPlatform()
}

tasks.named<org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask>("prepareSandbox") {
    // Build the CLI distribution from the composite-included root build first.
    dependsOn(gradle.includedBuild("compose-hot-reload").task(":cli:installDist"))

    // Copy the installDist output tree (bin/ + lib/) into <sandbox>/plugins/<pluginName>/cli/
    from(layout.projectDirectory.dir("../cli/build/install/cli")) {
        into("${pluginName.get()}/cli")
    }
}
