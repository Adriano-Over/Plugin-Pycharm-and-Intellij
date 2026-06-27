package com.drawing.ui

import com.drawing.ColorPreviewPanel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Color

class ColorPreviewPanelTest {
    @Test
    fun `preview panel updates hex and rgb labels`() {
        val panel = ColorPreviewPanel(
            initialColor = Color.BLACK,
            onCopyHex = {},
            onCopyRgb = {}
        )

        panel.updateColor(Color(12, 34, 56))

        assertEquals("#0C2238", panel.currentHexText())
        assertEquals("rgb(12, 34, 56)", panel.currentRgbText())
    }

    @Test
    fun `preview copy buttons invoke supplied callbacks`() {
        var copiedHex = false
        var copiedRgb = false
        val panel = ColorPreviewPanel(
            initialColor = Color.BLACK,
            onCopyHex = { copiedHex = true },
            onCopyRgb = { copiedRgb = true }
        )

        panel.components
            .flatMap { component -> (component as? java.awt.Container)?.components?.toList().orEmpty() }
            .filterIsInstance<javax.swing.JButton>()
            .forEach { it.doClick() }

        assertTrue(copiedHex)
        assertTrue(copiedRgb)
    }
}
