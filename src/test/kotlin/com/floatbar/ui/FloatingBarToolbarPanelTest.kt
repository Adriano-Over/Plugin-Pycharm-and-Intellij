package com.floatbar.ui

import com.floatbar.FloatingBarToolbarPanel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class FloatingBarToolbarPanelTest {
    @Test
    fun `toolbar panel exposes all controls used by FloatingBar`() {
        val toolbarView = FloatingBarToolbarPanel(
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
        assertEquals("Color: #000000", toolbarView.controls.colorStatusLabel.text)
        assertEquals("Select", toolbarView.controls.selectButton.text)
        assertEquals("Draw", toolbarView.controls.drawingButton.text)
        assertEquals("Erase", toolbarView.controls.erasingButton.text)
        assertEquals("Fill", toolbarView.controls.fillButton.text)
        assertEquals("Text", toolbarView.controls.textButton.text)
        assertEquals("Balloon", toolbarView.controls.balloonButton.text)
        assertEquals("Shapes", toolbarView.controls.shapeButton.text)
        assertEquals("Overlay OFF", toolbarView.controls.overlayButton.text)
        assertEquals("Pass-Through OFF", toolbarView.controls.passThroughButton.text)
        assertEquals("Grid ON", toolbarView.controls.gridButton.text)
        assertEquals(6, toolbarView.controls.recentColorButtons.size)
    }
}
