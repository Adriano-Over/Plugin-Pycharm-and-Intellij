package com.floatbar

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowType
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Container
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.AbstractButton
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JMenuItem
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.Scrollable
import javax.swing.SwingUtilities

class FloatBarToolWindowPanel(
    private val project: Project,
    private val toolWindow: ToolWindow
) : JPanel(), Disposable, Scrollable {
    private val stateService = project.service<FloatBarDrawingStateService>()
    private val recentColorStore = RecentColorStore(project)
    private lateinit var controls: FloatingBarToolbarControls
    private var activeTool = FloatBarToolMode.DRAW
    private var dragStartOnScreen: Point? = null
    private var windowStartLocation: Point? = null
    private val nativeMenuFilter = FloatBarToolWindowMenuFilter(toolWindow)

    private val canvasPanel = DrawingCanvasPanel(
        project = project,
        recentColorStore = recentColorStore,
        onColorApplied = {
            syncActiveToolFromCanvas()
            updateColorButton()
            refreshRecentColorButtons()
            updateShapeButton()
        },
        onHistoryChanged = { canUndo, canRedo ->
            updateHistoryButtonState(canUndo, canRedo)
        }
    )

    private val overlayController = EditorOverlayController(
        project = project,
        canvasPanel = canvasPanel,
        onOverlayChanged = { updateOverlayButtonState() }
    )

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false

        val toolbarView = createToolbarView()
        controls = toolbarView.controls
        lockToolbarSize(toolbarView.rootPanel)
        add(toolbarView.rootPanel)
        installToolWindowDragHandler(toolbarView.headerPanel)

        restoreToolbarStyles()
        activeTool = canvasPanel.getCurrentToolMode()
        updateShapeButton()
        updateToolButtonStyles()
        applySavedOverlayPreference()
        applySavedPassThroughPreference()
    }

    private fun lockToolbarSize(toolbarPanel: JPanel) {
        val compactSize = toolbarPanel.preferredSize
        toolbarPanel.minimumSize = compactSize
        toolbarPanel.preferredSize = compactSize
        toolbarPanel.maximumSize = compactSize
        toolbarPanel.alignmentX = LEFT_ALIGNMENT
        minimumSize = compactSize
        preferredSize = compactSize
        maximumSize = compactSize
    }

    private fun installToolWindowDragHandler(headerPanel: JPanel) {
        val dragHandler = object : MouseAdapter() {
            override fun mousePressed(event: MouseEvent) {
                if (showModeMenuIfPopup(event)) return
                if (!canDragToolWindow()) return
                dragStartOnScreen = event.locationOnScreen
                windowStartLocation = SwingUtilities.getWindowAncestor(this@FloatBarToolWindowPanel)?.location
            }

            override fun mouseReleased(event: MouseEvent) {
                if (showModeMenuIfPopup(event)) return
                dragStartOnScreen = null
                windowStartLocation = null
            }

            override fun mouseDragged(event: MouseEvent) {
                if (!canDragToolWindow()) return
                val dragStart = dragStartOnScreen ?: return
                val windowStart = windowStartLocation ?: return
                val window = SwingUtilities.getWindowAncestor(this@FloatBarToolWindowPanel) ?: return
                window.setLocation(
                    windowStart.x + event.xOnScreen - dragStart.x,
                    windowStart.y + event.yOnScreen - dragStart.y
                )
            }
        }
        installDragHandlerRecursively(headerPanel, dragHandler)
    }

    private fun showModeMenuIfPopup(event: MouseEvent): Boolean {
        if (!event.isPopupTrigger && !SwingUtilities.isRightMouseButton(event)) {
            return false
        }

        JPopupMenu().apply {
            add(JMenuItem("Float").apply {
                addActionListener { floatToolWindow() }
            })
            add(JMenuItem("Dock Pinned").apply {
                addActionListener { dockPinnedToolWindow() }
            })
            show(event.component, event.x, event.y)
        }
        return true
    }

    private fun floatToolWindow() {
        toolWindow.setType(ToolWindowType.FLOATING, null)
    }

    private fun dockPinnedToolWindow() {
        toolWindow.setType(ToolWindowType.DOCKED, null)
        toolWindow.setAutoHide(false)
    }

    private fun installDragHandlerRecursively(component: Component, dragHandler: MouseAdapter) {
        if (component is AbstractButton) return

        component.cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
        component.addMouseListener(dragHandler)
        component.addMouseMotionListener(dragHandler)

        if (component is Container) {
            component.components.forEach { child ->
                installDragHandlerRecursively(child, dragHandler)
            }
        }
    }

    private fun canDragToolWindow(): Boolean {
        return toolWindow.type == ToolWindowType.FLOATING || toolWindow.type == ToolWindowType.WINDOWED
    }

    private fun createToolbarView(): FloatingBarToolbarView {
        return FloatingBarToolbarPanel(
            restoreToolbarStyles = ::restoreToolbarStyles,
            headerTooltip = "Use IntelliJ Tool Window controls to dock, float, window, or hide FloatBar",
            onHide = ::hideToolWindow,
            onOverlay = ::toggleOverlayPreference,
            onPassThrough = ::togglePassThroughPreference,
            onSelect = {
                canvasPanel.setSelectMode()
                setActiveTool(FloatBarToolMode.SELECT)
                updateShapeButton()
            },
            onDraw = {
                canvasPanel.setDrawingMode()
                setActiveTool(FloatBarToolMode.DRAW)
                updateShapeButton()
            },
            onErase = {
                canvasPanel.setErasingMode()
                setActiveTool(FloatBarToolMode.ERASE)
                updateShapeButton()
            },
            onColor = {
                val owner = SwingUtilities.getWindowAncestor(this) ?: JOptionPane.getRootFrame()
                canvasPanel.chooseColor(owner)
            },
            onFill = {
                canvasPanel.setFillMode()
                setActiveTool(FloatBarToolMode.FILL)
                updateShapeButton()
            },
            onShapes = ::showShapesMenu,
            onText = {
                activateTextShape(ShapeKind.TEXT, canvasPanel.getTextStyleFor(ShapeKind.TEXT))
                showTextStyleMenu(ShapeKind.TEXT, controls.textButton)
            },
            onBalloon = {
                activateTextShape(ShapeKind.BALLOON, canvasPanel.getTextStyleFor(ShapeKind.BALLOON))
                showTextStyleMenu(ShapeKind.BALLOON, controls.balloonButton)
            },
            onGrid = {
                canvasPanel.toggleGrid()
                updateGridButton()
            },
            onUndo = {
                canvasPanel.undo()
                updateHistoryButtons()
            },
            onRedo = {
                canvasPanel.redo()
                updateHistoryButtons()
            },
            onClear = ::confirmAndClearCanvas,
            onRecentColor = ::applyRecentColor
        ).create()
    }

    private fun hideToolWindow() {
        toolWindow.hide(null)
    }

    private fun applyRecentColor(index: Int) {
        val color = recentColorStore.snapshot().getOrNull(index) ?: return
        canvasPanel.setSelectedColor(color)
        syncActiveToolFromCanvas()
        updateColorButton()
        refreshRecentColorButtons()
        updateShapeButton()
    }

    private fun restoreToolbarStyles() {
        if (!::controls.isInitialized) return
        updateColorButton()
        updateOverlayButtonState()
        updatePassThroughButtonState()
        updateGridButton()
        updateHistoryButtons()
        updateClearButtonState()
        updateToolButtonStyles()
        updateShapeButton()
        refreshRecentColorButtons()
    }

    private fun showShapesMenu() {
        canvasPanel.activateLastDrawingShapeMode()
        setActiveTool(FloatBarToolMode.SHAPES)
        updateShapeButton()

        val menu = ShapeMenuFactory.createShapeMenu(
            selectedShapeKind = canvasPanel.getSelectedShapeKind(),
            onShapeSelected = { shapeKind ->
                canvasPanel.setShapeMode(shapeKind)
                setActiveTool(FloatBarToolMode.SHAPES)
                updateShapeButton()
            }
        )
        menu.show(controls.shapeButton, 0, controls.shapeButton.height)
    }

    private fun showTextStyleMenu(shapeKind: ShapeKind, anchorButton: JButton) {
        val menu = JPopupMenu()
        for (style in BalloonTextStyle.entries) {
            val isSelected = style == canvasPanel.getTextStyleFor(shapeKind)
            val label = if (isSelected) "[selected] ${style.displayName}" else style.displayName
            menu.add(JMenuItem(label).apply {
                toolTipText = when (style) {
                    BalloonTextStyle.OUTLINE -> "Use hollow outline letters"
                    BalloonTextStyle.SOLID -> "Use filled text made like normal draw-tool strokes"
                }
                addActionListener { activateTextShape(shapeKind, style) }
            })
        }
        menu.show(anchorButton, 0, anchorButton.height)
    }

    private fun activateTextShape(shapeKind: ShapeKind, style: BalloonTextStyle) {
        canvasPanel.setTextStyleFor(shapeKind, style)
        canvasPanel.setShapeMode(shapeKind)
        setActiveTool(FloatBarToolMode.SHAPES)
        updateShapeButton()
    }

    private fun refreshRecentColorButtons() {
        val colors = recentColorStore.snapshot()
        val selectedColor = canvasPanel.getSelectedColor()
        controls.recentColorButtons.forEachIndexed { index, button ->
            FloatingBarStateBinder.applyRecentColorButton(
                button = button,
                index = index,
                color = colors.getOrNull(index),
                selectedColor = selectedColor
            )
        }
    }

    private fun updateColorButton() {
        val color = canvasPanel.getSelectedColor()
        FloatingBarStateBinder.applyColorButton(controls.colorButton, color)
        FloatingBarStateBinder.applyColorStatusLabel(controls.colorStatusLabel, color)
    }

    private fun updateGridButton() {
        FloatingBarStateBinder.applyGridButton(controls.gridButton, canvasPanel.isGridEnabled())
    }

    private fun applySavedOverlayPreference() {
        overlayController.setEnabled(stateService.isOverlayEnabled())
        updateOverlayButtonState()
    }

    private fun applySavedPassThroughPreference() {
        canvasPanel.setInteractionPassThroughEnabled(stateService.isInteractionPassThroughEnabled())
        updatePassThroughButtonState()
    }

    private fun toggleOverlayPreference() {
        val shouldEnableOverlay = !overlayController.isEnabled()
        stateService.setOverlayEnabled(shouldEnableOverlay)
        overlayController.setEnabled(shouldEnableOverlay)
        updateOverlayButtonState()
    }

    private fun togglePassThroughPreference() {
        val shouldEnablePassThrough = !canvasPanel.isInteractionPassThroughEnabled()
        stateService.setInteractionPassThroughEnabled(shouldEnablePassThrough)
        canvasPanel.setInteractionPassThroughEnabled(shouldEnablePassThrough)
        updatePassThroughButtonState()
    }

    private fun updateOverlayButtonState(enabled: Boolean = overlayController.isEnabled()) {
        if (!::controls.isInitialized) return
        FloatingBarStateBinder.applyOverlayButton(controls.overlayButton, enabled)
    }

    private fun updatePassThroughButtonState(enabled: Boolean = canvasPanel.isInteractionPassThroughEnabled()) {
        if (!::controls.isInitialized) return
        FloatingBarStateBinder.applyPassThroughButton(controls.passThroughButton, enabled)
    }

    private fun updateHistoryButtons() {
        updateHistoryButtonState(
            canUndo = canvasPanel.canUndo(),
            canRedo = canvasPanel.canRedo()
        )
    }

    private fun updateHistoryButtonState(canUndo: Boolean, canRedo: Boolean) {
        if (!::controls.isInitialized) return
        FloatingBarStateBinder.applyHistoryButton(
            button = controls.undoButton,
            enabled = canUndo,
            enabledTooltip = "Undo the last drawing action",
            disabledTooltip = "Nothing to undo yet"
        )
        FloatingBarStateBinder.applyHistoryButton(
            button = controls.redoButton,
            enabled = canRedo,
            enabledTooltip = "Redo the last undone drawing action",
            disabledTooltip = "Nothing to redo yet"
        )
        updateClearButtonState()
    }

    private fun updateClearButtonState() {
        if (!::controls.isInitialized) return
        FloatingBarStateBinder.applyClearButton(controls.clearButton, canvasPanel.hasDrawings())
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
        val selectedShapeKind = canvasPanel.getSelectedShapeKind()
        val selectedShapeName = selectedShapeKind.displayName
        controls.shapeButton.text = if (selectedShapeKind == ShapeKind.TEXT || selectedShapeKind == ShapeKind.BALLOON) {
            "Shapes"
        } else {
            FloatingBarStateBinder.shapeButtonText(activeTool, selectedShapeName)
        }
        val activeTextInfo = if (selectedShapeKind == ShapeKind.TEXT || selectedShapeKind == ShapeKind.BALLOON) {
            ". Text style: ${canvasPanel.getTextStyleFor(selectedShapeKind).displayName}"
        } else {
            ""
        }
        controls.shapeButton.toolTipText =
            "Selected shape: $selectedShapeName$activeTextInfo. Click to choose a flowchart or basic shape"
        controls.textButton.toolTipText =
            "Text style: ${canvasPanel.getTextStyleFor(ShapeKind.TEXT).displayName}. Click to choose Filled or Hollow text"
        controls.balloonButton.toolTipText =
            "Balloon text style: ${canvasPanel.getTextStyleFor(ShapeKind.BALLOON).displayName}. Click to choose Filled or Hollow text"
        updateToolStatusLabel()
    }

    private fun setActiveTool(tool: FloatBarToolMode) {
        activeTool = tool
        updateToolButtonStyles()
    }

    private fun syncActiveToolFromCanvas() {
        setActiveTool(canvasPanel.getCurrentToolMode())
    }

    private fun updateToolButtonStyles() {
        FloatingBarStateBinder.applyToolButton(
            controls.selectButton,
            activeTool == FloatBarToolMode.SELECT,
            FloatBarToolMode.SELECT
        )
        FloatingBarStateBinder.applyToolButton(
            controls.drawingButton,
            activeTool == FloatBarToolMode.DRAW,
            FloatBarToolMode.DRAW
        )
        FloatingBarStateBinder.applyToolButton(
            controls.erasingButton,
            activeTool == FloatBarToolMode.ERASE,
            FloatBarToolMode.ERASE
        )
        FloatingBarStateBinder.applyToolButton(
            controls.fillButton,
            activeTool == FloatBarToolMode.FILL,
            FloatBarToolMode.FILL
        )
        FloatingBarStateBinder.applyShapeToolButton(
            button = controls.textButton,
            active = activeTool == FloatBarToolMode.SHAPES && canvasPanel.getSelectedShapeKind() == ShapeKind.TEXT
        )
        FloatingBarStateBinder.applyShapeToolButton(
            button = controls.balloonButton,
            active = activeTool == FloatBarToolMode.SHAPES && canvasPanel.getSelectedShapeKind() == ShapeKind.BALLOON
        )
        FloatingBarStateBinder.applyShapeToolButton(
            button = controls.shapeButton,
            active = activeTool == FloatBarToolMode.SHAPES &&
                canvasPanel.getSelectedShapeKind() != ShapeKind.TEXT &&
                canvasPanel.getSelectedShapeKind() != ShapeKind.BALLOON
        )
        updateToolStatusLabel()
    }

    private fun updateToolStatusLabel() {
        FloatingBarStateBinder.applyToolStatusLabel(
            label = controls.toolStatusLabel,
            activeTool = activeTool,
            selectedShapeName = canvasPanel.getSelectedShapeKind().displayName
        )
    }

    override fun dispose() {
        nativeMenuFilter.dispose()
        overlayController.dispose()
    }

    override fun getPreferredScrollableViewportSize(): Dimension = preferredSize

    override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int = 16

    override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int = 64

    override fun getScrollableTracksViewportWidth(): Boolean = false

    override fun getScrollableTracksViewportHeight(): Boolean = false
}
