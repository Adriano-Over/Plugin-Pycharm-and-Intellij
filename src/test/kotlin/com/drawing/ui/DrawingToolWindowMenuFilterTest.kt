package com.drawing.ui

import com.drawing.DrawingToolWindowMenuFilter
import javax.swing.JMenu
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DrawingToolWindowMenuFilterTest {
    @Test
    fun `view mode menu keeps only dock pinned and float`() {
        val popup = JPopupMenu()
        val viewMode = JMenu("View Mode")
        listOf("Dock Pinned", "Dock Unpinned", "Undock", "Float", "Window").forEach { label ->
            viewMode.add(JMenuItem(label))
        }
        popup.add(viewMode)

        DrawingToolWindowMenuFilter.filterViewModeEntries(popup)

        assertEquals(listOf("Dock Pinned", "Float"), viewMode.menuComponents.map { (it as JMenuItem).text })
    }

    @Test
    fun `direct view mode popup keeps only dock pinned and float`() {
        val popup = JPopupMenu()
        listOf("Dock Pinned", "Dock Unpinned", "Undock", "Float", "Window").forEach { label ->
            popup.add(JMenuItem(label))
        }

        DrawingToolWindowMenuFilter.filterViewModeEntries(popup)

        assertEquals(
            listOf("Dock Pinned", "Float"),
            popup.components.filterIsInstance<JMenuItem>().map { it.text }
        )
    }
}
