package com.drawing

import java.awt.Color
import java.awt.Font
import javax.swing.BorderFactory

internal object ColorDialogTheme {
    const val RECENT_COLOR_SLOT_COUNT = 6
    const val SIDE_PANEL_WIDTH = 156
    const val PREVIEW_SWATCH_SIZE = 58
    const val RECENT_SWATCH_SIZE = 32

    val background = Color(25, 28, 36)
    val panel = Color(34, 39, 50)
    val panelAlt = Color(39, 45, 58)
    val header = Color(30, 38, 58)
    val headerBorder = Color(78, 98, 145)
    val text = Color(238, 242, 250)
    val mutedText = Color(168, 179, 200)
    val sectionText = Color(178, 190, 215)
    val border = Color(82, 95, 120)
    val accent = Color(92, 138, 255)
    val accentHover = Color(112, 158, 255)
    val button = Color(47, 54, 68)
    val buttonHover = Color(55, 64, 84)
    val ok = Color(67, 105, 205)
    val okHover = Color(80, 125, 235)
    val purple = Color(86, 76, 135)
    val purpleHover = Color(102, 90, 160)
    val emptySwatch = Color(44, 49, 60)
    val valueChip = Color(25, 31, 44)
    val valueChipBorder = Color(86, 105, 145)
    val success = Color(132, 220, 155)

    fun createSectionBorder(title: String) = BorderFactory.createTitledBorder(
        BorderFactory.createLineBorder(border, 1),
        title
    ).apply {
        titleColor = sectionText
        titleFont = Font("Dialog", Font.BOLD, 11)
    }

    fun toHexColor(color: Color): String {
        return "#%02X%02X%02X".format(color.red, color.green, color.blue)
    }

    fun toRgbText(color: Color): String {
        return "rgb(${color.red}, ${color.green}, ${color.blue})"
    }

    fun hasSameRgb(left: Color, right: Color): Boolean {
        return left.red == right.red && left.green == right.green && left.blue == right.blue
    }

    fun isWebSafeColor(color: Color): Boolean {
        return color.red % 51 == 0 && color.green % 51 == 0 && color.blue % 51 == 0
    }

    fun nearestWebSafeColor(color: Color): Color {
        return Color(
            nearestWebSafeComponent(color.red),
            nearestWebSafeComponent(color.green),
            nearestWebSafeComponent(color.blue)
        )
    }

    fun readableBorderFor(color: Color): Color {
        return if ((color.red * 299 + color.green * 587 + color.blue * 114) / 1000 < 140) {
            Color(232, 238, 255)
        } else {
            Color(35, 42, 58)
        }
    }

    private fun nearestWebSafeComponent(value: Int): Int {
        return ((value + 25) / 51).coerceIn(0, 5) * 51
    }
}
