package com.drawing.ui

import com.drawing.ShapeKind
import com.drawing.ShapeMenuFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import javax.swing.JMenuItem

class ShapeMenuFactoryTest {
    @Test
    fun `shape menu marks selected shape and calls selection callback`() {
        val selected = mutableListOf<ShapeKind>()
        val menu = ShapeMenuFactory.createShapeMenu(ShapeKind.DOCUMENT) { shapeKind ->
            selected += shapeKind
        }

        assertEquals(ShapeKind.entries.size - 2, menu.componentCount)
        val documentItem = menu.components
            .filterIsInstance<JMenuItem>()
            .single { it.text == "[selected] ${ShapeKind.DOCUMENT.displayName}" }

        assertEquals("[selected] ${ShapeKind.DOCUMENT.displayName}", documentItem.text)
        assertTrue(documentItem.toolTipText.contains("Currently selected"))
        assertTrue(menu.components.filterIsInstance<JMenuItem>().none { it.text == ShapeKind.TEXT.displayName })
        assertTrue(menu.components.filterIsInstance<JMenuItem>().none { it.text == ShapeKind.BALLOON.displayName })

        documentItem.doClick()

        assertEquals(listOf(ShapeKind.DOCUMENT), selected)
    }
}
