package com.drawing

import java.awt.Color
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JLabel

internal object DrawingStateBinder {
    private val defaultBackground = Color(50, 50, 50)
    private val defaultForeground = Color(220, 220, 220)
    private val defaultBorderColor = Color(100, 100, 100)
    private val disabledBackground = Color(38, 38, 38)
    private val disabledForeground = Color(130, 130, 130)
    private val disabledBorderColor = Color(70, 70, 70)
    private val enabledToggleBackground = Color(70, 105, 75)
    private val enabledToggleBorderColor = Color(130, 190, 135)

    fun applyColorButton(button: JButton, color: Color) {
        val colorHex = toHexColor(color)
        button.background = color
        button.foreground = readableForegroundFor(color)
        button.text = colorHex
        button.toolTipText = "Click to choose color. Selected: $colorHex / rgb(${color.red}, ${color.green}, ${color.blue})"
    }

    fun applyColorStatusLabel(label: JLabel, color: Color) {
        val colorHex = toHexColor(color)
        label.text = ""
        label.background = color
        label.border = BorderFactory.createEmptyBorder()
        label.toolTipText = "Selected color: $colorHex / rgb(${color.red}, ${color.green}, ${color.blue})"
    }

    fun applyRecentColorButton(button: JButton, index: Int, color: Color?, selectedColor: Color) {
        val isSelected = color?.let { hasSameRgb(it, selectedColor) } == true
        button.background = color ?: Color(60, 60, 60)
        button.isEnabled = color != null
        button.border = when {
            isSelected -> BorderFactory.createLineBorder(Color(150, 190, 255), 2)
            color != null -> BorderFactory.createLineBorder(Color(110, 110, 110), 1)
            else -> BorderFactory.createLineBorder(Color(75, 75, 75), 1)
        }
        button.toolTipText = color?.let {
            val colorDescription = "${toHexColor(it)} / rgb(${it.red}, ${it.green}, ${it.blue})"
            if (isSelected) {
                "Selected recent color ${index + 1}: $colorDescription"
            } else {
                "Recent color ${index + 1}: $colorDescription"
            }
        } ?: "Empty recent color slot"
    }

    fun applyGridButton(button: JButton, enabled: Boolean) {
        button.text = if (enabled) "Grid ON" else "Grid OFF"
        button.toolTipText = if (enabled) {
            "Hide the drawing alignment grid"
        } else {
            "Show the drawing alignment grid"
        }
        applyToggleStyle(button, enabled)
    }

    fun applyOverlayButton(button: JButton, enabled: Boolean) {
        button.text = if (enabled) "Overlay ON" else "Overlay OFF"
        button.toolTipText = if (enabled) {
            "Hide the editor drawing overlay. Drawing will remember this choice"
        } else {
            "Show the editor drawing overlay. Drawing will remember this choice"
        }
        applyToggleStyle(button, enabled)
    }

    fun applyPassThroughButton(button: JButton, enabled: Boolean) {
        button.text = if (enabled) "Code ON" else "Code OFF"
        button.toolTipText = if (enabled) {
            "Let the editor receive clicks and drags while drawings stay visible. Drawing will remember this choice"
        } else {
            "Block the editor with the drawing overlay again. Drawing will remember this choice"
        }
        applyToggleStyle(button, enabled)
    }

    fun applyHistoryButton(button: JButton, enabled: Boolean, enabledTooltip: String, disabledTooltip: String) {
        button.isEnabled = enabled
        button.toolTipText = if (enabled) enabledTooltip else disabledTooltip
        if (enabled) {
            applyDefaultStyle(button)
        } else {
            applyDisabledStyle(button)
        }
    }

    fun applyClearButton(button: JButton, hasDrawings: Boolean) {
        button.isEnabled = hasDrawings
        button.toolTipText = if (hasDrawings) {
            "Clear all drawings from the current editor document. You will be asked to confirm first"
        } else {
            "No drawings to clear in the current editor document"
        }
        if (hasDrawings) {
            button.background = Color(105, 55, 55)
            button.foreground = Color.WHITE
            button.border = BorderFactory.createLineBorder(Color(210, 115, 115), 1)
        } else {
            applyDisabledStyle(button)
        }
    }

