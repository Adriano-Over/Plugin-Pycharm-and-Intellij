package com.floatbar

import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridLayout
import java.awt.Insets
import java.awt.Window
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JColorChooser
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTabbedPane
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.event.ChangeListener

private const val RECENT_COLOR_SLOT_COUNT = 6
private const val SIDE_PANEL_WIDTH = 156
private const val PREVIEW_SWATCH_SIZE = 58
private const val RECENT_SWATCH_SIZE = 32

private val COLOR_DIALOG_BACKGROUND = Color(25, 28, 36)
private val COLOR_DIALOG_PANEL = Color(34, 39, 50)
private val COLOR_DIALOG_PANEL_ALT = Color(39, 45, 58)
private val COLOR_DIALOG_HEADER = Color(30, 38, 58)
private val COLOR_DIALOG_HEADER_BORDER = Color(78, 98, 145)
private val COLOR_DIALOG_TEXT = Color(238, 242, 250)
private val COLOR_DIALOG_MUTED_TEXT = Color(168, 179, 200)
private val COLOR_DIALOG_SECTION_TEXT = Color(178, 190, 215)
private val COLOR_DIALOG_BORDER = Color(82, 95, 120)
private val COLOR_DIALOG_ACCENT = Color(92, 138, 255)
private val COLOR_DIALOG_ACCENT_HOVER = Color(112, 158, 255)
private val COLOR_DIALOG_BUTTON = Color(47, 54, 68)
private val COLOR_DIALOG_BUTTON_HOVER = Color(55, 64, 84)
private val COLOR_DIALOG_OK = Color(67, 105, 205)
private val COLOR_DIALOG_OK_HOVER = Color(80, 125, 235)
private val COLOR_DIALOG_PURPLE = Color(86, 76, 135)
private val COLOR_DIALOG_PURPLE_HOVER = Color(102, 90, 160)
private val COLOR_DIALOG_EMPTY_SWATCH = Color(44, 49, 60)
private val COLOR_DIALOG_VALUE_CHIP = Color(25, 31, 44)
private val COLOR_DIALOG_VALUE_CHIP_BORDER = Color(86, 105, 145)
private val COLOR_DIALOG_SUCCESS = Color(132, 220, 155)

