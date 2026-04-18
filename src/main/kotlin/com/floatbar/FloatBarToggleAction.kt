package com.floatbar

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAwareAction

class FloatBarToggleAction : DumbAwareAction("Toggle FloatBar") {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        project.service<FloatBarService>().toggle()
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.getData(PlatformDataKeys.PROJECT) != null
    }
}
