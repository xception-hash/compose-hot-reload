import java.util.jar.JarInputStream
import java.util.zip.ZipFile

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
    implementation("com.google.code.gson:gson:2.11.0")
    intellijPlatform {
        // Build/run against IntelliJ IDEA Community; the plugin also loads in Android Studio
        // (it only uses core platform APIs: status bar, actions, project settings).
        // useInstaller = false: 2026.1.4 has no published download.jetbrains.com installer dmg
        // yet, only a maven artifact in the intellij-repository — same reasoning as the
        // pluginVerification ides() below.
        intellijIdeaCommunity(providers.gradleProperty("platformVersion").get(), useInstaller = false)
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
        description = """
            <h2>Compose Hot Reload for Android</h2>
            <p>Apply hot reload to a running, debuggable Jetpack Compose app from Android Studio or
            IntelliJ IDEA. The plugin includes the <code>hotreload</code> CLI, starts one watch
            session for the selected device, and reports its state in the status bar.</p>
            <h3>Get started</h3>
            <ol>
              <li>Open <b>Settings | Tools | Compose Hot Reload</b> and select your project.</li>
              <li>Use <b>Refresh discovery</b> as a suggestion, then review and save an explicit
                  configured profile with the app, variant, watched modules, target JDK, and device.</li>
              <li>Run the matching <code>doctor</code> and <code>prepare</code> flow so the CLI owns
                  the installed debug APK baseline.</li>
              <li>Start from the status-bar widget or <b>Tools | Start Hot Reload</b>. Wait for
                  <b>Ready</b>, save one Compose body edit, then use the same control to Stop.</li>
            </ol>
            <p>Configured Gradle-plugin integration with a reviewed profile is the stable path.
            Zero-touch bootstrap and live literals are experimental. Use one API-30+ device and one
            watcher at a time.</p>
            <p><a href="https://github.com/xception-hash/compose-hot-reload">Quickstart and source</a>
            | <a href="https://github.com/xception-hash/compose-hot-reload/blob/main/docs/ide-plugin-settings.md">IDE settings guide</a>
            | <a href="https://github.com/xception-hash/compose-hot-reload/blob/main/docs/ai-project-setup.md">AI-assisted setup guide</a></p>
        """.trimIndent()
        changeNotes = """
            <ul>
              <li><b>0.2.0</b> — Defines configured Gradle-plugin integration with reviewed
                  profiles as the stable onboarding path. Zero-touch and live literals remain
                  experimental; public AI-assisted setup and recovery guidance is included.</li>
              <li><b>0.1.8</b> — Zero-touch now supports apps with a minSdk of 23. Hot reload
                  remains enabled only on API 30+ devices; older devices safely skip the runtime.</li>
              <li><b>0.1.7</b> — Zero-touch now supports projects with Gradle composite builds
                  (for example, an included <code>build-logic</code> build). The bundled
                  bootstrap is inert in included builds and continues to instrument the selected
                  root-build app modules.</li>
              <li><b>0.1.6</b> — Preflight now surfaces the real doctor output and a
                  <b>Report on GitHub</b> action when the environment check can't run;
                  auto-discovers the Android SDK (<code>local.properties</code> /
                  <code>ANDROID_HOME</code> / platform default) for GUI-launched IDEs; expands
                  <code>~</code> in path settings.</li>
              <li><b>0.1.5</b> — Added a pre-Start environment preflight: the plugin runs
                  <code>hotreload doctor</code> before launching and, if the environment is not
                  ready (missing Android SDK build-tools, no connected device, or the app is not
                  running), shows an actionable notification with a <b>Start anyway</b> option
                  instead of a raw CLI error. Also rebuilt against the 2026.1 IntelliJ platform and
                  verified against the newest IDE builds.</li>
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
    // History (0.1.2/0.1.3 shipped broken): several `PluginManager` descriptor lookups (e.g.
    // getPluginByClass) are `@ApiStatus.Internal` only from the platform's 262 branch (2026.2)
    // on, and 2026.x wasn't in the public IntelliJ maven repo yet — so a local verifyPlugin
    // against 2025.x stayed green while the Marketplace verifier (2026.2 RC, build 262.8665.176)
    // rejected the upload. 0.1.4 removed ALL platform plugin-descriptor APIs (see PluginInfo.kt).
    // 0.1.5 moves the build target itself to 2026.1.4 (now published to the public IntelliJ
    // maven repo), so the verifier list below pins the older 2025.1 line the plugin used to
    // build against, the 2026.1.4 stable it builds against now, and the Marketplace's own 262
    // build — keep the last entry tracking whatever line the Marketplace compat check runs.
    pluginVerification {
        ides {
            // Older supported line (sinceBuild = 242) — the prior build target.
            ide(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.IntellijIdeaCommunity, "2025.1", useInstaller = false)
            // Newest stable = current build target (platformVersion).
            ide(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.IntellijIdeaCommunity, providers.gradleProperty("platformVersion").get(), useInstaller = false)
            // The Marketplace's own 262 build family.
            ide(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.IntellijIdeaCommunity, "262.8665.176-EAP-SNAPSHOT", useInstaller = false)
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

// Marketplace builds copy the root installDist tree into the plugin ZIP. Inspect the final
// archive so a missing nested bootstrap payload cannot ship unnoticed.
tasks.named("buildPlugin") {
    doLast {
        val archive = outputs.files.files.singleOrNull { it.extension == "zip" }
            ?: error("buildPlugin produced no unique ZIP: ${outputs.files.files}")
        val required = setOf(
            "dev/hotreload/bootstrap/bootstrap.jar",
            "dev/hotreload/bootstrap/runtime-client.aar",
            "dev/hotreload/bootstrap/zero-touch.init.gradle",
        )

        ZipFile(archive).use { zip ->
            val engineEntries = mutableListOf<java.util.zip.ZipEntry>()
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.name.endsWith("/cli/lib/engine.jar")) engineEntries += entry
            }
            check(engineEntries.size == 1) {
                "expected exactly one */cli/lib/engine.jar in $archive, found ${engineEntries.map { it.name }}"
            }

            val bundled = mutableSetOf<String>()
            zip.getInputStream(engineEntries.single()).use { input ->
                JarInputStream(input).use { jar ->
                    var entry = jar.nextJarEntry
                    while (entry != null) {
                        bundled += entry.name
                        entry = jar.nextJarEntry
                    }
                }
            }
            val missing = required - bundled
            check(missing.isEmpty()) {
                "IntelliJ plugin's bundled CLI engine JAR is missing zero-touch payloads: ${missing.joinToString()}"
            }
        }
    }
}
