package com.floatbar

import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Window
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JColorChooser
import javax.swing.JDialog
import javax.swing.JPanel
import javax.swing.JTabbedPane
import javax.swing.SwingUtilities
import javax.swing.event.ChangeListener

private const val RECENT_COLOR_SLOT_COUNT = 6

class ColorPickerDialog(
    owner: Window,
    initialColor: Color,
    recentColors: List<Color>,
    private val onChosen: (Color) -> Unit
) : JDialog(owner, "Choose Drawing Color", ModalityType.MODELESS) {

    private var selectedColor: Color = initialColor
    private val previewSwatch = JPanel()
    private val recentPanel = JPanel()
    private val recentColorButtons = mutableListOf<JButton>()
    private val recentColorsSnapshot = recentColors.toList()
    private val palettePanel = PalettePanel(initialColor) { color ->
        applySelectedColor(color)
    }
    private val advancedChooser = JColorChooser(initialColor).apply {
        setPreviewPanel(JPanel())
        selectionModel.addChangeListener(ChangeListener {
            applySelectedColor(this.color)
        })
    }

    init {
        layout = BorderLayout(12, 12)
        contentPane.background = Color(52, 55, 61)
        rootPane.border = BorderFactory.createEmptyBorder(12, 12, 12, 12)

        previewSwatch.preferredSize = Dimension(48, 48)
        previewSwatch.minimumSize = Dimension(48, 48)
        previewSwatch.maximumSize = Dimension(48, 48)
        previewSwatch.background = initialColor
        previewSwatch.border = BorderFactory.createLineBorder(Color(210, 210, 210), 1)

        val centerTabs = JTabbedPane().apply {
            addTab("Palette", palettePanel)
            addTab("Advanced", advancedChooser)
        }

        val rightPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            preferredSize = Dimension(120, 0)
        }

        val recentContainer = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(Color(120, 120, 120), 1),
                "Recent"
            )
        }

        recentPanel.layout = java.awt.GridLayout(2, 3, 4, 4)
        recentPanel.isOpaque = false
        recentContainer.add(recentPanel, BorderLayout.CENTER)
        refreshRecentButtons()

        val eyeDropperButton = JButton("Eyedrop").apply {
            alignmentX = CENTER_ALIGNMENT
            addActionListener {
                val picker = this@ColorPickerDialog
                val overlay = EyeDropperOverlay(
                    onPicked = { sampled ->
                        applySelectedColor(sampled)
                        palettePanel.setSelectedColor(sampled)
                        advancedChooser.color = sampled
                    },
                    onClosed = {
                        picker.isVisible = true
                        picker.toFront()
                    },
                    onError = {
                        picker.isVisible = true
                        picker.toFront()
                    }
                )
                picker.isVisible = false
                SwingUtilities.invokeLater { overlay.showOverlay() }
            }
        }

        rightPanel.add(recentContainer)
        rightPanel.add(Box.createVerticalStrut(12))
        rightPanel.add(previewSwatch)
        rightPanel.add(Box.createVerticalStrut(12))
        rightPanel.add(eyeDropperButton)
        rightPanel.add(Box.createVerticalGlue())

        val bottomPanel = JPanel(FlowLayout(FlowLayout.RIGHT)).apply {
            isOpaque = false
        }

        val okButton = JButton("OK").apply {
            addActionListener {
                onChosen(selectedColor)
                dispose()
            }
        }

        val cancelButton = JButton("Cancel").apply {
            addActionListener { dispose() }
        }

        bottomPanel.add(okButton)
        bottomPanel.add(cancelButton)

        add(centerTabs, BorderLayout.CENTER)
        add(rightPanel, BorderLayout.EAST)
        add(bottomPanel, BorderLayout.SOUTH)

        minimumSize = Dimension(760, 520)
        size = Dimension(900, 620)
        setLocationRelativeTo(owner)
        defaultCloseOperation = DISPOSE_ON_CLOSE

        addWindowListener(object : WindowAdapter() {
            override fun windowClosed(e: WindowEvent?) {
                owner.toFront()
            }
        })
    }

    private fun refreshRecentButtons() {
        recentPanel.removeAll()
        recentColorButtons.clear()

        repeat(RECENT_COLOR_SLOT_COUNT) { index ->
            val color = recentColorsSnapshot.getOrNull(index)
            val swatch = JButton().apply {
                preferredSize = Dimension(24, 24)
                isFocusPainted = false
                margin = java.awt.Insets(0, 0, 0, 0)
                background = color ?: Color(0, 0, 0, 0)
                border = BorderFactory.createLineBorder(Color(180, 180, 180), 1)
                isEnabled = color != null
                toolTipText = color?.let { "Recent color ${index + 1}: #${it.red.toString(16).padStart(2, '0').uppercase()}${it.green.toString(16).padStart(2, '0').uppercase()}${it.blue.toString(16).padStart(2, '0').uppercase()}" }
                    ?: "Empty recent color slot"
                if (color != null) {
                    addActionListener {
                        applySelectedColor(color)
                        palettePanel.setSelectedColor(color)
                        advancedChooser.color = color
                    }
                }
            }
            recentColorButtons += swatch
            recentPanel.add(swatch)
        }

        updateRecentSelectionBorders()
        recentPanel.revalidate()
        recentPanel.repaint()
    }

    private fun applySelectedColor(color: Color) {
        selectedColor = color
        previewSwatch.background = color
        updateRecentSelectionBorders()
    }

    private fun updateRecentSelectionBorders() {
        recentColorButtons.forEachIndexed { index, button ->
            val color = recentColorsSnapshot.getOrNull(index)
            val isSelected = color?.let { hasSameRgb(it, selectedColor) } == true

            button.border = when {
                isSelected -> BorderFactory.createLineBorder(Color(150, 190, 255), 2)
                color != null -> BorderFactory.createLineBorder(Color(180, 180, 180), 1)
                else -> BorderFactory.createLineBorder(Color(75, 75, 75), 1)
            }

            button.toolTipText = color?.let {
                val hex = toHexColor(it)
                if (isSelected) {
                    "Selected recent color ${index + 1}: $hex"
                } else {
                    "Recent color ${index + 1}: $hex"
                }
            } ?: "Empty recent color slot"
        }
    }

    private fun toHexColor(color: Color): String {
        return "#%02X%02X%02X".format(color.red, color.green, color.blue)
    }

    private fun hasSameRgb(left: Color, right: Color): Boolean {
        return left.red == right.red && left.green == right.green && left.blue == right.blue
    }
}
