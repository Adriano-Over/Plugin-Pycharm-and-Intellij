package com.floatbar

import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.GridLayout
import java.awt.Insets
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JPanel

internal class RecentColorsPanel(
    recentColors: List<Color>,
    private val onColorSelected: (Color) -> Unit
) : JPanel(BorderLayout()) {
    private val recentPanel = JPanel(GridLayout(2, 3, 6, 6))
    private val recentColorButtons = mutableListOf<JButton>()
    internal val colorsSnapshot: List<Color> = recentColors
        .distinctBy { it.rgb }
        .take(ColorDialogTheme.RECENT_COLOR_SLOT_COUNT)

    init {
        isOpaque = true
        background = ColorDialogTheme.panelAlt
        border = ColorDialogTheme.createSectionBorder("Recent")
        recentPanel.isOpaque = false
        recentPanel.border = BorderFactory.createEmptyBorder(2, 2, 2, 2)
        add(recentPanel, BorderLayout.CENTER)
        refreshButtons()
    }

    fun updateSelectedColor(selectedColor: Color) {
        recentColorButtons.forEachIndexed { index, button ->
            val color = colorsSnapshot.getOrNull(index)
            val isSelected = color?.let { ColorDialogTheme.hasSameRgb(it, selectedColor) } == true
            button.border = when {
                isSelected -> BorderFactory.createLineBorder(ColorDialogTheme.accentHover, 2)
                color != null -> BorderFactory.createLineBorder(Color(118, 132, 160), 1)
                else -> BorderFactory.createLineBorder(Color(66, 74, 90), 1)
            }
            button.toolTipText = color?.let {
                val hex = ColorDialogTheme.toHexColor(it)
                val rgb = ColorDialogTheme.toRgbText(it)
                if (isSelected) {
                    "Selected recent color ${index + 1}: $hex / $rgb"
                } else {
                    "Recent color ${index + 1}: $hex / $rgb"
                }
            } ?: "Empty recent color slot"
        }
    }

    internal fun buttonAt(index: Int): JButton = recentColorButtons[index]

    private fun refreshButtons() {
        recentPanel.removeAll()
        recentColorButtons.clear()
        repeat(ColorDialogTheme.RECENT_COLOR_SLOT_COUNT) { index ->
            val color = colorsSnapshot.getOrNull(index)
            val swatch = SolidColorButton().apply {
                preferredSize = Dimension(ColorDialogTheme.RECENT_SWATCH_SIZE, ColorDialogTheme.RECENT_SWATCH_SIZE)
                minimumSize = Dimension(ColorDialogTheme.RECENT_SWATCH_SIZE, ColorDialogTheme.RECENT_SWATCH_SIZE)
                maximumSize = Dimension(ColorDialogTheme.RECENT_SWATCH_SIZE, ColorDialogTheme.RECENT_SWATCH_SIZE)
                isFocusPainted = false
                isOpaque = false
                isContentAreaFilled = false
                margin = Insets(0, 0, 0, 0)
                background = color ?: ColorDialogTheme.emptySwatch
                isEnabled = color != null
                cursor = Cursor.getPredefinedCursor(
                    if (color == null) Cursor.DEFAULT_CURSOR else Cursor.HAND_CURSOR
                )
                if (color != null) {
                    addActionListener { onColorSelected(color) }
                }
            }
            recentColorButtons += swatch
            recentPanel.add(swatch)
        }
        recentPanel.revalidate()
        recentPanel.repaint()
    }
}
