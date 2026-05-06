package com.floatbar

import com.intellij.openapi.components.service
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
    private val ownerFrame = owner
    private val stateService = project.service<FloatBarDrawingStateService>()
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
    private lateinit var clearButton: JButton
    private lateinit var toolStatusLabel: JLabel
    private lateinit var colorStatusLabel: JLabel
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
            updateHistoryButtonState(canUndo, canRedo)
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
        colorStatusLabel = JLabel("Color: #000000", SwingConstants.CENTER).apply {
            preferredSize = Dimension(88, 20)
            maximumSize = Dimension(88, 20)
            alignmentX = CENTER_ALIGNMENT
            font = Font("Dialog", Font.PLAIN, 10)
            isOpaque = true
            background = Color(38, 38, 38)
            foreground = Color(210, 210, 210)
            border = BorderFactory.createLineBorder(Color(80, 80, 80), 1)
            toolTipText = "Selected FloatBar color"
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
            toggleOverlayPreference()
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
        clearButton = createButton("Clear") {
            confirmAndClearCanvas()
        }.apply {
            toolTipText = "Clear all drawings from the current editor document. You will be asked to confirm first"
        }

        buttonColumn.add(toolStatusLabel)
        buttonColumn.add(Box.createVerticalStrut(4))
        buttonColumn.add(colorStatusLabel)
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

        repeat(6) { index ->
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
        updateClearButtonState()
        activeTool = canvasPanel.getCurrentToolMode()
        updateShapeButton()
        updateToolButtonStyles()
        pack()
        restoreFloatingBarLocation()

        val dragHandler = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                dragX = e.x
                dragY = e.y
            }

            override fun mouseDragged(e: MouseEvent) {
                val f = ownerFrame.bounds
                val newX = clampCoordinate(x + e.x - dragX, f.x, f.x + f.width - width)
                val newY = clampCoordinate(y + e.y - dragY, f.y, f.y + f.height - height)
                setLocation(newX, newY)
            }

            override fun mouseReleased(e: MouseEvent) {
                saveFloatingBarLocation()
            }
        }
        panel.addMouseListener(dragHandler)
        panel.addMouseMotionListener(dragHandler)
    }


    private fun restoreFloatingBarLocation() {
        val frameBounds = ownerFrame.bounds
        val savedLocation = stateService.getFloatingBarLocation()
        val targetX = savedLocation?.first ?: (frameBounds.x + 24)
        val targetY = savedLocation?.second ?: (frameBounds.y + 60)

        setLocation(
            clampCoordinate(targetX, frameBounds.x, frameBounds.x + frameBounds.width - width),
            clampCoordinate(targetY, frameBounds.y, frameBounds.y + frameBounds.height - height)
        )
    }

    private fun saveFloatingBarLocation() {
        stateService.setFloatingBarLocation(x, y)
    }

    private fun clampCoordinate(value: Int, min: Int, max: Int): Int {
        return if (max < min) min else value.coerceIn(min, max)
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
        applySavedOverlayPreference()
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
        val selectedShapeKind = canvasPanel.getSelectedShapeKind()
        for (shapeKind in ShapeKind.entries) {
            val isSelected = shapeKind == selectedShapeKind
            val label = if (isSelected) "[selected] ${shapeKind.displayName}" else shapeKind.displayName
            menu.add(JMenuItem(label).apply {
                toolTipText = if (isSelected) {
                    "Currently selected shape: ${shapeKind.displayName}"
                } else {
                    "Switch shape tool to ${shapeKind.displayName}"
                }
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
        val selectedColor = canvasPanel.getSelectedColor()
        recentColorButtons.forEachIndexed { index, button ->
            val color = colors.getOrNull(index)
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
    }

    private fun updateColorButton() {
        val color = canvasPanel.getSelectedColor()
        val colorHex = toHexColor(color)
        colorButton.background = color
        colorButton.foreground = readableForegroundFor(color)
        colorButton.text = colorHex
        colorButton.toolTipText = "Click to choose color. Selected: $colorHex / rgb(${color.red}, ${color.green}, ${color.blue})"
        if (::colorStatusLabel.isInitialized) {
            colorStatusLabel.text = "Color: $colorHex"
            colorStatusLabel.background = color
            colorStatusLabel.foreground = readableForegroundFor(color)
            colorStatusLabel.border = BorderFactory.createLineBorder(Color(95, 95, 95), 1)
            colorStatusLabel.toolTipText = "Selected color: $colorHex / rgb(${color.red}, ${color.green}, ${color.blue})"
        }
    }

    private fun readableForegroundFor(color: Color): Color {
        return if ((color.red * 299 + color.green * 587 + color.blue * 114) / 1000 < 140) {
            Color.WHITE
        } else {
            Color.BLACK
        }
    }

    private fun toHexColor(color: Color): String {
        return "#%02X%02X%02X".format(color.red, color.green, color.blue)
    }

    private fun hasSameRgb(left: Color, right: Color): Boolean {
        return left.red == right.red && left.green == right.green && left.blue == right.blue
    }

    private fun updateGridButton() {
        if (!::gridButton.isInitialized) return
        val enabled = canvasPanel.isGridEnabled()
        gridButton.text = if (enabled) "Grid ON" else "Grid OFF"
        gridButton.toolTipText = if (enabled) {
            "Hide the drawing alignment grid"
        } else {
            "Show the drawing alignment grid"
        }
        if (enabled) {
            gridButton.background = Color(70, 105, 75)
            gridButton.foreground = Color.WHITE
            gridButton.border = BorderFactory.createLineBorder(Color(130, 190, 135), 1)
        } else {
            gridButton.background = Color(50, 50, 50)
            gridButton.foreground = Color(220, 220, 220)
            gridButton.border = BorderFactory.createLineBorder(Color(100, 100, 100), 1)
        }
    }

    private fun applySavedOverlayPreference() {
        overlayController.setEnabled(stateService.isOverlayEnabled())
        updateOverlayButtonState(overlayController.isInstalled())
    }

    private fun toggleOverlayPreference() {
        val shouldEnableOverlay = !overlayController.isInstalled()
        stateService.setOverlayEnabled(shouldEnableOverlay)
        overlayController.setEnabled(shouldEnableOverlay)
        updateOverlayButtonState(overlayController.isInstalled())
    }

    private fun updateOverlayButtonState(installed: Boolean = overlayController.isInstalled()) {
        if (!::overlayButton.isInitialized) return
        overlayButton.text = if (installed) "Overlay ON" else "Overlay OFF"
        overlayButton.toolTipText = if (installed) {
            "Hide the editor drawing overlay. FloatBar will remember this choice"
        } else {
            "Show the editor drawing overlay. FloatBar will remember this choice"
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
        updateHistoryButtonState(
            canUndo = canvasPanel.canUndo(),
            canRedo = canvasPanel.canRedo()
        )
    }

    private fun updateHistoryButtonState(canUndo: Boolean, canRedo: Boolean) {
        if (!::undoButton.isInitialized || !::redoButton.isInitialized) return
        applyHistoryButtonStyle(
            button = undoButton,
            enabled = canUndo,
            enabledTooltip = "Undo the last drawing action",
            disabledTooltip = "Nothing to undo yet"
        )
        applyHistoryButtonStyle(
            button = redoButton,
            enabled = canRedo,
            enabledTooltip = "Redo the last undone drawing action",
            disabledTooltip = "Nothing to redo yet"
        )
        updateClearButtonState()
    }

    private fun updateClearButtonState() {
        if (!::clearButton.isInitialized) return
        val hasDrawings = canvasPanel.hasDrawings()
        clearButton.isEnabled = hasDrawings
        clearButton.toolTipText = if (hasDrawings) {
            "Clear all drawings from the current editor document. You will be asked to confirm first"
        } else {
            "No drawings to clear in the current editor document"
        }
        if (hasDrawings) {
            clearButton.background = Color(50, 50, 50)
            clearButton.foreground = Color(220, 220, 220)
            clearButton.border = BorderFactory.createLineBorder(Color(100, 100, 100), 1)
        } else {
            clearButton.background = Color(38, 38, 38)
            clearButton.foreground = Color(130, 130, 130)
            clearButton.border = BorderFactory.createLineBorder(Color(70, 70, 70), 1)
        }
    }

    private fun applyHistoryButtonStyle(
        button: JButton,
        enabled: Boolean,
        enabledTooltip: String,
        disabledTooltip: String
    ) {
        button.isEnabled = enabled
        button.toolTipText = if (enabled) enabledTooltip else disabledTooltip
        if (enabled) {
            button.background = Color(50, 50, 50)
            button.foreground = Color(220, 220, 220)
            button.border = BorderFactory.createLineBorder(Color(100, 100, 100), 1)
        } else {
            button.background = Color(38, 38, 38)
            button.foreground = Color(130, 130, 130)
            button.border = BorderFactory.createLineBorder(Color(70, 70, 70), 1)
        }
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
        updateClearButtonState()
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
