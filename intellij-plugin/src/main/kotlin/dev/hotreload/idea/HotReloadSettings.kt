package dev.hotreload.idea

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil

/**
 * Project-level, persisted plugin configuration (stored in `.idea/hotreload.xml`).
 * Structured `watch` inputs, stored per project. The original T26 fields remain so existing
 * `.idea/hotreload.xml` files continue to launch the same command.
 */
@Service(Service.Level.PROJECT)
@State(name = "HotReloadSettings", storages = [Storage("hotreload.xml")])
class HotReloadSettings : PersistentStateComponent<HotReloadSettings.State> {

    class State {
        /** Gradle project dir of the app; blank = the IDE project's base path. */
        var projectDir: String = ""
        var appId: String = ""
        /** Comma-separated Gradle modules; the first is the app module. */
        var modules: String = "app"
        /** Optional named CLI profile. Explicit structured controls still override it. */
        var profile: String = ""
        /** Explicit app module when it differs from the first watched module. */
        var appModule: String = ""
        var variant: String = ""
        /** Target project's JDK, passed as `--project-java-home`. */
        var targetJdk: String = ""
        var device: String = ""
        var literals: Boolean = false
        var zeroTouch: Boolean = false
        /** Advanced opt-out: skip the pre-Start `hotreload doctor` preflight. */
        var skipPreflight: Boolean = false
        /** One exact Gradle argument per item; emitted as repeatable `--gradle-arg`. */
        var gradleArgs: MutableList<String> = mutableListOf()
        /** Advanced append-only CLI tokens. Each item is one token, never shell-split. */
        var advancedTokens: MutableList<String> = mutableListOf()
        /** Android SDK root; blank = the CLI's default ($ANDROID_HOME). */
        var sdkPath: String = ""
        /**
         * Override path to a CLI launcher (…/cli/build/install/cli/bin/cli from
         * `./gradlew :cli:installDist`). Blank = use the CLI bundled inside the plugin (T31 Part 2).
         */
        var cliLauncherPath: String = ""
        /** Legacy whitespace-delimited extra arguments, read only when [advancedTokens] is empty. */
        var extraArgs: String = ""
    }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(loaded: State) {
        XmlSerializerUtil.copyBean(loaded, state)
    }

    companion object {
        fun getInstance(project: Project): HotReloadSettings = project.service()
    }
}
