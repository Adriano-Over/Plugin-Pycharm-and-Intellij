package com.drawing

import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.Point
import java.awt.RenderingHints
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.util.Locale
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.SwingConstants
import kotlin.math.roundToInt

internal class PhotoshopColorPickerPanel(
    initialColor: Color,
    private val onColorChanged: (Color) -> Unit
) : JPanel(BorderLayout(14, 0)) {
    private var hue = 0f
    private var saturation = 1f
    private var brightness = 1f
    private var selectedColor = Color(initialColor.red, initialColor.green, initialColor.blue)
    private var syncingFields = false
    private var onlyWebColors = false

    private val colorField = SaturationBrightnessField(
        hueProvider = { hue },
        saturationProvider = { saturation },
        brightnessProvider = { brightness },
        onPositionChanged = { newSaturation, newBrightness ->
            applyHsbState(hue, newSaturation, newBrightness, notify = true)
        }
    )
    private val hueSlider = HueSlider(
        hueProvider = { hue },
        onHueChanged = { newHue ->
            applyHsbState(newHue, saturation, brightness, notify = true)
        }
    )

    private val hField = valueField()
    private val sField = valueField()
    private val bField = valueField()
    private val rField = valueField()
    private val gField = valueField()
    private val blueField = valueField()
    private val hexField = valueField(columns = 7)
    private val webSafeCheckBox = JCheckBox("Only Web Colors")
    private val webSafeStatusLabel = JLabel(" ", SwingConstants.LEFT)
    private lateinit var snapWebSafeButton: JButton

    init {
        isOpaque = true
        background = ColorDialogTheme.panel
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(ColorDialogTheme.border, 1),
            BorderFactory.createEmptyBorder(14, 14, 14, 14)
        )

        add(createPickerArea(), BorderLayout.CENTER)
        add(createValuePanel(), BorderLayout.EAST)
        setSelectedColor(initialColor)
    }

    fun setSelectedColor(color: Color) {
        applyColorState(Color(color.red, color.green, color.blue), notify = false)
    }

    internal fun currentColor(): Color = Color(selectedColor.red, selectedColor.green, selectedColor.blue)

    internal fun valueText(key: String): String {
        return when (key.uppercase(Locale.US)) {
            "H" -> hField.text
            "S" -> sField.text
            "B" -> bField.text
            "R" -> rField.text
            "G" -> gField.text
            "BLUE" -> blueField.text
            "HEX" -> hexField.text
            else -> ""
        }
    }

    internal fun commitHexForTest(value: String) {
        hexField.text = value
        commitHexField()
    }

    internal fun setOnlyWebColorsForTest(enabled: Boolean) {
        webSafeCheckBox.isSelected = enabled
        setOnlyWebColors(enabled)
    }

    private fun createPickerArea(): JPanel {
        return JPanel(BorderLayout(10, 0)).apply {
            isOpaque = false
            add(colorField, BorderLayout.CENTER)
            add(hueSlider, BorderLayout.EAST)
        }
    }

    private fun createValuePanel(): JPanel {
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = true
            background = ColorDialogTheme.panelAlt
            border = ColorDialogTheme.createSectionBorder("Values")
            preferredSize = Dimension(190, 0)
        }

        panel.add(sectionLabel("HSB"))
        panel.add(valueRowsPanel {
            addValueRow("H", hField, "deg", ::commitHsbFields)
            addValueRow("S", sField, "%", ::commitHsbFields)
            addValueRow("B", bField, "%", ::commitHsbFields)
        })
        panel.add(Box.createVerticalStrut(10))
        panel.add(sectionLabel("RGB"))
        panel.add(valueRowsPanel {
            addValueRow("R", rField, "", ::commitRgbFields)
            addValueRow("G", gField, "", ::commitRgbFields)
            addValueRow("B", blueField, "", ::commitRgbFields)
        })
        panel.add(Box.createVerticalStrut(10))
        panel.add(sectionLabel("HEX"))
        panel.add(valueRowsPanel {
            addValueRow("#", hexField, "", ::commitHexField)
        })
        panel.add(Box.createVerticalStrut(10))
        panel.add(sectionLabel("Web"))
        panel.add(createWebSafePanel())
        panel.add(Box.createVerticalGlue())

        return panel
    }

    private fun sectionLabel(text: String): JLabel {
        return JLabel(text, SwingConstants.LEFT).apply {
            alignmentX = LEFT_ALIGNMENT
            border = BorderFactory.createEmptyBorder(4, 10, 4, 10)
            font = Font("Dialog", Font.BOLD, 11)
            foreground = ColorDialogTheme.sectionText
        }
    }

    private fun createWebSafePanel(): JPanel {
        snapWebSafeButton = ColorDialogButtonFactory.createCompactActionButton("Snap") {
            applyColorState(ColorDialogTheme.nearestWebSafeColor(selectedColor), notify = true)
        }.apply {
            toolTipText = "Switch to the nearest classic web-safe RGB color"
        }

        webSafeCheckBox.apply {
            isOpaque = false
            font = Font("Dialog", Font.PLAIN, 11)
            foreground = ColorDialogTheme.text
            toolTipText = "Limit picker movement and typed values to the classic 216 web-safe colors"
            addActionListener {
                setOnlyWebColors(isSelected)
            }
        }
        webSafeStatusLabel.apply {
            font = Font("Dialog", Font.PLAIN, 10)
            foreground = ColorDialogTheme.mutedText
            toolTipText = "Shows whether the current RGB color is web-safe"
        }

        return JPanel(GridBagLayout()).apply {
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
            border = BorderFactory.createEmptyBorder(0, 10, 0, 10)
            add(webSafeCheckBox, GridBagConstraints().apply {
                gridx = 0
                gridy = 0
                gridwidth = 2
                anchor = GridBagConstraints.WEST
                insets = Insets(0, 0, 4, 0)
            })
            add(webSafeStatusLabel, GridBagConstraints().apply {
                gridx = 0
                gridy = 1
                fill = GridBagConstraints.HORIZONTAL
                weightx = 1.0
                insets = Insets(0, 0, 0, 6)
            })
            add(snapWebSafeButton, GridBagConstraints().apply {
                gridx = 1
                gridy = 1
                anchor = GridBagConstraints.EAST
            })
        }
    }

    private fun valueRowsPanel(build: ValueRowsPanel.() -> Unit): JPanel {
        return ValueRowsPanel().apply {
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
            border = BorderFactory.createEmptyBorder(0, 10, 0, 10)
            build()
        }
    }

    private fun valueField(columns: Int = 4): JTextField {
        return JTextField(columns).apply {
            font = Font("Dialog", Font.PLAIN, 12)
            background = ColorDialogTheme.valueChip
            foreground = ColorDialogTheme.text
            caretColor = ColorDialogTheme.text
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(ColorDialogTheme.valueChipBorder, 1),
                BorderFactory.createEmptyBorder(2, 4, 2, 4)
            )
        }
    }

    private fun installCommit(field: JTextField, commit: () -> Unit) {
        field.addActionListener {
            if (!syncingFields) commit()
        }
        field.addFocusListener(object : FocusAdapter() {
            override fun focusLost(e: FocusEvent?) {
                if (!syncingFields) commit()
            }
        })
    }

    private fun ValueRowsPanel.addValueRow(
        labelText: String,
        field: JTextField,
        suffixText: String,
        commit: () -> Unit
    ) {
        val row = rowCount++
        installCommit(field, commit)

        add(JLabel(labelText, SwingConstants.RIGHT).apply {
            font = Font("Dialog", Font.PLAIN, 12)
            foreground = ColorDialogTheme.text
        }, GridBagConstraints().apply {
            gridx = 0
            gridy = row
            anchor = GridBagConstraints.EAST
            insets = Insets(2, 0, 2, 5)
        })

        add(field, GridBagConstraints().apply {
            gridx = 1
            gridy = row
            fill = GridBagConstraints.HORIZONTAL
            weightx = 1.0
            insets = Insets(2, 0, 2, 4)
        })

        add(JLabel(suffixText).apply {
            font = Font("Dialog", Font.PLAIN, 11)
            foreground = ColorDialogTheme.mutedText
        }, GridBagConstraints().apply {
            gridx = 2
            gridy = row
            anchor = GridBagConstraints.WEST
            insets = Insets(2, 0, 2, 0)
        })
    }

    private fun commitHsbFields() {
        val newHue = parseInt(hField.text, 0, 360)
        val newSaturation = parseInt(sField.text, 0, 100)
        val newBrightness = parseInt(bField.text, 0, 100)
        applyHsbState(
            newHue.coerceIn(0, 359) / 360f,
            newSaturation / 100f,
            newBrightness / 100f,
            notify = true
        )
    }

    private fun commitRgbFields() {
        applyColorState(
            Color(
                parseInt(rField.text, 0, 255),
                parseInt(gField.text, 0, 255),
                parseInt(blueField.text, 0, 255)
            ),
            notify = true
        )
    }

    private fun commitHexField() {
        val color = parseHexColor(hexField.text)
        if (color != null) {
            applyColorState(color, notify = true)
        } else {
            updateFields()
        }
    }

    private fun setOnlyWebColors(enabled: Boolean) {
        onlyWebColors = enabled
        if (enabled) {
            applyColorState(ColorDialogTheme.nearestWebSafeColor(selectedColor), notify = true)
        } else {
            updateViews(notify = false)
        }
    }

    private fun applyColorState(color: Color, notify: Boolean) {
        val displayColor = if (onlyWebColors) ColorDialogTheme.nearestWebSafeColor(color) else color
        val hsb = FloatArray(3)
        Color.RGBtoHSB(displayColor.red, displayColor.green, displayColor.blue, hsb)
        hue = hsb[0]
        saturation = hsb[1]
        brightness = hsb[2]
        selectedColor = Color(displayColor.red, displayColor.green, displayColor.blue)
        updateViews(notify)
    }

    private fun applyHsbState(newHue: Float, newSaturation: Float, newBrightness: Float, notify: Boolean) {
        hue = newHue.coerceIn(0f, 1f)
        saturation = newSaturation.coerceIn(0f, 1f)
        brightness = newBrightness.coerceIn(0f, 1f)
        val rgb = Color.getHSBColor(hue, saturation, brightness)
        if (onlyWebColors) {
            applyColorState(rgb, notify)
            return
        }
        selectedColor = Color(rgb.red, rgb.green, rgb.blue)
        updateViews(notify)
    }

    private fun updateViews(notify: Boolean) {
        updateFields()
        colorField.repaint()
        hueSlider.repaint()
        updateWebSafeStatus()
        if (notify) {
            onColorChanged(currentColor())
        }
    }

    private fun updateFields() {
        syncingFields = true
        try {
            hField.text = ((hue * 360f).roundToInt() % 360).toString()
            sField.text = (saturation * 100f).roundToInt().toString()
            bField.text = (brightness * 100f).roundToInt().toString()
            rField.text = selectedColor.red.toString()
            gField.text = selectedColor.green.toString()
            blueField.text = selectedColor.blue.toString()
            hexField.text = ColorDialogTheme.toHexColor(selectedColor).removePrefix("#")
        } finally {
            syncingFields = false
        }
    }

    private fun parseInt(text: String, minimum: Int, maximum: Int): Int {
        return text.trim()
            .toIntOrNull()
            ?.coerceIn(minimum, maximum)
            ?: minimum
    }

    private fun parseHexColor(text: String): Color? {
        val trimmed = text.trim().removePrefix("#")
        val normalized = when (trimmed.length) {
            3 -> trimmed.flatMap { listOf(it, it) }.joinToString("")
            6 -> trimmed
            else -> return null
        }
        val rgb = normalized.toIntOrNull(radix = 16) ?: return null
        return Color(rgb)
    }

    private fun updateWebSafeStatus() {
        if (!::snapWebSafeButton.isInitialized) return
        val isWebSafe = ColorDialogTheme.isWebSafeColor(selectedColor)
        webSafeStatusLabel.text = if (isWebSafe) "Web-safe" else "Not web-safe"
        webSafeStatusLabel.foreground = if (isWebSafe) ColorDialogTheme.success else Color(245, 190, 115)
        snapWebSafeButton.isEnabled = !isWebSafe
        webSafeCheckBox.isSelected = onlyWebColors
    }

    private class ValueRowsPanel : JPanel(GridBagLayout()) {
        var rowCount = 0
    }

    private class SaturationBrightnessField(
        private val hueProvider: () -> Float,
        private val saturationProvider: () -> Float,
        private val brightnessProvider: () -> Float,
        private val onPositionChanged: (Float, Float) -> Unit
    ) : JPanel() {
        init {
            preferredSize = Dimension(320, 320)
            minimumSize = Dimension(260, 260)
            border = BorderFactory.createLineBorder(ColorDialogTheme.border, 1)

            val handler = object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    updateFromPoint(e.point)
                }

                override fun mouseDragged(e: MouseEvent) {
                    updateFromPoint(e.point)
                }
            }
            addMouseListener(handler)
            addMouseMotionListener(handler)
        }

        private fun updateFromPoint(point: Point) {
            val newSaturation = (point.x.toFloat() / (width - 1).coerceAtLeast(1)).coerceIn(0f, 1f)
            val newBrightness = (1f - point.y.toFloat() / (height - 1).coerceAtLeast(1)).coerceIn(0f, 1f)
            onPositionChanged(newSaturation, newBrightness)
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            if (width <= 0 || height <= 0) return

            val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
            val hue = hueProvider()
            val widthDenominator = (width - 1).coerceAtLeast(1).toFloat()
            val heightDenominator = (height - 1).coerceAtLeast(1).toFloat()
            for (y in 0 until height) {
                val brightness = (1f - y.toFloat() / heightDenominator).coerceIn(0f, 1f)
                for (x in 0 until width) {
                    val saturation = (x.toFloat() / widthDenominator).coerceIn(0f, 1f)
                    image.setRGB(x, y, Color.HSBtoRGB(hue, saturation, brightness))
                }
            }

            val g2 = g as Graphics2D
            g2.drawImage(image, 0, 0, null)
            drawSelection(g2)
        }

        private fun drawSelection(g2: Graphics2D) {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val x = (saturationProvider() * (width - 1).coerceAtLeast(1)).roundToInt()
            val y = ((1f - brightnessProvider()) * (height - 1).coerceAtLeast(1)).roundToInt()
            g2.stroke = BasicStroke(2f)
            g2.color = Color.WHITE
            g2.drawOval(x - 6, y - 6, 12, 12)
            g2.color = Color.BLACK
            g2.stroke = BasicStroke(1f)
            g2.drawOval(x - 8, y - 8, 16, 16)
        }
    }

    private class HueSlider(
        private val hueProvider: () -> Float,
        private val onHueChanged: (Float) -> Unit
    ) : JPanel() {
        init {
            preferredSize = Dimension(28, 320)
            minimumSize = Dimension(24, 260)
            border = BorderFactory.createLineBorder(ColorDialogTheme.border, 1)

            val handler = object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    updateFromY(e.y)
                }

                override fun mouseDragged(e: MouseEvent) {
                    updateFromY(e.y)
                }
            }
            addMouseListener(handler)
            addMouseMotionListener(handler)
        }

        private fun updateFromY(y: Int) {
            onHueChanged((y.toFloat() / (height - 1).coerceAtLeast(1)).coerceIn(0f, 1f))
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            if (width <= 0 || height <= 0) return

            val g2 = g as Graphics2D
            val denominator = (height - 1).coerceAtLeast(1).toFloat()
            for (y in 0 until height) {
                g2.color = Color.getHSBColor((y.toFloat() / denominator).coerceIn(0f, 1f), 1f, 1f)
                g2.drawLine(0, y, width, y)
            }

            val markerY = (hueProvider() * denominator).roundToInt()
            g2.color = Color.WHITE
            g2.stroke = BasicStroke(2f)
            g2.drawLine(0, markerY, width, markerY)
            g2.color = Color.BLACK
            g2.stroke = BasicStroke(1f)
            g2.drawLine(0, markerY - 2, width, markerY - 2)
            g2.drawLine(0, markerY + 2, width, markerY + 2)
        }
    }
}
