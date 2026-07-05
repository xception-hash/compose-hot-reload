package dev.hotreload.idea

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel

/**
 * The single settings page (Settings › Tools › Compose Hot Reload). Bound directly to the
 * persisted [HotReloadSettings] state; [BoundConfigurable] wires apply/reset from the bindings.
 */
class HotReloadConfigurable(project: Project) : BoundConfigurable("Compose Hot Reload") {

    private val state = HotReloadSettings.getInstance(project).state

    override fun createPanel(): DialogPanel = panel {
        row("CLI launcher:") {
            textField().bindText(state::cliLauncherPath).columns(45)
                .comment("Path to …/cli/build/install/cli/bin/cli — produce it with <code>./gradlew :cli:installDist</code> in the repo.")
        }
        row("Project dir:") {
            textField().bindText(state::projectDir).columns(45)
                .comment("Gradle project of the app. Blank = this IDE project's base path.")
        }
        row("Application id:") {
            textField().bindText(state::appId).columns(30)
                .comment("applicationId of the debug build installed on the device.")
        }
        row("Modules:") {
            textField().bindText(state::modules).columns(30)
                .comment("Comma-separated Gradle modules; the first is the app module. Nested paths use '/'.")
        }
        row("SDK path:") {
            textField().bindText(state::sdkPath).columns(45)
                .comment("Android SDK root. Blank = the CLI default (\$ANDROID_HOME).")
        }
        row("Extra CLI args:") {
            textField().bindText(state::extraArgs).columns(45)
                .comment("Appended to <code>hotreload watch</code>, e.g. <code>--literals</code> or <code>--build-tools 36.0.0</code>.")
        }
    }
}
