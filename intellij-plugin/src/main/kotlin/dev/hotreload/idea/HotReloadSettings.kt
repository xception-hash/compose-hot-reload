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
 * Exactly the surface T26 asks for: where the CLI lives, and the `watch` arguments.
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
        /** Android SDK root; blank = the CLI's default ($ANDROID_HOME). */
        var sdkPath: String = ""
        /**
         * Path to the CLI launcher from `./gradlew :cli:installDist`
         * (…/cli/build/install/cli/bin/cli). No bundled binary in the MVP.
         */
        var cliLauncherPath: String = ""
        /** Extra `watch` args appended verbatim, e.g. `--literals` or `--build-tools 36.0.0`. */
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
