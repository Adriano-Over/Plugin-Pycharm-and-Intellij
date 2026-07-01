package com.drawing

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import javax.swing.ScrollPaneConstants

class DrawingToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun shouldBeAvailable(project: Project): Boolean = true

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        DrawingToolWindowLayout.applyRightDockDefaults(toolWindow)
        val panel = DrawingToolWindowPanel(project, toolWindow)
        Disposer.register(project, panel)
        val preferredDockSize = DrawingToolWindowLayout.preferredDockSize(panel.preferredSize.height)
        val scrollPane = JBScrollPane(panel).apply {
            preferredSize = preferredDockSize
            minimumSize = panel.minimumSize
            viewport.preferredSize = panel.preferredSize
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
            border = null
        }

        val content = ContentFactory.getInstance()
            .createContent(scrollPane, "", false)
        toolWindow.contentManager.addContent(content)
        DrawingToolWindowLayout.applyRightDockDefaults(toolWindow)
    }
}
