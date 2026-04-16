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
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu

class FloatingBar(
    owner: Frame,
    project: Project
) : JDialog(owner, false) {

    private var dragX = 0
    private var dragY = 0
    private val visibilityListeners = mutableListOf<(Boolean) -> Unit>()

    private val recentColorStore = RecentColorStore(project)
    private lateinit var undoButton: JButton
    private lateinit var redoButton: JButton
    private lateinit var shapeButton: JButton
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
    private val overlayController = EditorOverlayController(project, canvasPanel)

    
    private var startedDefaultActivation = false
private lateinit var drawingButton: JButton
    private lateinit var erasingButton: JButton
    private lateinit var fillButton: JButton
    private lateinit var colorButton: JButton
    private lateinit var gridButton: JButton
    private val recentColorButtons = mutableListOf<JButton>()
    private var activeTool = FloatBarToolMode.DRAW

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

        val overlayButton = createButton("Overlay") { overlayController.toggle() }
        drawingButton = createButton("Draw") {
            canvasPanel.setDrawingMode()
            setActiveTool(FloatBarToolMode.DRAW)
            updateShapeButton()
        }
        erasingButton = createButton("Erase") {
            canvasPanel.setErasingMode()
            setActiveTool(FloatBarToolMode.ERASE)
            updateShapeButton()
        }
        colorButton = createButton("Color") {
            canvasPanel.chooseColor(this)
        }
        fillButton = createButton("Fill") {
            canvasPanel.setFillMode()
            setActiveTool(FloatBarToolMode.FILL)
            updateShapeButton()
        }
        shapeButton = createButton("Shapes") {
            showShapesMenu()
        }
        gridButton = createButton("Grid ON") {
            canvasPanel.toggleGrid()
            updateGridButton()
        }
        undoButton = createHalfButton("Undo") {
            canvasPanel.undo()
            updateHistoryButtons()
        }
        redoButton = createHalfButton("Redo") {
            canvasPanel.redo()
            updateHistoryButtons()
        }
        val clearButton = createButton("Clear") {
            canvasPanel.clearCanvas()
            updateHistoryButtons()
        }

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
    }

    fun removeVisibilityListener(listener: (Boolean) -> Unit) {
        visibilityListeners -= listener
    }

    fun toggle() {
        isVisible = !isVisible
        visibilityListeners.forEach { it(isVisible) }
    }


    fun activateByDefault() {
        if (startedDefaultActivation) return
        startedDefaultActivation = true

        if (!isVisible) {
            isVisible = true
        }

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
            margin = Insets(0, 0, 0, 0)
            addActionListener { onClick() }
        }
    }

