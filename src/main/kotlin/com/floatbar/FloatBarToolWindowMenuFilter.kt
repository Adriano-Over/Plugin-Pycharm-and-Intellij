package com.floatbar

import com.intellij.openapi.Disposable
import com.intellij.openapi.wm.ToolWindow
import java.awt.AWTEvent
import java.awt.Component
import java.awt.Container
import java.awt.Toolkit
import java.awt.event.AWTEventListener
import java.awt.event.ComponentEvent
import java.awt.event.ContainerEvent
import javax.swing.JMenu
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.SwingUtilities

internal class FloatBarToolWindowMenuFilter(
    private val toolWindow: ToolWindow
) : Disposable {
    private val listener = AWTEventListener { event ->
        if (!shouldInspect(event)) return@AWTEventListener
        val component = event.source as? Component ?: return@AWTEventListener
        SwingUtilities.invokeLater {
            if (isFloatBarToolWindowActive()) {
                filterPopupMenus(component)
            }
        }
    }

    init {
        Toolkit.getDefaultToolkit().addAWTEventListener(
            listener,
            AWTEvent.COMPONENT_EVENT_MASK or AWTEvent.CONTAINER_EVENT_MASK
        )
    }

    override fun dispose() {
        Toolkit.getDefaultToolkit().removeAWTEventListener(listener)
    }

    private fun shouldInspect(event: AWTEvent): Boolean {
        return event.id == ComponentEvent.COMPONENT_SHOWN ||
            event.id == ContainerEvent.COMPONENT_ADDED
    }

    private fun isFloatBarToolWindowActive(): Boolean {
        return runCatching { toolWindow.isActive || toolWindow.isVisible }.getOrDefault(false)
    }

    private fun filterPopupMenus(component: Component) {
        when (component) {
            is JPopupMenu -> filterViewModeEntries(component)
            is Container -> component.components.forEach(::filterPopupMenus)
        }
    }

    companion object {
        private val allowedViewModes = setOf("Dock Pinned", "Float")
        private val hiddenViewModes = setOf("Dock Unpinned", "Undock", "Window")

        internal fun filterViewModeEntries(popupMenu: JPopupMenu) {
            filterDirectViewModeItems(popupMenu)
            popupMenu.components.forEach { component ->
                when (component) {
                    is JMenu -> {
                        if (normalizedText(component.text) == "View Mode") {
                            filterMenu(component)
                        }
                        filterViewModeEntries(component.popupMenu)
                    }
                    is JPopupMenu -> filterViewModeEntries(component)
                }
            }
        }

        private fun filterMenu(menu: JMenu) {
            filterDirectViewModeItems(menu.popupMenu)
        }

        private fun filterDirectViewModeItems(popupMenu: JPopupMenu) {
            val directItems = popupMenu.components.filterIsInstance<JMenuItem>()
            val directTexts = directItems.mapNotNull { normalizedText(it.text) }.toSet()
            val looksLikeViewModeMenu = allowedViewModes.any { it in directTexts } &&
                hiddenViewModes.any { it in directTexts }

            if (!looksLikeViewModeMenu) return

            directItems
                .filter { normalizedText(it.text) in hiddenViewModes }
                .forEach { popupMenu.remove(it) }

            popupMenu.revalidate()
            popupMenu.repaint()
        }

        private fun normalizedText(text: String?): String? {
            return text
                ?.filter { it.isLetterOrDigit() || it.isWhitespace() }
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        }
    }
}
