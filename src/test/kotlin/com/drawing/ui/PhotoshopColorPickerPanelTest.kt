package com.drawing.ui

import com.drawing.PhotoshopColorPickerPanel
import java.awt.Color
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PhotoshopColorPickerPanelTest {
    @Test
    fun `initial color populates hsb rgb and hex values`() {
        val panel = PhotoshopColorPickerPanel(Color.RED) {}

        assertEquals("0", panel.valueText("H"))
        assertEquals("100", panel.valueText("S"))
        assertEquals("100", panel.valueText("B"))
        assertEquals("255", panel.valueText("R"))
        assertEquals("0", panel.valueText("G"))
        assertEquals("0", panel.valueText("BLUE"))
        assertEquals("FF0000", panel.valueText("HEX"))
    }

    @Test
    fun `external selected color updates rgb and hex values without firing callback`() {
        val selected = mutableListOf<Color>()
        val panel = PhotoshopColorPickerPanel(Color.RED) { selected += it }

        panel.setSelectedColor(Color(12, 34, 56))

        assertEquals(emptyList<Color>(), selected)
        assertEquals(Color(12, 34, 56), panel.currentColor())
        assertEquals("12", panel.valueText("R"))
        assertEquals("34", panel.valueText("G"))
        assertEquals("56", panel.valueText("BLUE"))
        assertEquals("0C2238", panel.valueText("HEX"))
    }

    @Test
    fun `hex entry updates selected color and notifies callback`() {
        val selected = mutableListOf<Color>()
        val panel = PhotoshopColorPickerPanel(Color.RED) { selected += it }

        panel.commitHexForTest("#3366CC")

        assertEquals(Color(51, 102, 204), panel.currentColor())
        assertEquals(listOf(Color(51, 102, 204)), selected)
        assertEquals("51", panel.valueText("R"))
        assertEquals("102", panel.valueText("G"))
        assertEquals("204", panel.valueText("BLUE"))
        assertEquals("3366CC", panel.valueText("HEX"))
    }

    @Test
    fun `short hex entry expands to full rgb color`() {
        val panel = PhotoshopColorPickerPanel(Color.BLACK) {}

        panel.commitHexForTest("0F8")

        assertEquals(Color(0, 255, 136), panel.currentColor())
        assertEquals("00FF88", panel.valueText("HEX"))
    }

    @Test
    fun `only web colors snaps typed color to nearest web safe rgb value`() {
        val selected = mutableListOf<Color>()
        val panel = PhotoshopColorPickerPanel(Color.BLACK) { selected += it }

        panel.setOnlyWebColorsForTest(true)
        panel.commitHexForTest("0C2238")

        assertEquals(Color(0, 51, 51), panel.currentColor())
        assertEquals(Color(0, 51, 51), selected.last())
        assertEquals("003333", panel.valueText("HEX"))
    }
}
