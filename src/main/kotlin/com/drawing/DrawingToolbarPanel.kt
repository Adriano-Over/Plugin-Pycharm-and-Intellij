package com.drawing

import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.GridLayout
import java.awt.Insets
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants

internal data class DrawingToolbarControls(
    val toolStatusLabel: JLabel,
    val colorStatusLabel: JLabel,
    val overlayButton: JButton,
    val passThroughButton: JButton,
    val undoButton: JButton,
    val redoButton: JButton,
    val shapeButton: JButton,
    val textButton: JButton,
    val balloonButton: JButton,
    val selectButton: JButton,
    val drawingButton: JButton,
    val erasingButton: JButton,
    val fillButton: JButton,
    val colorButton: JButton,
    val gridButton: JButton,
    val clearButton: JButton,
    val recentColorButtons: List<JButton>
)

internal data class DrawingToolbarView(
    val rootPanel: JPanel,
    val headerPanel: JPanel,
    val controls: DrawingToolbarControls
)

internal class DrawingToolbarPanel(
    restoreToolbarStyles: () -> Unit,
    private val headerTooltip: String = "Drag to move Drawing",
    private val onHide: () -> Unit,
    private val onOverlay: () -> Unit,
    private val onPassThrough: () -> Unit,
    private val onSelect: () -> Unit,
    private val onDraw: () -> Unit,
    private val onErase: () -> Unit,
    private val onColor: () -> Unit,
    private val onFill: () -> Unit,
    private val onShapes: () -> Unit,
    private val onText: () -> Unit,
    private val onBalloon: () -> Unit,
    private val onGrid: () -> Unit,
    private val onUndo: () -> Unit,
    private val onRedo: () -> Unit,
    private val onClear: () -> Unit,
    private val onRecentColor: (Int) -> Unit
) {
    private val buttonFactory = DrawingButtonFactory(restoreToolbarStyles)

    fun create(): DrawingToolbarView {
        val rootPanel = createRootPanel()
        val buttonColumn = createButtonColumn()
        val historyRow = createHistoryRow()
        val recentGrid = createRecentGrid()
        val headerPanel = createHeaderPanel()
        val controls = createControls()

        addToolbarSections(buttonColumn, historyRow, controls)
        addRecentColorButtons(recentGrid, controls.recentColorButtons)
        rootPanel.add(headerPanel)
        rootPanel.add(buttonColumn)
        rootPanel.add(createRecentSectionHeader())
        rootPanel.add(recentGrid)

        return DrawingToolbarView(
            rootPanel = rootPanel,
            headerPanel = headerPanel,
            controls = controls
        )
    }

    private fun createRootPanel(): JPanel {
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = Color(30, 30, 30, 215)
            border = BorderFactory.createLineBorder(Color(80, 80, 80), 1)
            isOpaque = true
        }
    }

    private fun createButtonColumn(): JPanel {
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = BorderFactory.createEmptyBorder(6, 8, 6, 8)
        }
    }

    private fun createHistoryRow(): JPanel {
        return JPanel(GridLayout(1, 2, 6, 0)).apply {
            isOpaque = false
            alignmentX = Component.CENTER_ALIGNMENT
            maximumSize = Dimension(DrawingMetrics.BAR_WIDTH, DrawingMetrics.BUTTON_HEIGHT)
            preferredSize = Dimension(DrawingMetrics.BAR_WIDTH, DrawingMetrics.BUTTON_HEIGHT)
        }
    }

    private fun createRecentGrid(): JPanel {
        return JPanel(GridLayout(0, 3, 6, 6)).apply {
            isOpaque = false
            border = BorderFactory.createEmptyBorder(0, 8, 8, 8)
        }
    }

    private fun createControls(): DrawingToolbarControls {
        val recentColorButtons = createRecentColorButtons()
        return DrawingToolbarControls(
            toolStatusLabel = createToolStatusLabel(),
            colorStatusLabel = createColorStatusLabel(),
            overlayButton = buttonFactory.createButton("Overlay OFF", onOverlay).apply {
                toolTipText = "Show the editor drawing overlay"
            },
            passThroughButton = buttonFactory.createButton("Code OFF", onPassThrough).apply {
                toolTipText = "Let the editor receive mouse input while drawings remain visible"
            },
            selectButton = buttonFactory.createButton("Select", onSelect).apply {
                toolTipText = "Select an existing drawing, then drag to move it"
            },
            drawingButton = buttonFactory.createButton("Draw", onDraw).apply {
                toolTipText = "Draw freehand strokes on the editor overlay"
            },
            erasingButton = buttonFactory.createButton("Erase", onErase).apply {
                toolTipText = "Erase strokes using the current eraser radius"
            },
            colorButton = buttonFactory.createButton("Color", onColor).apply {
                toolTipText = "Choose the drawing and fill color"
            },
            fillButton = buttonFactory.createButton("Fill", onFill).apply {
                toolTipText = "Fill a bounded area with the selected color"
            },
            shapeButton = buttonFactory.createButton("Shapes", onShapes).apply {
                toolTipText = "Choose and draw a shape"
            },
            textButton = buttonFactory.createButton("Text", onText).apply {
                toolTipText = "Choose Filled or Hollow text, then draw a text area on the overlay"
            },
            balloonButton = buttonFactory.createButton("Balloon", onBalloon).apply {
                toolTipText = "Choose Filled or Hollow text, then draw a speech balloon"
            },
            gridButton = buttonFactory.createButton("Grid ON", onGrid).apply {
                toolTipText = "Toggle the drawing alignment grid"
            },
            undoButton = buttonFactory.createHalfButton("Undo", onUndo).apply {
                toolTipText = "Undo the last drawing action"
            },
            redoButton = buttonFactory.createHalfButton("Redo", onRedo).apply {
                toolTipText = "Redo the last undone drawing action"
            },
            clearButton = buttonFactory.createButton("Clear", onClear).apply {
                toolTipText = "Clear all drawings from the current editor document. You will be asked to confirm first"
            },
            recentColorButtons = recentColorButtons
        )
    }

    private fun createToolStatusLabel(): JLabel {
        return JLabel("Tool: Draw", SwingConstants.CENTER).apply {
            preferredSize = Dimension(DrawingMetrics.BAR_WIDTH, DrawingMetrics.STATUS_HEIGHT)
            maximumSize = Dimension(DrawingMetrics.BAR_WIDTH, DrawingMetrics.STATUS_HEIGHT)
            alignmentX = Component.CENTER_ALIGNMENT
            font = Font("Dialog", Font.BOLD, 11)
            isOpaque = true
            background = Color(42, 42, 42)
            foreground = Color(235, 235, 235)
            border = BorderFactory.createLineBorder(Color(95, 95, 95), 1)
            toolTipText = "Current Drawing tool"
        }
    }

    private fun createColorStatusLabel(): JLabel {
        return JLabel("", SwingConstants.CENTER).apply {
            preferredSize = Dimension(DrawingMetrics.BAR_WIDTH, DrawingMetrics.COLOR_STATUS_HEIGHT)
            minimumSize = Dimension(DrawingMetrics.BAR_WIDTH, DrawingMetrics.COLOR_STATUS_HEIGHT)
            maximumSize = Dimension(DrawingMetrics.BAR_WIDTH, DrawingMetrics.COLOR_STATUS_HEIGHT)
            alignmentX = Component.CENTER_ALIGNMENT
            isOpaque = true
            background = Color.BLACK
            border = BorderFactory.createEmptyBorder()
            toolTipText = "Selected Drawing color"
        }
    }

    private fun createRecentColorButtons(): List<JButton> {
        return List(6) { index ->
            SolidColorButton().apply {
                preferredSize = Dimension(28, 28)
                minimumSize = Dimension(28, 28)
                maximumSize = Dimension(28, 28)
                isFocusPainted = false
                margin = Insets(0, 0, 0, 0)
                border = BorderFactory.createLineBorder(Color(110, 110, 110), 1)
                toolTipText = "Recent color ${index + 1}"
                buttonFactory.installHoverFeedback(this)
                addActionListener { onRecentColor(index) }
            }
        }
    }

    private fun addToolbarSections(
        buttonColumn: JPanel,
        historyRow: JPanel,
        controls: DrawingToolbarControls
    ) {
        buttonColumn.add(controls.colorStatusLabel)
        buttonColumn.add(Box.createVerticalStrut(4))
        buttonColumn.add(controls.toolStatusLabel)
        buttonColumn.add(createSectionSeparator())
        buttonColumn.add(createSectionLabel("Tools"))
        listOf(
            controls.drawingButton,
            controls.erasingButton,
            controls.fillButton,
            controls.shapeButton,
            controls.textButton,
            controls.balloonButton,
            controls.selectButton
        ).forEach { button ->
            addStackedButton(buttonColumn, button)
        }

        buttonColumn.add(createSectionSeparator())
        buttonColumn.add(createSectionLabel("Color"))
        addStackedButton(buttonColumn, controls.colorButton)

        buttonColumn.add(createSectionSeparator())
        buttonColumn.add(createSectionLabel("View"))
        listOf(controls.overlayButton, controls.gridButton, controls.passThroughButton).forEach { button ->
            addStackedButton(buttonColumn, button)
        }

        buttonColumn.add(createSectionSeparator())
        buttonColumn.add(createSectionLabel("History"))
        historyRow.add(controls.undoButton)
        historyRow.add(controls.redoButton)
        buttonColumn.add(historyRow)
        buttonColumn.add(Box.createVerticalStrut(6))

        controls.clearButton.alignmentX = Component.CENTER_ALIGNMENT
        buttonColumn.add(controls.clearButton)
        buttonColumn.add(Box.createVerticalStrut(6))
    }

    private fun addRecentColorButtons(recentGrid: JPanel, recentColorButtons: List<JButton>) {
        recentColorButtons.forEach(recentGrid::add)
    }

    private fun createHeaderPanel(): JPanel {
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            background = Color(38, 38, 38)
            border = BorderFactory.createEmptyBorder(5, 8, 5, 8)
            maximumSize = Dimension(Int.MAX_VALUE, 30)
            isOpaque = true
            toolTipText = headerTooltip

            add(JLabel("Drawing").apply {
                font = Font("Dialog", Font.BOLD, 12)
                foreground = Color(235, 235, 235)
                toolTipText = headerTooltip
            })
            add(Box.createHorizontalGlue())
            add(JLabel("::").apply {
                font = Font("Dialog", Font.BOLD, 12)
                foreground = Color(150, 150, 150)
                toolTipText = headerTooltip
            })
            add(Box.createHorizontalStrut(6))
            add(buttonFactory.createHeaderHideButton(onHide))
        }
    }

    private fun createSectionLabel(text: String): JLabel {
        return JLabel(text.uppercase(), SwingConstants.LEFT).apply {
            preferredSize = Dimension(DrawingMetrics.BAR_WIDTH, 16)
            maximumSize = Dimension(DrawingMetrics.BAR_WIDTH, 16)
            alignmentX = Component.CENTER_ALIGNMENT
            font = Font("Dialog", Font.BOLD, 9)
            foreground = Color(165, 165, 165)
            toolTipText = "$text controls"
        }
    }

    private fun createRecentSectionHeader(): JLabel {
        return JLabel("RECENT COLORS", SwingConstants.LEFT).apply {
            preferredSize = Dimension(DrawingMetrics.BAR_WIDTH + 16, 16)
            maximumSize = Dimension(DrawingMetrics.BAR_WIDTH + 16, 16)
            border = BorderFactory.createEmptyBorder(0, 8, 0, 8)
            alignmentX = Component.CENTER_ALIGNMENT
            font = Font("Dialog", Font.BOLD, 9)
            foreground = Color(165, 165, 165)
            toolTipText = "Recently used Drawing colors"
        }
    }

    private fun createSectionSeparator(): JPanel {
        return JPanel().apply {
            maximumSize = Dimension(DrawingMetrics.BAR_WIDTH, 8)
            preferredSize = Dimension(DrawingMetrics.BAR_WIDTH, 8)
            minimumSize = Dimension(DrawingMetrics.BAR_WIDTH, 8)
            isOpaque = false
            border = BorderFactory.createMatteBorder(1, 0, 0, 0, Color(65, 65, 65))
        }
    }

    private fun addStackedButton(container: JPanel, button: JButton) {
        button.alignmentX = Component.CENTER_ALIGNMENT
        container.add(button)
        container.add(Box.createVerticalStrut(6))
    }
}
