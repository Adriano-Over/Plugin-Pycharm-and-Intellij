package com.drawing.ui

import com.drawing.DrawingToolMode
import com.drawing.DrawingStateBinder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Color
import javax.swing.JButton
import javax.swing.JLabel

class DrawingStateBinderTest {
    @Test
    fun `color button shows selected hex and readable foreground`() {
        val button = JButton("Color")

        DrawingStateBinder.applyColorButton(button, Color(12, 34, 56))

        assertEquals("#0C2238", button.text)
        assertEquals(Color.WHITE, button.foreground)
        assertTrue(button.toolTipText.contains("rgb(12, 34, 56)"))
    }

    @Test
    fun `grid and overlay buttons expose on off state through labels and tooltips`() {
        val gridButton = JButton()
        val overlayButton = JButton()
        val passThroughButton = JButton()

        DrawingStateBinder.applyGridButton(gridButton, true)
        DrawingStateBinder.applyOverlayButton(overlayButton, false)
        DrawingStateBinder.applyPassThroughButton(passThroughButton, true)

        assertEquals("Grid ON", gridButton.text)
        assertEquals("Overlay OFF", overlayButton.text)
        assertEquals("Pass-Through ON", passThroughButton.text)
        assertTrue(gridButton.toolTipText.contains("Hide"))
        assertTrue(overlayButton.toolTipText.contains("Show"))
        assertTrue(passThroughButton.toolTipText.contains("editor receive"))
    }

    @Test
    fun `tool status and shape button text follow active tool`() {
        val label = JLabel()

        DrawingStateBinder.applyToolStatusLabel(label, DrawingToolMode.SHAPES, "Document")

        assertEquals("Tool: Document", label.text)
        assertEquals("Document", DrawingStateBinder.shapeButtonText(DrawingToolMode.SHAPES, "Document"))
        assertEquals("Shapes", DrawingStateBinder.shapeButtonText(DrawingToolMode.DRAW, "Document"))

        DrawingStateBinder.applyToolStatusLabel(label, DrawingToolMode.SELECT, "Document")

        assertEquals("Tool: Select", label.text)
    }
}
