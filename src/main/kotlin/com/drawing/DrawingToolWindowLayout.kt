package com.drawing

import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowAnchor
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.SwingUtilities
import kotlin.math.abs

internal object DrawingToolWindowLayout {
    private const val DOCK_WIDTH_PADDING = 24
    val defaultDockWidth: Int = DrawingMetrics.BAR_WIDTH + DOCK_WIDTH_PADDING

    fun applyRightDockDefaults(toolWindow: ToolWindow) {
        runCatching {
            toolWindow.setAnchor(ToolWindowAnchor.RIGHT, null)
        }
        applyPreferredDockWidth(toolWindow)
    }

    fun preferredDockSize(height: Int): Dimension {
        return Dimension(defaultDockWidth, height.coerceAtLeast(1))
    }

    private fun applyPreferredDockWidth(toolWindow: ToolWindow) {
        val component = toolWindow.component
        component.preferredSize = preferredDockSize(component.preferredSize.height)
        component.revalidate()

        SwingUtilities.invokeLater {
            val dockComponent = toolWindow.component
            dockComponent.preferredSize = preferredDockSize(dockComponent.preferredSize.height)
            stretchToPreferredWidth(toolWindow, dockComponent)
            dockComponent.revalidate()
            dockComponent.parent?.revalidate()
        }
    }

    private fun stretchToPreferredWidth(toolWindow: ToolWindow, component: JComponent) {
        val currentWidth = component.width
        if (currentWidth <= 0) return

        val delta = defaultDockWidth - currentWidth
        if (abs(delta) <= 2) return

        val stretchWidth = toolWindow.javaClass.methods.firstOrNull { method ->
            method.name == "stretchWidth" &&
                method.parameterTypes.size == 1 &&
                method.parameterTypes[0] == Integer.TYPE
        } ?: return

        runCatching {
            stretchWidth.invoke(toolWindow, delta)
        }
    }
}
