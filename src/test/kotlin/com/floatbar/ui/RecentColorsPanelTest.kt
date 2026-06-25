package com.floatbar.ui

import com.floatbar.RecentColorsPanel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Color

class RecentColorsPanelTest {
    @Test
    fun `recent colors are deduplicated and capped`() {
        val red = Color.RED
        val blue = Color.BLUE
        val panel = RecentColorsPanel(
            recentColors = listOf(red, blue, red, Color.GREEN, Color.YELLOW, Color.CYAN, Color.MAGENTA, Color.ORANGE),
            onColorSelected = {}
        )

        assertEquals(6, panel.colorsSnapshot.size)
        assertEquals(listOf(red, blue, Color.GREEN, Color.YELLOW, Color.CYAN, Color.MAGENTA), panel.colorsSnapshot)
    }

    @Test
    fun `recent color button invokes callback and marks selected color`() {
        val selected = mutableListOf<Color>()
        val panel = RecentColorsPanel(
            recentColors = listOf(Color.RED, Color.BLUE),
            onColorSelected = { selected += it }
        )

        panel.buttonAt(1).doClick()
        panel.updateSelectedColor(Color.BLUE)

        assertEquals(listOf(Color.BLUE), selected)
        assertTrue(panel.buttonAt(1).toolTipText.startsWith("Selected recent color 2"))
        assertEquals(false, panel.buttonAt(4).isEnabled)
    }
}
