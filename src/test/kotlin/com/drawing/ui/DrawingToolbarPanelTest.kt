package com.drawing.ui

import com.drawing.DrawingToolbarPanel
import com.drawing.DrawingMetrics
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import javax.swing.JButton
import javax.swing.JPanel

class DrawingToolbarPanelTest {
    @Test
    fun `toolbar panel exposes all controls used by Drawing`() {
        val toolbarView = DrawingToolbarPanel(
            restoreToolbarStyles = {},
            onHide = {},
            onOverlay = {},
            onPassThrough = {},
            onSelect = {},
            onDraw = {},
            onErase = {},
            onColor = {},
            onFill = {},
            onShapes = {},
            onText = {},
            onBalloon = {},
            onGrid = {},
            onUndo = {},
            onRedo = {},
            onClear = {},
            onRecentColor = {}
        ).create()

        assertNotNull(toolbarView.rootPanel)
        assertNotNull(toolbarView.headerPanel)
        assertEquals("Tool: Draw", toolbarView.controls.toolStatusLabel.text)
        assertEquals("", toolbarView.controls.colorStatusLabel.text)
        assertEquals(DrawingMetrics.COLOR_STATUS_HEIGHT, toolbarView.controls.colorStatusLabel.preferredSize.height)
        assertEquals(DrawingMetrics.COLOR_STATUS_HEIGHT, toolbarView.controls.colorStatusLabel.maximumSize.height)
        assertEquals("Select", toolbarView.controls.selectButton.text)
        assertEquals("Draw", toolbarView.controls.drawingButton.text)
        assertEquals("Erase", toolbarView.controls.erasingButton.text)
        assertEquals("Fill", toolbarView.controls.fillButton.text)
        assertEquals("Text", toolbarView.controls.textButton.text)
        assertEquals("Balloon", toolbarView.controls.balloonButton.text)
        assertEquals("Shapes", toolbarView.controls.shapeButton.text)
        assertEquals("Overlay OFF", toolbarView.controls.overlayButton.text)
        assertEquals("Code OFF", toolbarView.controls.passThroughButton.text)
        assertEquals("Grid ON", toolbarView.controls.gridButton.text)
        assertEquals(6, toolbarView.controls.recentColorButtons.size)

        val buttonColumn = toolbarView.rootPanel.components.filterIsInstance<JPanel>()[1]
        assertEquals(toolbarView.controls.colorStatusLabel, buttonColumn.components[0])
        assertEquals(toolbarView.controls.toolStatusLabel, buttonColumn.components[2])

        val toolButtonTexts = buttonColumn.components
            .filterIsInstance<JButton>()
            .map { it.text }
            .take(7)
        assertEquals(
            listOf("Draw", "Erase", "Fill", "Shapes", "Text", "Balloon", "Select"),
            toolButtonTexts
        )

        val allButtonTexts = buttonColumn.components
            .filterIsInstance<JButton>()
            .map { it.text }
        val viewStart = allButtonTexts.indexOf("Overlay OFF")
        assertEquals(
            listOf("Overlay OFF", "Grid ON", "Code OFF"),
            allButtonTexts.drop(viewStart).take(3)
        )
    }
}
