package com.floatbar

import javax.swing.JMenuItem
import javax.swing.JPopupMenu

internal object ShapeMenuFactory {
    fun createShapeMenu(
        selectedShapeKind: ShapeKind,
        onShapeSelected: (ShapeKind) -> Unit
    ): JPopupMenu {
        val menu = JPopupMenu()
        val menuShapes = ShapeKind.entries.filterNot { it == ShapeKind.TEXT || it == ShapeKind.BALLOON }
        for (shapeKind in menuShapes) {
            val isSelected = shapeKind == selectedShapeKind
            val label = if (isSelected) "[selected] ${shapeKind.displayName}" else shapeKind.displayName
            menu.add(JMenuItem(label).apply {
                toolTipText = if (isSelected) {
                    "Currently selected shape: ${shapeKind.displayName}"
                } else {
                    "Switch shape tool to ${shapeKind.displayName}"
                }
                addActionListener { onShapeSelected(shapeKind) }
            })
        }
        return menu
    }
}
