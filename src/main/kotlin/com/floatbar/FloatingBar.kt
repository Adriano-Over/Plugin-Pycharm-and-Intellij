package com.floatbar

import com.intellij.openapi.project.Project
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.Frame
import java.awt.GridLayout
import java.awt.Insets
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.SwingConstants

class FloatingBar(
    owner: Frame,
    project: Project
) : JDialog(owner, false) {

    private var dragX = 0
    private var dragY = 0
    private val visibilityListeners = linkedSetOf<(Boolean) -> Unit>()

    private val recentColorStore = RecentColorStore(project)
    private lateinit var overlayButton: JButton
    private lateinit var undoButton: JButton
    private lateinit var redoButton: JButton
    private lateinit var shapeButton: JButton
    private lateinit var drawingButton: JButton
    private lateinit var erasingButton: JButton
    private lateinit var fillButton: JButton
    private lateinit var colorButton: JButton
    private lateinit var gridButton: JButton
    private lateinit var toolStatusLabel: JLabel
    private val recentColorButtons = mutableListOf<JButton>()
    private var activeTool = FloatBarToolMode.DRAW
    private var startedDefaultActivation = false

    private val canvasPanel = DrawingCanvasPanel(
        project = project,
        recentColorStore = recentColorStore,
        onColorApplied = {
            setActiveTool(FloatBarToolMode.DRAW)
            updateColorButton()
            refreshRecentColorButtons()
        },
        onHistoryChanged = { canUndo, canRedo ->
            undoButton.isEnabled = canUndo
            redoButton.isEnabled = canRedo
        }
    )
    private val overlayController = EditorOverlayController(
        project = project,
        canvasPanel = canvasPanel,
        onOverlayChanged = ::updateOverlayButtonState
    )

    init {
        isUndecorated = true
        background = Color(0, 0, 0, 0)
        defaultCloseOperation = HIDE_ON_CLOSE

        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = Color(30, 30, 30, 215)
            border = BorderFactory.createLineBorder(Color(80, 80, 80), 1)
            isOpaque = true
        }

        val buttonColumn = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = BorderFactory.createEmptyBorder(6, 6, 6, 6)
        }
        toolStatusLabel = JLabel("Tool: Draw", SwingConstants.CENTER).apply {
            preferredSize = Dimension(88, 24)
            maximumSize = Dimension(88, 24)
            alignmentX = CENTER_ALIGNMENT
            font = Font("Dialog", Font.BOLD, 11)
            isOpaque = true
            background = Color(42, 42, 42)
            foreground = Color(235, 235, 235)
            border = BorderFactory.createLineBorder(Color(95, 95, 95), 1)
            toolTipText = "Current FloatBar tool"
        }
        val historyRow = JPanel(GridLayout(1, 2, 6, 0)).apply {
            isOpaque = false
            alignmentX = CENTER_ALIGNMENT
            maximumSize = Dimension(88, 28)
            preferredSize = Dimension(88, 28)
        }
        val recentGrid = JPanel(GridLayout(0, 2, 6, 6)).apply {
            isOpaque = false
            border = BorderFactory.createEmptyBorder(0, 6, 6, 6)
        }

        overlayButton = createButton("Overlay OFF") {
            overlayController.toggle()
        }.apply {
            toolTipText = "Show the editor drawing overlay"
        }
        drawingButton = createButton("Draw") {
            canvasPanel.setDrawingMode()
            setActiveTool(FloatBarToolMode.DRAW)
            updateShapeButton()
        }.apply {
            toolTipText = "Draw freehand strokes on the editor overlay"
        }
        erasingButton = createButton("Erase") {
            canvasPanel.setErasingMode()
            setActiveTool(FloatBarToolMode.ERASE)
            updateShapeButton()
        }.apply {
            toolTipText = "Erase strokes using the current eraser radius"
        }
        colorButton = createButton("Color") {
            canvasPanel.chooseColor(this)
        }.apply {
            toolTipText = "Choose the drawing and fill color"
        }
        fillButton = createButton("Fill") {
            canvasPanel.setFillMode()
            setActiveTool(FloatBarToolMode.FILL)
            updateShapeButton()
        }.apply {
            toolTipText = "Fill a bounded area with the selected color"
        }
        shapeButton = createButton("Shapes") {
            showShapesMenu()
        }.apply {
            toolTipText = "Choose and draw a shape"
        }
        gridButton = createButton("Grid ON") {
            canvasPanel.toggleGrid()
            updateGridButton()
        }.apply {
            toolTipText = "Toggle the drawing alignment grid"
        }
        undoButton = createHalfButton("Undo") {
            canvasPanel.undo()
            updateHistoryButtons()
        }.apply {
            toolTipText = "Undo the last drawing action"
        }
        redoButton = createHalfButton("Redo") {
            canvasPanel.redo()
            updateHistoryButtons()
        }.apply {
            toolTipText = "Redo the last undone drawing action"
        }
        val clearButton = createButton("Clear") {
            confirmAndClearCanvas()
        }.apply {
            toolTipText = "Clear all drawings from the current editor document. You will be asked to confirm first"
        }

        buttonColumn.add(toolStatusLabel)
        buttonColumn.add(Box.createVerticalStrut(6))

        listOf(overlayButton, drawingButton, erasingButton, colorButton, fillButton, shapeButton, gridButton).forEach { button ->
            button.alignmentX = CENTER_ALIGNMENT
            buttonColumn.add(button)
            buttonColumn.add(Box.createVerticalStrut(6))
        }

        historyRow.add(undoButton)
        historyRow.add(redoButton)
        buttonColumn.add(historyRow)
        buttonColumn.add(Box.createVerticalStrut(6))

        clearButton.alignmentX = CENTER_ALIGNMENT
        buttonColumn.add(clearButton)
        buttonColumn.add(Box.createVerticalStrut(6))

        repeat(5) { index ->
            val swatch = JButton().apply {
                preferredSize = Dimension(28, 28)
                minimumSize = Dimension(28, 28)
                maximumSize = Dimension(28, 28)
                isFocusPainted = false
                margin = Insets(0, 0, 0, 0)
                border = BorderFactory.createLineBorder(Color(110, 110, 110), 1)
                toolTipText = "Recent color ${index + 1}"
                addActionListener {
                    val color = recentColorStore.snapshot().getOrNull(index) ?: return@addActionListener
                    canvasPanel.setSelectedColor(color)
                    setActiveTool(FloatBarToolMode.DRAW)
                    updateColorButton()
                    refreshRecentColorButtons()
                    updateShapeButton()
                }
            }
            recentColorButtons += swatch
            recentGrid.add(swatch)
        }

        panel.add(buttonColumn)
        panel.add(recentGrid)
        contentPane.add(panel)

        updateColorButton()
        refreshRecentColorButtons()
        updateOverlayButtonState(overlayController.isInstalled())
        updateGridButton()
        updateHistoryButtons()
        updateShapeButton()
        updateToolButtonStyles()
        pack()

        val frameBounds = owner.bounds
        setLocation(frameBounds.x + 24, frameBounds.y + 60)

        val dragHandler = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                dragX = e.x
                dragY = e.y
            }

            override fun mouseDragged(e: MouseEvent) {
                val f = owner.bounds
                val newX = (x + e.x - dragX).coerceIn(f.x, f.x + f.width - width)
                val newY = (y + e.y - dragY).coerceIn(f.y, f.y + f.height - height)
                setLocation(newX, newY)
            }
        }
        panel.addMouseListener(dragHandler)
        panel.addMouseMotionListener(dragHandler)
    }

    fun addVisibilityListener(listener: (Boolean) -> Unit) {
        visibilityListeners += listener
        listener(isVisible)
    }

    override fun setVisible(visible: Boolean) {
        val changed = isVisible != visible
        super.setVisible(visible)
        if (changed) {
            visibilityListeners.forEach { it(visible) }
        }
    }

    fun toggle() {
        isVisible = !isVisible
    }

    fun activateByDefault() {
        if (startedDefaultActivation) return
        startedDefaultActivation = true

        showBar()
        overlayController.toggle()
    }

    fun showBar() {
        isVisible = true
    }

    fun hideBar() {
        isVisible = false
    }

    override fun dispose() {
        overlayController.dispose()
        super.dispose()
    }

    private fun createButton(text: String, onClick: () -> Unit): JButton {
        return JButton(text).apply {
            preferredSize = Dimension(88, 28)
            maximumSize = Dimension(88, 28)
            font = Font("Dialog", Font.PLAIN, 12)
            isFocusPainted = false
            isOpaque = true
            background = Color(50, 50, 50)
            foreground = Color(220, 220, 220)
            border = BorderFactory.createLineBorder(Color(100, 100, 100), 1)
            addActionListener { onClick() }
        }
    }

    private fun createHalfButton(text: String, onClick: () -> Unit): JButton {
        return JButton(text).apply {
            preferredSize = Dimension(41, 28)
            minimumSize = Dimension(41, 28)
            maximumSize = Dimension(41, 28)
            font = Font("Dialog", Font.PLAIN, 11)
            isFocusPainted = false
            isOpaque = true
            background = Color(50, 50, 50)
            foreground = Color(220, 220, 220)
            border = BorderFactory.createLineBorder(Color(100, 100, 100), 1)
            addActionListener { onClick() }
        }
    }

    private fun showShapesMenu() {
        val menu = JPopupMenu()
        for (shapeKind in ShapeKind.entries) {
            menu.add(JMenuItem(shapeKind.displayName).apply {
                addActionListener {
                    canvasPanel.setShapeMode(shapeKind)
                    setActiveTool(FloatBarToolMode.SHAPES)
                    updateShapeButton()
                }
            })
        }
        menu.show(shapeButton, 0, shapeButton.height)
    }

    private fun refreshRecentColorButtons() {
        val colors = recentColorStore.snapshot()
        recentColorButtons.forEachIndexed { index, button ->
            val color = colors.getOrNull(index)
            button.background = color ?: Color(60, 60, 60)
            button.isEnabled = color != null
            button.toolTipText = color?.let { "Recent color ${index + 1}: rgb(${it.red}, ${it.green}, ${it.blue})" } ?: "Empty recent color slot"
        }
    }

    private fun updateColorButton() {
        val color = canvasPanel.getSelectedColor()
        colorButton.background = color
        colorButton.foreground = if ((color.red * 299 + color.green * 587 + color.blue * 114) / 1000 < 140) {
            Color.WHITE
        } else {
            Color.BLACK
        }
        colorButton.toolTipText = "Selected color: rgb(${color.red}, ${color.green}, ${color.blue})"
    }

    private fun updateGridButton() {
        gridButton.text = if (canvasPanel.isGridEnabled()) "Grid ON" else "Grid OFF"
    }

    private fun updateOverlayButtonState(installed: Boolean = overlayController.isInstalled()) {
        if (!::overlayButton.isInitialized) return
        overlayButton.text = if (installed) "Overlay ON" else "Overlay OFF"
        overlayButton.toolTipText = if (installed) {
            "Hide the editor drawing overlay"
        } else {
            "Show the editor drawing overlay"
        }
        if (installed) {
            overlayButton.background = Color(70, 105, 75)
            overlayButton.foreground = Color.WHITE
            overlayButton.border = BorderFactory.createLineBorder(Color(130, 190, 135), 1)
        } else {
            overlayButton.background = Color(50, 50, 50)
            overlayButton.foreground = Color(220, 220, 220)
            overlayButton.border = BorderFactory.createLineBorder(Color(100, 100, 100), 1)
        }
    }

    private fun updateHistoryButtons() {
        undoButton.isEnabled = canvasPanel.canUndo()
        redoButton.isEnabled = canvasPanel.canRedo()
    }

    private fun confirmAndClearCanvas() {
        val confirmed = JOptionPane.showConfirmDialog(
            this,
            "Clear all drawings from the current editor document?",
            "Clear FloatBar drawings",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE
        ) == JOptionPane.YES_OPTION

        if (!confirmed) return

        canvasPanel.clearCanvas()
        updateHistoryButtons()
    }

    private fun updateShapeButton() {
        shapeButton.text = when (activeTool) {
            FloatBarToolMode.SHAPES -> canvasPanel.getSelectedShapeKind().displayName
            else -> "Shapes"
        }
        shapeButton.toolTipText = "Selected shape: ${canvasPanel.getSelectedShapeKind().displayName}. Click to choose a flowchart or basic shape"
        updateToolStatusLabel()
    }

    private fun setActiveTool(tool: FloatBarToolMode) {
        activeTool = tool
        updateToolButtonStyles()
    }

    private fun updateToolButtonStyles() {
        applyToolButtonStyle(drawingButton, activeTool == FloatBarToolMode.DRAW)
        applyToolButtonStyle(erasingButton, activeTool == FloatBarToolMode.ERASE)
        applyToolButtonStyle(fillButton, activeTool == FloatBarToolMode.FILL)
        applyToolButtonStyle(shapeButton, activeTool == FloatBarToolMode.SHAPES)
        updateToolStatusLabel()
    }

    private fun updateToolStatusLabel() {
        val toolText = when (activeTool) {
            FloatBarToolMode.DRAW -> "Tool: Draw"
            FloatBarToolMode.ERASE -> "Tool: Erase"
            FloatBarToolMode.FILL -> "Tool: Fill"
            FloatBarToolMode.SHAPES -> "Tool: ${canvasPanel.getSelectedShapeKind().displayName}"
        }
        toolStatusLabel.text = toolText
        toolStatusLabel.toolTipText = toolText
    }

    private fun applyToolButtonStyle(button: JButton, active: Boolean) {
        if (active) {
            button.background = Color(80, 120, 200)
            button.foreground = Color.WHITE
            button.border = BorderFactory.createLineBorder(Color(150, 190, 255), 1)
        } else {
            button.background = Color(50, 50, 50)
            button.foreground = Color(220, 220, 220)
            button.border = BorderFactory.createLineBorder(Color(100, 100, 100), 1)
        }
    }
}