private fun showShapesMenu() {
    val menu = JPopupMenu()
    addShapeMenuItem(menu, "Rectangle", ShapeKind.RECTANGLE)
    addShapeMenuItem(menu, "Ellipse", ShapeKind.ELLIPSE)
    addShapeMenuItem(menu, "Line", ShapeKind.LINE)
    menu.addSeparator()
    addShapeMenuItem(menu, "Arrow", ShapeKind.ARROW)
    addShapeMenuItem(menu, "Process", ShapeKind.PROCESS)
    addShapeMenuItem(menu, "Decision / If", ShapeKind.DECISION)
    addShapeMenuItem(menu, "Start / End", ShapeKind.START_END)
    addShapeMenuItem(menu, "Input / Output", ShapeKind.INPUT_OUTPUT)
    addShapeMenuItem(menu, "Document", ShapeKind.DOCUMENT)
    addShapeMenuItem(menu, "Connector", ShapeKind.CONNECTOR)
    menu.show(shapeButton, 0, shapeButton.height)
}

    private fun addShapeMenuItem(
        menu: JPopupMenu,
        label: String,
        shapeKind: ShapeKind
    ) {
        menu.add(JMenuItem(label).apply {
            addActionListener {
                canvasPanel.setShapeMode(shapeKind)
                setActiveTool(FloatBarToolMode.SHAPES)
                updateShapeButton()
            }
        })
    }

    private fun styleButtonInactive(button: JButton) {
        button.background = Color(50, 50, 50)
        button.foreground = Color(220, 220, 220)
        button.border = BorderFactory.createLineBorder(Color(100, 100, 100), 1)
    }

    private fun styleButtonActive(button: JButton) {
        button.background = Color(85, 115, 170)
        button.foreground = Color.WHITE
        button.border = BorderFactory.createLineBorder(Color(170, 210, 255), 2)
    }

    private fun styleButtonDisabled(button: JButton) {
        button.background = Color(42, 42, 42)
        button.foreground = Color(120, 120, 120)
        button.border = BorderFactory.createLineBorder(Color(80, 80, 80), 1)
    }

    private fun setActiveTool(tool: FloatBarToolMode) {
        activeTool = tool
        updateToolButtonStyles()
    }

    private fun updateToolButtonStyles() {
        if (!::drawingButton.isInitialized || !::erasingButton.isInitialized || !::fillButton.isInitialized || !::shapeButton.isInitialized) return
        styleButtonInactive(drawingButton)
        styleButtonInactive(erasingButton)
        styleButtonInactive(fillButton)
        styleButtonInactive(shapeButton)
        when (activeTool) {
            FloatBarToolMode.DRAW -> styleButtonActive(drawingButton)
            FloatBarToolMode.ERASE -> styleButtonActive(erasingButton)
            FloatBarToolMode.FILL -> styleButtonActive(fillButton)
            FloatBarToolMode.SHAPES -> styleButtonActive(shapeButton)
        }
    }

    private fun updateColorButton() {
        if (!::colorButton.isInitialized) return
        val color = canvasPanel.getSelectedColor()
        colorButton.background = Color(color.red, color.green, color.blue)
        colorButton.foreground = if ((color.red * 0.299 + color.green * 0.587 + color.blue * 0.114) > 186) {
            Color.BLACK
        } else {
            Color.WHITE
        }
        colorButton.toolTipText = "Current color: #${"%02X%02X%02X".format(color.red, color.green, color.blue)}"
    }

    private fun updateGridButton() {
        if (!::gridButton.isInitialized) return
        val enabled = canvasPanel.isGridEnabled()
        gridButton.text = if (enabled) "Grid ON" else "Grid OFF"
        if (enabled) {
            styleButtonActive(gridButton)
        } else {
            styleButtonInactive(gridButton)
        }
        gridButton.toolTipText = if (enabled) "Hide overlay grid" else "Show overlay grid"
    }

    private fun updateShapeButton() {
    if (!::shapeButton.isInitialized) return
    shapeButton.text = "Shapes ▾"
    shapeButton.toolTipText = "Selected shape: ${canvasPanel.getSelectedShapeKind().displayName}. Click to choose a flowchart or basic shape"
}

    private fun updateHistoryButtons() {
        if (!::undoButton.isInitialized || !::redoButton.isInitialized) return

        undoButton.isEnabled = canvasPanel.canUndo()
        redoButton.isEnabled = canvasPanel.canRedo()

        if (undoButton.isEnabled) {
            styleButtonInactive(undoButton)
        } else {
            styleButtonDisabled(undoButton)
        }

        if (redoButton.isEnabled) {
            styleButtonInactive(redoButton)
        } else {
            styleButtonDisabled(redoButton)
        }
    }

    private fun refreshRecentColorButtons() {
        val recents = recentColorStore.snapshot()
        recentColorButtons.forEachIndexed { index, button ->
            val color = recents.getOrNull(index)
            button.background = color ?: Color(60, 60, 60)
            button.isEnabled = color != null
            button.toolTipText = color?.let { "Recent color #${"%02X%02X%02X".format(it.red, it.green, it.blue)}" } ?: "Empty"
        }
    }
}
