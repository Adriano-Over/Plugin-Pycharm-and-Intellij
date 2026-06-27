package com.drawing

import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.GridLayout
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.Timer

internal class ColorPreviewPanel(
    initialColor: Color,
    currentColor: Color = initialColor,
    private val onCopyHex: () -> Unit,
    private val onCopyRgb: () -> Unit
) : JPanel() {
    private val currentSwatch = JPanel()
    private val previewSwatch = JPanel()
    private val hexValueLabel = JLabel("", SwingConstants.CENTER)
    private val rgbValueLabel = JLabel("", SwingConstants.CENTER)
    private val copyStatusLabel = JLabel(" ", SwingConstants.CENTER)
    private var copyStatusTimer: Timer? = null

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = true
        background = ColorDialogTheme.panelAlt
        border = ColorDialogTheme.createSectionBorder("Selected")

        addCenteredLabel("PREVIEW", Font.BOLD, 9, ColorDialogTheme.sectionText)
        add(Box.createVerticalStrut(6))
        configureColorSwatch(currentSwatch, currentColor, "Current color")
        configureColorSwatch(previewSwatch, initialColor, "New color")
        add(createSwatchComparisonRow())
        add(Box.createVerticalStrut(8))
        configureValueLabels()
        add(hexValueLabel)
        add(Box.createVerticalStrut(4))
        add(rgbValueLabel)
        add(Box.createVerticalStrut(8))
        add(createCopyButtonsRow())
        add(Box.createVerticalStrut(5))
        configureCopyStatusLabel()
        add(copyStatusLabel)
        updateColor(initialColor)
    }

    fun updateColor(color: Color) {
        val hex = ColorDialogTheme.toHexColor(color)
        val rgb = ColorDialogTheme.toRgbText(color)
        previewSwatch.background = color
        previewSwatch.toolTipText = "New color: $hex / $rgb"
        previewSwatch.border = BorderFactory.createLineBorder(ColorDialogTheme.readableBorderFor(color), 2)
        hexValueLabel.text = hex
        rgbValueLabel.text = rgb
    }

    fun clearCopyStatus() {
        copyStatusLabel.text = " "
    }

    fun showCopyFeedback(text: String) {
        copyStatusTimer?.stop()
        copyStatusLabel.text = text
        copyStatusLabel.foreground = ColorDialogTheme.success
        copyStatusTimer = Timer(1200) {
            copyStatusLabel.text = " "
        }.apply {
            isRepeats = false
            start()
        }
    }

    internal fun currentHexText(): String = hexValueLabel.text

    internal fun currentRgbText(): String = rgbValueLabel.text

    private fun configureColorSwatch(swatch: JPanel, color: Color, label: String) {
        swatch.apply {
            preferredSize = Dimension(ColorDialogTheme.PREVIEW_SWATCH_SIZE, ColorDialogTheme.PREVIEW_SWATCH_SIZE)
            minimumSize = Dimension(ColorDialogTheme.PREVIEW_SWATCH_SIZE, ColorDialogTheme.PREVIEW_SWATCH_SIZE)
            maximumSize = Dimension(ColorDialogTheme.PREVIEW_SWATCH_SIZE, ColorDialogTheme.PREVIEW_SWATCH_SIZE)
            background = color
            border = BorderFactory.createLineBorder(ColorDialogTheme.accent, 2)
            toolTipText = "$label: ${ColorDialogTheme.toHexColor(color)} / ${ColorDialogTheme.toRgbText(color)}"
            alignmentX = CENTER_ALIGNMENT
        }
    }

    private fun createSwatchComparisonRow(): JPanel {
        return JPanel(GridLayout(1, 2, 6, 0)).apply {
            isOpaque = false
            alignmentX = CENTER_ALIGNMENT
            preferredSize = Dimension(128, ColorDialogTheme.PREVIEW_SWATCH_SIZE + 18)
            maximumSize = Dimension(128, ColorDialogTheme.PREVIEW_SWATCH_SIZE + 18)
            add(createLabeledSwatch("Current", currentSwatch))
            add(createLabeledSwatch("New", previewSwatch))
        }
    }

    private fun createLabeledSwatch(label: String, swatch: JPanel): JPanel {
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(JLabel(label, SwingConstants.CENTER).apply {
                alignmentX = CENTER_ALIGNMENT
                font = Font("Dialog", Font.PLAIN, 9)
                foreground = ColorDialogTheme.mutedText
            })
            add(Box.createVerticalStrut(3))
            add(swatch)
        }
    }

    private fun configureValueLabels() {
        hexValueLabel.apply {
            alignmentX = CENTER_ALIGNMENT
            preferredSize = Dimension(116, 22)
            maximumSize = Dimension(116, 22)
            font = Font("Dialog", Font.BOLD, 12)
            isOpaque = true
            background = ColorDialogTheme.valueChip
            foreground = ColorDialogTheme.text
            border = BorderFactory.createLineBorder(ColorDialogTheme.valueChipBorder, 1)
        }
        rgbValueLabel.apply {
            alignmentX = CENTER_ALIGNMENT
            preferredSize = Dimension(116, 20)
            maximumSize = Dimension(116, 20)
            font = Font("Dialog", Font.PLAIN, 10)
            isOpaque = true
            background = ColorDialogTheme.valueChip
            foreground = ColorDialogTheme.mutedText
            border = BorderFactory.createLineBorder(Color(70, 83, 108), 1)
        }
    }

    private fun createCopyButtonsRow(): JPanel {
        return JPanel(GridLayout(1, 2, 6, 0)).apply {
            isOpaque = false
            alignmentX = CENTER_ALIGNMENT
            preferredSize = Dimension(116, 24)
            maximumSize = Dimension(116, 24)
            add(ColorDialogButtonFactory.createCompactActionButton("Copy HEX", onCopyHex).apply {
                toolTipText = "Copy the selected color as #RRGGBB"
            })
            add(ColorDialogButtonFactory.createCompactActionButton("Copy RGB", onCopyRgb).apply {
                toolTipText = "Copy the selected color as rgb(r, g, b)"
            })
        }
    }

    private fun configureCopyStatusLabel() {
        copyStatusLabel.apply {
            alignmentX = CENTER_ALIGNMENT
            preferredSize = Dimension(116, 16)
            maximumSize = Dimension(116, 16)
            font = Font("Dialog", Font.PLAIN, 10)
            foreground = ColorDialogTheme.success
            toolTipText = "Copy status"
        }
    }

    private fun addCenteredLabel(text: String, fontStyle: Int, fontSize: Int, color: Color) {
        add(JLabel(text, SwingConstants.CENTER).apply {
            alignmentX = CENTER_ALIGNMENT
            font = Font("Dialog", fontStyle, fontSize)
            foreground = color
        })
    }
}
