package com.drawing

import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Window
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities

class ColorPickerDialog(
    owner: Window,
    initialColor: Color,
    recentColors: List<Color>,
    private val onChosen: (Color) -> Unit
) : JDialog(owner, "Choose Drawing Color", ModalityType.MODELESS) {
    private var selectedColor: Color = Color(initialColor.red, initialColor.green, initialColor.blue)
    private var syncingColorControls = false
    private lateinit var previewPanel: ColorPreviewPanel
    private lateinit var recentColorsPanel: RecentColorsPanel
    private lateinit var photoshopPickerPanel: PhotoshopColorPickerPanel

    init {
        layout = BorderLayout(12, 12)
        contentPane.background = ColorDialogTheme.background
        rootPane.border = BorderFactory.createEmptyBorder(12, 12, 12, 12)

        previewPanel = ColorPreviewPanel(
            initialColor = selectedColor,
            onCopyHex = ::copySelectedHex,
            onCopyRgb = ::copySelectedRgb
        )
        recentColorsPanel = RecentColorsPanel(recentColors) { color ->
            applySelectedColor(color, syncPicker = true)
        }
        photoshopPickerPanel = PhotoshopColorPickerPanel(selectedColor) { color ->
            if (!syncingColorControls) {
                applySelectedColor(color, syncPicker = false)
            }
        }

        add(createHeaderPanel(), BorderLayout.NORTH)
        add(photoshopPickerPanel, BorderLayout.CENTER)
        add(createSidePanel(), BorderLayout.EAST)
        add(createBottomPanel(), BorderLayout.SOUTH)

        refreshSelectedColorViews()
        configureWindow(owner)
    }

    private fun createHeaderPanel(): JPanel {
        return JPanel(BorderLayout()).apply {
            isOpaque = true
            background = ColorDialogTheme.header
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(ColorDialogTheme.headerBorder, 1),
                BorderFactory.createEmptyBorder(9, 11, 9, 11)
            )

            add(JLabel("Choose Color").apply {
                font = Font("Dialog", Font.BOLD, 13)
                foreground = ColorDialogTheme.text
            }, BorderLayout.NORTH)

            add(JLabel("Use the precision picker for Draw, Fill, Shapes, Text, and Balloon colors.").apply {
                font = Font("Dialog", Font.PLAIN, 11)
                foreground = ColorDialogTheme.mutedText
            }, BorderLayout.SOUTH)
        }
    }

    private fun createSidePanel(): JPanel {
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = true
            background = ColorDialogTheme.panel
            preferredSize = Dimension(ColorDialogTheme.SIDE_PANEL_WIDTH, 0)
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(ColorDialogTheme.border, 1),
                BorderFactory.createEmptyBorder(10, 10, 10, 10)
            )
            add(previewPanel)
            add(Box.createVerticalStrut(12))
            add(recentColorsPanel)
            add(Box.createVerticalStrut(12))
            add(createEyeDropperButton())
            add(Box.createVerticalGlue())
        }
    }

    private fun createEyeDropperButton() = ColorDialogButtonFactory.createActionButton(
        text = "Eyedrop",
        backgroundColor = ColorDialogTheme.purple,
        hoverBackgroundColor = ColorDialogTheme.purpleHover,
        borderColor = Color(136, 120, 205),
        hoverBorderColor = Color(184, 164, 255)
    ) {
        showEyeDropper()
    }.apply {
        alignmentX = CENTER_ALIGNMENT
        toolTipText = "Pick a color from anywhere on the screen"
    }

    private fun createBottomPanel(): JPanel {
        val bottomPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0)).apply {
            isOpaque = false
        }
        val okButton = ColorDialogButtonFactory.createActionButton(
            text = "OK",
            backgroundColor = ColorDialogTheme.ok,
            hoverBackgroundColor = ColorDialogTheme.okHover,
            borderColor = ColorDialogTheme.accent,
            hoverBorderColor = Color(170, 200, 255)
        ) {
            onChosen(selectedColor)
            dispose()
        }.apply {
            toolTipText = "Apply the selected color"
        }
        val cancelButton = ColorDialogButtonFactory.createActionButton("Cancel") {
            dispose()
        }.apply {
            toolTipText = "Close without changing the color"
        }
        bottomPanel.add(okButton)
        bottomPanel.add(cancelButton)
        rootPane.defaultButton = okButton
        return bottomPanel
    }

    private fun showEyeDropper() {
        val picker = this@ColorPickerDialog
        val overlay = EyeDropperOverlay(
            onPicked = { sampled ->
                applySelectedColor(sampled, syncPicker = true)
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

    private fun configureWindow(owner: Window) {
        minimumSize = Dimension(760, 500)
        size = Dimension(840, 560)
        setLocationRelativeTo(owner)
        defaultCloseOperation = DISPOSE_ON_CLOSE
        addWindowListener(object : WindowAdapter() {
            override fun windowClosed(e: WindowEvent?) {
                owner.toFront()
            }
        })
    }

    private fun copySelectedHex() {
        ColorClipboardActions.copyToSystemClipboard(ColorClipboardActions.hexText(selectedColor))
        previewPanel.showCopyFeedback("HEX copied")
    }

    private fun copySelectedRgb() {
        ColorClipboardActions.copyToSystemClipboard(ColorClipboardActions.rgbText(selectedColor))
        previewPanel.showCopyFeedback("RGB copied")
    }

    private fun applySelectedColor(
        color: Color,
        syncPicker: Boolean = false
    ) {
        selectedColor = Color(color.red, color.green, color.blue)
        previewPanel.clearCopyStatus()
        refreshSelectedColorViews()

        if (syncingColorControls) return
        syncingColorControls = true
        try {
            if (syncPicker) {
                photoshopPickerPanel.setSelectedColor(selectedColor)
            }
        } finally {
            syncingColorControls = false
        }
    }

    private fun refreshSelectedColorViews() {
        previewPanel.updateColor(selectedColor)
        recentColorsPanel.updateSelectedColor(selectedColor)
    }
}
