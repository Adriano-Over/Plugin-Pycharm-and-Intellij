package com.floatbar

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory

class FloatBarStatusBarWidgetFactory : StatusBarWidgetFactory {

    override fun getId(): String = "FloatBarStatusBarWidget"

    override fun getDisplayName(): String = "FloatBar"

    override fun isAvailable(project: Project): Boolean = true

    override fun createWidget(project: Project): StatusBarWidget {
        return FloatBarStatusBarWidget(project)
    }

    override fun disposeWidget(widget: StatusBarWidget) {
        widget.dispose()
    }

    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true
}