package dev.hotreload.idea

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.util.Consumer
import java.awt.Component
import java.awt.event.MouseEvent

/**
 * Status-bar widget: the single always-visible surface for hot-reload state. Click to
 * Start/Stop. Its text/tooltip are recomputed from [HotReloadService.status] whenever the
 * service pushes an update (see [HotReloadService.setStatus] → `updateWidget`).
 */
class HotReloadStatusWidget(private val project: Project) :
    StatusBarWidget, StatusBarWidget.TextPresentation {

    private val service = HotReloadService.getInstance(project)

    override fun ID(): String = ID

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun install(statusBar: StatusBar) {
        // Repaint the widget whenever the service changes state.
        service.addListener { statusBar.updateWidget(ID) }
    }

    override fun dispose() {}

    override fun getText(): String {
        val s = service.status
        return "Hot Reload: " + when (s.state) {
            HotReloadState.OFF -> "off"
            HotReloadState.STARTING -> "starting…"
            HotReloadState.READY -> s.lastLatencyMs?.let { "ready (${it}ms)" } ?: "ready"
            HotReloadState.RELOADING -> "reloading…"
            HotReloadState.ERROR -> if (s.errorCount > 1) "error (${s.errorCount})" else "error"
            HotReloadState.REBUILD_NEEDED -> "rebuild needed"
        }
    }

    override fun getTooltipText(): String {
        val s = service.status
        val head = when (s.state) {
            HotReloadState.OFF -> "Click to start hot reload"
            HotReloadState.STARTING -> "Starting the hotreload watch session"
            HotReloadState.READY -> "Watching — save a file to hot reload"
            HotReloadState.RELOADING -> "Reloading…"
            HotReloadState.ERROR -> "Reload failed"
            HotReloadState.REBUILD_NEEDED -> "A full rebuild + reinstall is required"
        }
        return s.detail?.takeIf { it.isNotBlank() }?.let { "$head — $it" } ?: head
    }

    override fun getClickConsumer(): Consumer<MouseEvent> = Consumer { service.toggle() }

    override fun getAlignment(): Float = Component.CENTER_ALIGNMENT

    companion object {
        const val ID = "HotReload.StatusWidget"
    }
}

/** Registers the widget for every project (see plugin.xml `statusBarWidgetFactory`). */
class HotReloadStatusWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = HotReloadStatusWidget.ID

    override fun getDisplayName(): String = "Compose Hot Reload"

    override fun createWidget(project: Project): StatusBarWidget = HotReloadStatusWidget(project)

    override fun isAvailable(project: Project): Boolean = true

    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true
}
