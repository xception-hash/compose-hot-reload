package dev.hotreload.idea

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent

/**
 * Tools-menu entry that starts or stops the watch session (the status-bar widget offers the
 * same toggle on click). The label flips between Start/Stop based on the live process state.
 */
class HotReloadToggleAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabled = project != null
        val running = project != null && HotReloadService.getInstance(project).isRunning
        e.presentation.text = if (running) "Stop Hot Reload" else "Start Hot Reload"
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        HotReloadService.getInstance(project).toggle()
    }
}
