package dev.hotreload.toy

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        HotSwap.attachAgent(this)
        ComposeBridge.enableHotReloadMode()
    }
}