class ColorPickerDialog(
    owner: Window,
    initialColor: Color,
    recentColors: List<Color>,
    private val onChosen: (Color) -> Unit
) : JDialog(owner, "Choose Drawing Color", ModalityType.MODELESS) {

    private var selectedColor: Color = initialColor
    private val previewSwatch = JPanel()
    private val hexValueLabel = JLabel("", SwingConstants.CENTER)
    private val rgbValueLabel = JLabel("", SwingConstants.CENTER)
    private val copyStatusLabel = JLabel(" ", SwingConstants.CENTER)
    private var copyStatusTimer: Timer? = null
    private val recentPanel = JPanel(GridLayout(2, 3, 6, 6))
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
        contentPane.background = COLOR_DIALOG_BACKGROUND
        rootPane.border = BorderFactory.createEmptyBorder(12, 12, 12, 12)

        previewSwatch.apply {
            preferredSize = Dimension(PREVIEW_SWATCH_SIZE, PREVIEW_SWATCH_SIZE)
            minimumSize = Dimension(PREVIEW_SWATCH_SIZE, PREVIEW_SWATCH_SIZE)
            maximumSize = Dimension(PREVIEW_SWATCH_SIZE, PREVIEW_SWATCH_SIZE)
            background = initialColor
            border = BorderFactory.createLineBorder(COLOR_DIALOG_ACCENT, 2)
            toolTipText = "Selected drawing color preview"
        }

        val centerTabs = JTabbedPane().apply {
            background = COLOR_DIALOG_PANEL
            foreground = COLOR_DIALOG_TEXT
            border = BorderFactory.createLineBorder(COLOR_DIALOG_BORDER, 1)
            addTab("Palette", palettePanel)
            addTab("Advanced", advancedChooser)
            toolTipText = "Choose a color using the simple palette or the advanced color chooser"
        }

        val rightPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = true
            background = COLOR_DIALOG_PANEL
            preferredSize = Dimension(SIDE_PANEL_WIDTH, 0)
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(COLOR_DIALOG_BORDER, 1),
                BorderFactory.createEmptyBorder(10, 10, 10, 10)
            )
        }

        val previewContainer = createPreviewContainer()
        val recentContainer = createRecentContainer()
        val eyeDropperButton = createActionButton(
            text = "Eyedrop",
            backgroundColor = COLOR_DIALOG_PURPLE,
            hoverBackgroundColor = COLOR_DIALOG_PURPLE_HOVER,
            borderColor = Color(136, 120, 205),
            hoverBorderColor = Color(184, 164, 255)
        ) {
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
        }.apply {
            alignmentX = CENTER_ALIGNMENT
            toolTipText = "Pick a color from anywhere on the screen"
        }

        refreshRecentButtons()
        updateSelectedColorLabels()

        rightPanel.add(previewContainer)
        rightPanel.add(Box.createVerticalStrut(12))
        rightPanel.add(recentContainer)
        rightPanel.add(Box.createVerticalStrut(12))
        rightPanel.add(eyeDropperButton)
        rightPanel.add(Box.createVerticalGlue())

        val bottomPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0)).apply {
            isOpaque = false
        }
        val okButton = createActionButton(
            text = "OK",
            backgroundColor = COLOR_DIALOG_OK,
            hoverBackgroundColor = COLOR_DIALOG_OK_HOVER,
            borderColor = COLOR_DIALOG_ACCENT,
            hoverBorderColor = Color(170, 200, 255)
        ) {
            onChosen(selectedColor)
            dispose()
        }.apply {
            toolTipText = "Apply the selected color"
        }
        val cancelButton = createActionButton("Cancel") {
            dispose()
        }.apply {
            toolTipText = "Close without changing the color"
        }
        bottomPanel.add(okButton)
        bottomPanel.add(cancelButton)
        rootPane.defaultButton = okButton

        add(createHeaderPanel(), BorderLayout.NORTH)
        add(centerTabs, BorderLayout.CENTER)
        add(rightPanel, BorderLayout.EAST)
        add(bottomPanel, BorderLayout.SOUTH)

        minimumSize = Dimension(800, 540)
        size = Dimension(920, 640)
        setLocationRelativeTo(owner)
        defaultCloseOperation = DISPOSE_ON_CLOSE
        addWindowListener(object : WindowAdapter() {
            override fun windowClosed(e: WindowEvent?) {
                owner.toFront()
            }
        })
    }

    private fun createHeaderPanel(): JPanel {
        return JPanel(BorderLayout()).apply {
            isOpaque = true
            background = COLOR_DIALOG_HEADER
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(COLOR_DIALOG_HEADER_BORDER, 1),
                BorderFactory.createEmptyBorder(9, 11, 9, 11)
            )

            add(JLabel("Choose Color").apply {
                font = Font("Dialog", Font.BOLD, 13)
                foreground = COLOR_DIALOG_TEXT
            }, BorderLayout.NORTH)

            add(JLabel("Select the color used by Draw, Fill, and Shapes.").apply {
                font = Font("Dialog", Font.PLAIN, 11)
                foreground = COLOR_DIALOG_MUTED_TEXT
            }, BorderLayout.SOUTH)
        }
    }

    private fun createPreviewContainer(): JPanel {
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = true
            background = COLOR_DIALOG_PANEL_ALT
            border = createSectionBorder("Selected")

            addCenteredLabel("PREVIEW", Font.BOLD, 9, COLOR_DIALOG_SECTION_TEXT)
            add(Box.createVerticalStrut(6))
            previewSwatch.alignmentX = CENTER_ALIGNMENT
            add(previewSwatch)
            add(Box.createVerticalStrut(8))

            hexValueLabel.apply {
                alignmentX = CENTER_ALIGNMENT
                preferredSize = Dimension(116, 22)
                maximumSize = Dimension(116, 22)
                font = Font("Dialog", Font.BOLD, 12)
                isOpaque = true
                background = COLOR_DIALOG_VALUE_CHIP
                foreground = COLOR_DIALOG_TEXT
                border = BorderFactory.createLineBorder(COLOR_DIALOG_VALUE_CHIP_BORDER, 1)
            }
            rgbValueLabel.apply {
                alignmentX = CENTER_ALIGNMENT
                preferredSize = Dimension(116, 20)
                maximumSize = Dimension(116, 20)
                font = Font("Dialog", Font.PLAIN, 10)
                isOpaque = true
                background = COLOR_DIALOG_VALUE_CHIP
                foreground = COLOR_DIALOG_MUTED_TEXT
                border = BorderFactory.createLineBorder(Color(70, 83, 108), 1)
            }
            add(hexValueLabel)
            add(Box.createVerticalStrut(4))
            add(rgbValueLabel)
            add(Box.createVerticalStrut(8))
            add(createCopyButtonsRow())
            add(Box.createVerticalStrut(5))
            copyStatusLabel.apply {
                alignmentX = CENTER_ALIGNMENT
                preferredSize = Dimension(116, 16)
                maximumSize = Dimension(116, 16)
                font = Font("Dialog", Font.PLAIN, 10)
                foreground = COLOR_DIALOG_SUCCESS
                toolTipText = "Copy status"
            }
            add(copyStatusLabel)
        }
    }

    private fun createCopyButtonsRow(): JPanel {
        return JPanel(GridLayout(1, 2, 6, 0)).apply {
            isOpaque = false
            alignmentX = CENTER_ALIGNMENT
            preferredSize = Dimension(116, 24)
            maximumSize = Dimension(116, 24)
            add(createCompactActionButton("Copy HEX") {
                copyToClipboard(toHexColor(selectedColor), "HEX copied")
            }.apply {
                toolTipText = "Copy the selected color as #RRGGBB"
            })
            add(createCompactActionButton("Copy RGB") {
                copyToClipboard("rgb(${selectedColor.red}, ${selectedColor.green}, ${selectedColor.blue})", "RGB copied")
            }.apply {
                toolTipText = "Copy the selected color as rgb(r, g, b)"
            })
        }
    }

    private fun createRecentContainer(): JPanel {
        return JPanel(BorderLayout()).apply {
            isOpaque = true
            background = COLOR_DIALOG_PANEL_ALT
            border = createSectionBorder("Recent")
            recentPanel.isOpaque = false
            recentPanel.border = BorderFactory.createEmptyBorder(2, 2, 2, 2)
            add(recentPanel, BorderLayout.CENTER)
        }
    }

    private fun JPanel.addCenteredLabel(text: String, fontStyle: Int, fontSize: Int, color: Color) {
        add(JLabel(text, SwingConstants.CENTER).apply {
            alignmentX = CENTER_ALIGNMENT
            font = Font("Dialog", fontStyle, fontSize)
            foreground = color
        })
    }

    private fun createSectionBorder(title: String) = BorderFactory.createTitledBorder(
        BorderFactory.createLineBorder(COLOR_DIALOG_BORDER, 1),
        title
    ).apply {
        titleColor = COLOR_DIALOG_SECTION_TEXT
        titleFont = Font("Dialog", Font.BOLD, 11)
    }

    private fun createActionButton(
        text: String,
        backgroundColor: Color = COLOR_DIALOG_BUTTON,
        hoverBackgroundColor: Color = COLOR_DIALOG_BUTTON_HOVER,
        borderColor: Color = COLOR_DIALOG_BORDER,
        hoverBorderColor: Color = COLOR_DIALOG_ACCENT_HOVER,
        onClick: () -> Unit
    ): JButton {
        return JButton(text).apply {
            preferredSize = Dimension(108, 28)
            maximumSize = Dimension(108, 28)
            font = Font("Dialog", Font.PLAIN, 12)
            isFocusPainted = false
            isOpaque = true
            background = backgroundColor
            foreground = COLOR_DIALOG_TEXT
            border = BorderFactory.createLineBorder(borderColor, 1)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            margin = Insets(2, 10, 2, 10)
            addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) {
                    if (!isEnabled) return
                    background = hoverBackgroundColor
                    border = BorderFactory.createLineBorder(hoverBorderColor, 1)
                }

                override fun mouseExited(e: MouseEvent) {
                    background = backgroundColor
                    border = BorderFactory.createLineBorder(borderColor, 1)
                }
            })
            addActionListener { onClick() }
        }
    }

    private fun createCompactActionButton(text: String, onClick: () -> Unit): JButton {
        return JButton(text).apply {
            preferredSize = Dimension(55, 24)
            maximumSize = Dimension(55, 24)
            font = Font("Dialog", Font.PLAIN, 9)
            isFocusPainted = false
            isOpaque = true
            background = COLOR_DIALOG_BUTTON
            foreground = COLOR_DIALOG_TEXT
            border = BorderFactory.createLineBorder(COLOR_DIALOG_BORDER, 1)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            margin = Insets(1, 3, 1, 3)
            addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) {
                    if (!isEnabled) return
                    background = COLOR_DIALOG_BUTTON_HOVER
                    border = BorderFactory.createLineBorder(COLOR_DIALOG_ACCENT_HOVER, 1)
                }

                override fun mouseExited(e: MouseEvent) {
                    background = COLOR_DIALOG_BUTTON
                    border = BorderFactory.createLineBorder(COLOR_DIALOG_BORDER, 1)
                }
            })
            addActionListener { onClick() }
        }
    }

    private fun copyToClipboard(value: String, feedbackText: String) {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(value), null)
        showCopyFeedback(feedbackText)
    }

    private fun showCopyFeedback(text: String) {
        copyStatusTimer?.stop()
        copyStatusLabel.text = text
        copyStatusLabel.foreground = COLOR_DIALOG_SUCCESS
        copyStatusTimer = Timer(1200) {
            copyStatusLabel.text = " "
        }.apply {
            isRepeats = false
            start()
        }
    }

    private fun refreshRecentButtons() {
        recentPanel.removeAll()
        recentColorButtons.clear()
        repeat(RECENT_COLOR_SLOT_COUNT) { index ->
            val color = recentColorsSnapshot.getOrNull(index)
            val swatch = JButton().apply {
                preferredSize = Dimension(RECENT_SWATCH_SIZE, RECENT_SWATCH_SIZE)
                minimumSize = Dimension(RECENT_SWATCH_SIZE, RECENT_SWATCH_SIZE)
                maximumSize = Dimension(RECENT_SWATCH_SIZE, RECENT_SWATCH_SIZE)
                isFocusPainted = false
                isOpaque = true
                margin = Insets(0, 0, 0, 0)
                background = color ?: COLOR_DIALOG_EMPTY_SWATCH
                isEnabled = color != null
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
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
        copyStatusLabel.text = " "
        previewSwatch.background = color
        updateSelectedColorLabels()
        updateRecentSelectionBorders()
    }

    private fun updateSelectedColorLabels() {
        val hex = toHexColor(selectedColor)
        hexValueLabel.text = hex
        rgbValueLabel.text = "rgb(${selectedColor.red}, ${selectedColor.green}, ${selectedColor.blue})"
        previewSwatch.toolTipText = "Selected color: $hex / ${rgbValueLabel.text}"
        previewSwatch.border = BorderFactory.createLineBorder(readableBorderFor(selectedColor), 2)
    }

    private fun updateRecentSelectionBorders() {
        recentColorButtons.forEachIndexed { index, button ->
            val color = recentColorsSnapshot.getOrNull(index)
            val isSelected = color?.let { hasSameRgb(it, selectedColor) } == true
            button.border = when {
                isSelected -> BorderFactory.createLineBorder(COLOR_DIALOG_ACCENT_HOVER, 2)
                color != null -> BorderFactory.createLineBorder(Color(118, 132, 160), 1)
                else -> BorderFactory.createLineBorder(Color(66, 74, 90), 1)
            }
            button.toolTipText = color?.let {
                val hex = toHexColor(it)
                val rgb = "rgb(${it.red}, ${it.green}, ${it.blue})"
                if (isSelected) {
                    "Selected recent color ${index + 1}: $hex / $rgb"
                } else {
                    "Recent color ${index + 1}: $hex / $rgb"
                }
            } ?: "Empty recent color slot"
        }
    }

    private fun readableBorderFor(color: Color): Color {
        return if ((color.red * 299 + color.green * 587 + color.blue * 114) / 1000 < 140) {
            Color(232, 238, 255)
        } else {
            Color(35, 42, 58)
        }
    }

    private fun toHexColor(color: Color): String {
        return "#%02X%02X%02X".format(color.red, color.green, color.blue)
    }

    private fun hasSameRgb(left: Color, right: Color): Boolean {
        return left.red == right.red && left.green == right.green && left.blue == right.blue
    }
}
