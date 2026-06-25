package com.floatbar.ui

import com.floatbar.ColorClipboardActions
import com.floatbar.ColorDialogTheme
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.awt.Color

class ColorDialogThemeTest {
    @Test
    fun `formats hex and rgb color values`() {
        val color = Color(12, 34, 56)

        assertEquals("#0C2238", ColorDialogTheme.toHexColor(color))
        assertEquals("rgb(12, 34, 56)", ColorDialogTheme.toRgbText(color))
        assertEquals("#0C2238", ColorClipboardActions.hexText(color))
        assertEquals("rgb(12, 34, 56)", ColorClipboardActions.rgbText(color))
    }

    @Test
    fun `rgb comparison ignores alpha`() {
        assertEquals(
            true,
            ColorDialogTheme.hasSameRgb(Color(1, 2, 3, 40), Color(1, 2, 3, 220))
        )
    }

    @Test
    fun `web safe helpers detect and snap classic browser palette colors`() {
        assertEquals(true, ColorDialogTheme.isWebSafeColor(Color(0, 51, 255)))
        assertEquals(false, ColorDialogTheme.isWebSafeColor(Color(12, 34, 56)))
        assertEquals(Color(0, 51, 51), ColorDialogTheme.nearestWebSafeColor(Color(12, 34, 56)))
        assertEquals(Color(255, 255, 255), ColorDialogTheme.nearestWebSafeColor(Color(250, 250, 250)))
    }
}
