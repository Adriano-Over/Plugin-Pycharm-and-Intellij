package com.floatbar

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import java.awt.Dimension
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton

class FloatingBarToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun shouldBeAvailable(project: Project): Boolean = true

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val manager = project.getService(FloatingBarManagerService::class.java)

        val panel = JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = javax.swing.BorderFactory.createEmptyBorder(12, 12, 12, 12)

            add(JBLabel("Floatbar"))
            add(Box.createRigidArea(Dimension(0, 8)))
            add(JBLabel("Use this side tab to reopen or hide the floating bar."))
            add(Box.createRigidArea(Dimension(0, 12)))

            add(JButton("Open Floating Bar").apply {
                alignmentX = 0f
                addActionListener { manager.showBar() }
            })
            add(Box.createRigidArea(Dimension(0, 8)))

            add(JButton("Hide Floating Bar").apply {
                alignmentX = 0f
                addActionListener { manager.hideBar() }
            })
            add(Box.createRigidArea(Dimension(0, 8)))

            add(JButton("Toggle Floating Bar").apply {
                alignmentX = 0f
                addActionListener { manager.toggleBar() }
            })
        }

        val content = ContentFactory.getInstance()
            .createContent(JBScrollPane(panel), "", false)
        toolWindow.contentManager.addContent(content)
    }
}