    fun shapeButtonText(activeTool: DrawingToolMode, selectedShapeName: String): String {
        return when (activeTool) {
            DrawingToolMode.SHAPES -> selectedShapeName
            else -> "Shapes"
        }
    }

    fun applyToolButton(button: JButton, active: Boolean, tool: DrawingToolMode) {
        if (active) {
            val (backgroundColor, borderColor) = toolAccentColors(tool)
            button.background = backgroundColor
            button.foreground = Color.WHITE
            button.border = BorderFactory.createLineBorder(borderColor, 1)
        } else {
            applyDefaultStyle(button)
        }
    }

    fun applyShapeToolButton(button: JButton, active: Boolean) {
        applyToolButton(button, active, DrawingToolMode.SHAPES)
    }

    fun applyTextToolButton(button: JButton, active: Boolean) {
        applyOptionalAccentToolButton(button, active, Color(128, 54, 94), Color(245, 125, 175))
    }

    fun applyBalloonToolButton(button: JButton, active: Boolean) {
        applyOptionalAccentToolButton(button, active, Color(130, 105, 32), Color(245, 220, 90))
    }

    fun applyToolStatusLabel(label: JLabel, activeTool: DrawingToolMode, selectedShapeName: String) {
        val toolText = toolStatusText(activeTool, selectedShapeName)
        val (backgroundColor, borderColor) = toolAccentColors(activeTool)
        label.text = toolText
        label.toolTipText = "$toolText is active"
        label.background = backgroundColor
        label.foreground = Color.WHITE
        label.border = BorderFactory.createLineBorder(borderColor, 1)
    }

    fun toolStatusText(activeTool: DrawingToolMode, selectedShapeName: String): String {
        return when (activeTool) {
            DrawingToolMode.SELECT -> "Tool: Select"
            DrawingToolMode.DRAW -> "Tool: Draw"
            DrawingToolMode.ERASE -> "Tool: Erase"
            DrawingToolMode.FILL -> "Tool: Fill"
            DrawingToolMode.SHAPES -> "Tool: $selectedShapeName"
        }
    }

    fun readableForegroundFor(color: Color): Color {
        return if ((color.red * 299 + color.green * 587 + color.blue * 114) / 1000 < 140) {
            Color.WHITE
        } else {
            Color.BLACK
        }
    }

    fun toHexColor(color: Color): String {
        return "#%02X%02X%02X".format(color.red, color.green, color.blue)
    }

    fun hasSameRgb(left: Color, right: Color): Boolean {
        return left.red == right.red && left.green == right.green && left.blue == right.blue
    }

    private fun applyToggleStyle(button: JButton, enabled: Boolean) {
        if (enabled) {
            button.background = enabledToggleBackground
            button.foreground = Color.WHITE
            button.border = BorderFactory.createLineBorder(enabledToggleBorderColor, 1)
        } else {
            applyDefaultStyle(button)
        }
    }

    private fun applyDefaultStyle(button: JButton) {
        button.background = defaultBackground
        button.foreground = defaultForeground
        button.border = BorderFactory.createLineBorder(defaultBorderColor, 1)
    }

    private fun applyOptionalAccentToolButton(
        button: JButton,
        active: Boolean,
        activeBackground: Color,
        activeBorder: Color
    ) {
        if (active) {
            button.background = activeBackground
            button.foreground = Color.WHITE
            button.border = BorderFactory.createLineBorder(activeBorder, 1)
        } else {
            applyDefaultStyle(button)
        }
    }

    private fun applyDisabledStyle(button: JButton) {
        button.background = disabledBackground
        button.foreground = disabledForeground
        button.border = BorderFactory.createLineBorder(disabledBorderColor, 1)
    }

    private fun toolAccentColors(tool: DrawingToolMode): Pair<Color, Color> {
        return when (tool) {
            DrawingToolMode.SELECT -> Color(48, 96, 118) to Color(130, 215, 240)
            DrawingToolMode.DRAW -> Color(58, 82, 135) to Color(140, 175, 255)
            DrawingToolMode.ERASE -> Color(112, 78, 50) to Color(230, 170, 100)
            DrawingToolMode.FILL -> Color(65, 105, 80) to Color(130, 205, 145)
            DrawingToolMode.SHAPES -> Color(92, 70, 125) to Color(190, 155, 245)
        }
    }
}
