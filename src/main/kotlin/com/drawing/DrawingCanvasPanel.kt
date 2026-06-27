package com.drawing

import com.intellij.openapi.components.service
import com.intellij.openapi.editor.FoldRegion
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.ex.FoldingListener
import com.intellij.openapi.editor.ex.FoldingModelEx
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Insets
import java.awt.Point
import java.awt.Rectangle
import java.awt.Window
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import kotlin.math.roundToInt
import javax.swing.AbstractAction
import javax.swing.BorderFactory
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.KeyStroke
import javax.swing.SwingUtilities

class DrawingCanvasPanel(
    private val project: Project,
    private val recentColorStore: RecentColorStore,
    private val onColorApplied: () -> Unit = {},
    private val onHistoryChanged: (Boolean, Boolean) -> Unit = { _, _ -> }
) : JPanel() {

    private var editor: Editor? = null
    private var currentFile: VirtualFile? = null

    private val drawingStateService = project.service<DrawingStateService>()

    private val strokeStore = DrawingStrokeStore(
        stateService = drawingStateService
    )
    private val historyStore = DrawingHistoryStore(maxUndoDepth = 50)

    private val persistenceDebounceMs = 200

    private var currentStroke: StrokePath? = null
    private var currentTool = drawingStateService.getSelectedToolMode()
    private var toolPreviewPoint: Point? = null
    private var selectedShapeKind: ShapeKind = drawingStateService.getSelectedShapeKind()
    private var selectedDrawingShapeKind: ShapeKind = drawingStateService.getSelectedDrawingShapeKind()
    private var selectedTextStyle: BalloonTextStyle = drawingStateService.getSelectedTextStyle()
    private var selectedBalloonTextStyle: BalloonTextStyle = drawingStateService.getSelectedBalloonTextStyle()
    private var shapePreview: StrokePath? = null
    private var activeBalloonTextEditor: JTextArea? = null
    private var activeBalloonTextEditorCommit: (() -> Unit)? = null
    private var foldListenerDisposable = Disposer.newDisposable("DrawingFoldListener")
    private var collapsedFoldRegions: List<CollapsedFoldRegionSnapshot> = emptyList()

    private var drawColor = loadPersistedDrawColor()
    private var gridEnabled = drawingStateService.isGridEnabled()
    private var interactionPassThroughEnabled = drawingStateService.isInteractionPassThroughEnabled()
    private val eraseRadius = 9.0

    private val canvasPadding = 10
    private val gridExtendLeftPx = 10
    private val minCodeClearancePx = 8
    private val dirtyPaddingPx = 28
    private val eraseMinMovePx = 2.0

    private val drawSampleSpacingPx = 3.0
    private val freehandMinPointDistancePx = 3.0
    private val freehandSimplifyTolerancePx = 1.35
    private val freehandSimplifyMinPoints = 10

    private val shapeEdgeSpacing = 6.0
    private val ellipseSegments = 36

    private val coordinateMapper = DrawingCoordinateMapper(
        canvas = this,
        editorProvider = { editor },
        minCodeClearancePx = minCodeClearancePx
    )

    private val strokeRenderer = DrawingStrokeRenderer(
        canvasPadding = canvasPadding,
        gridExtendLeftPx = gridExtendLeftPx
    )

    private val strokeWorkspace = DrawingStrokeWorkspace(
        currentDocument = { editor?.document },
        strokeStore = strokeStore,
        coordinateMapper = coordinateMapper,
        strokeRenderer = strokeRenderer
    )

    private val strokePathTools = DrawingStrokePathTools(
        eraseRadius = eraseRadius,
        drawSampleSpacingPx = drawSampleSpacingPx,
        freehandSimplifyTolerancePx = freehandSimplifyTolerancePx,
        freehandSimplifyMinPoints = freehandSimplifyMinPoints,
        toViewPoint = coordinateMapper::toViewPoint
    )

    private val documentSync = DrawingDocumentSync(
        coordinateMapper = coordinateMapper,
        strokeStore = strokeStore,
        persistenceDebounceMs = persistenceDebounceMs,
        currentEditor = { editor },
        currentFilePath = { currentFile?.path },
        currentStrokes = ::currentStrokes,
        onDocumentStrokesRemapped = { document ->
            strokeWorkspace.rebuildStrokeBounds(document)
            strokeWorkspace.resetStrokeGeometryCache(document)
        },
        repaintCanvas = ::repaint
    )

    private val canvasController = DrawingCanvasController(
        canvas = this,
        editorProvider = { editor },
        currentStrokesProvider = ::currentStrokes,
        historyStore = historyStore,
        strokeWorkspace = strokeWorkspace,
        documentSync = documentSync,
        coordinateMapper = coordinateMapper,
        strokePathTools = strokePathTools,
        drawColorProvider = { drawColor },
        selectedShapeKindProvider = { selectedShapeKind },
        currentStrokeGetter = { currentStroke },
        currentStrokeSetter = { currentStroke = it },
        shapePreviewGetter = { shapePreview },
        shapePreviewSetter = { shapePreview = it },
        refreshHistoryState = ::refreshHistoryState,
        canvasPadding = canvasPadding,
        dirtyPaddingPx = dirtyPaddingPx,
        eraseRadius = eraseRadius,
        freehandMinPointDistancePx = freehandMinPointDistancePx,
        eraseMinMovePx = eraseMinMovePx,
        shapeEdgeSpacing = shapeEdgeSpacing,
        ellipseSegments = ellipseSegments,
        balloonTextStyleProvider = { textStyleForSelectedShape() },
        balloonTextEditor = ::openTextEditor
    )

    private val canvasPainter = DrawingCanvasPainter(
        canvas = this,
        editorProvider = { editor },
        currentStrokesProvider = ::currentStrokes,
        shapePreviewProvider = { shapePreview },
        collapsedFoldRegionsProvider = ::collapsedFoldRegionsSnapshot,
        selectedStrokeIdsProvider = canvasController::selectedStrokeIdsSnapshot,
        gridEnabledProvider = { gridEnabled },
        strokeRenderer = strokeRenderer,
        currentToolProvider = { currentTool },
        toolPreviewPointProvider = { toolPreviewPoint },
        eraseRadiusProvider = { eraseRadius },
        strokeWorkspace = strokeWorkspace
    )

    init {
        DrawingDiagnosticLog.configure(project)
        DrawingDiagnosticLog.info("PANEL", "DrawingCanvasPanel created")
        isOpaque = false
        background = Color(0, 0, 0, 0)
        isDoubleBuffered = false
        layout = null
        preferredSize = Dimension(10, 10)
        updateToolCursor()

        val inputController = DrawingInputController(
            currentToolProvider = { currentTool },
            interactionPassThroughEnabledProvider = { interactionPassThroughEnabled },
            clampPoint = { point, tool ->
                coordinateMapper.clampPointToDrawableArea(
                    point = point,
                    allowCodeArea = tool == DrawingToolMode.ERASE || tool == DrawingToolMode.SELECT,
                    rejectCodeArea = tool == DrawingToolMode.FILL
                )
            },
            onToolPreviewPointChanged = ::setToolPreviewPoint,
            onSelectPressed = canvasController::handleSelectPressed,
            onSelectDragged = canvasController::handleSelectDragged,
            onSelectReleased = canvasController::handleSelectReleased,
            onFillPressed = canvasController::handleFillPressed,
            onErasePressed = canvasController::handleErasePressed,
            onEraseDragged = canvasController::handleEraseDragged,
            onEraseReleased = canvasController::handleEraseReleased,
            onShapePressed = { _ -> canvasController.handleShapePressed() },
            onShapeDragged = canvasController::handleShapeDragged,
            onShapeReleased = canvasController::handleShapeReleased,
            onDrawPressed = canvasController::handleDrawPressed,
            onDrawDragged = canvasController::handleDrawDragged,
            onDrawReleased = canvasController::handleDrawReleased,
            onDrawGestureStarted = coordinateMapper::beginFreehandStraightWrap,
            onDrawGestureFinished = coordinateMapper::endFreehandStraightWrap,
            onMouseWheel = ::forwardMouseWheelToEditor,
            onPassthroughMouseEvent = ::forwardMouseEventToEditor
        )

        addMouseListener(inputController)
        addMouseMotionListener(inputController)
        addMouseWheelListener(inputController)
    }


    private fun forwardMouseWheelToEditor(event: MouseWheelEvent) {
        val currentEditor = editor ?: return
        val target = SwingUtilities.getAncestorOfClass(JScrollPane::class.java, currentEditor.contentComponent)
            ?: currentEditor.component
        val targetPoint = SwingUtilities.convertPoint(this, event.point, target)
        val forwardedEvent = MouseWheelEvent(
            target,
            event.id,
            event.`when`,
            event.modifiersEx,
            targetPoint.x,
            targetPoint.y,
            event.xOnScreen,
            event.yOnScreen,
            event.clickCount,
            event.isPopupTrigger,
            event.scrollType,
            event.scrollAmount,
            event.wheelRotation,
            event.preciseWheelRotation
        )
        target.dispatchEvent(forwardedEvent)
        event.consume()
    }

    private fun forwardMouseEventToEditor(event: MouseEvent) {
        val target = editor?.contentComponent ?: return
        val targetPoint = SwingUtilities.convertPoint(this, event.point, target)
        val forwardedEvent = MouseEvent(
            target,
            event.id,
            event.`when`,
            event.modifiersEx,
            targetPoint.x,
            targetPoint.y,
            event.xOnScreen,
            event.yOnScreen,
            event.clickCount,
            event.isPopupTrigger,
            event.button
        )
        target.dispatchEvent(forwardedEvent)
        event.consume()
    }

    fun bindEditor(editor: Editor) {
        DrawingDiagnosticLog.info("PANEL", "bindEditor beforePersist currentFile=${currentFile?.path} strokes=${currentStrokes().size}")
        documentSync.persistCurrentStrokes()
        documentSync.unbindDocumentListener()
        this.editor = editor
        currentFile = FileDocumentManager.getInstance().getFile(editor.document)
        DrawingDiagnosticLog.info("PANEL", "bindEditor newFile=${currentFile?.path} documentLines=${editor.document.lineCount}")
        currentStroke = null
        shapePreview = null
        canvasController.clearSelection(repaint = false)
        setToolPreviewPoint(null)
        updateToolCursor()
        documentSync.loadPersistedStrokes()
        DrawingDiagnosticLog.info("PANEL", "bindEditor loadedStrokes=${currentStrokes().size}")
        documentSync.bindDocumentListener(editor.document)
        bindFoldListener(editor)
        refreshFoldLayoutState(editor)
        refreshHistoryState()
        repaint()
    }

    fun unbindEditor() {
        DrawingDiagnosticLog.info("PANEL", "unbindEditor file=${currentFile?.path} strokes=${currentStrokes().size}")
        documentSync.persistCurrentStrokes()
        documentSync.unbindDocumentListener()
        unbindFoldListener()
        editor = null
        currentFile = null
        currentStroke = null
        shapePreview = null
        canvasController.clearSelection(repaint = false)
        setToolPreviewPoint(null)
        updateToolCursor()
        refreshFoldLayoutState(null)
        repaint()
    }

    fun setDrawingMode() {
        setTool(DrawingToolMode.DRAW)
    }

    fun setSelectMode() {
        setTool(DrawingToolMode.SELECT)
    }

    fun setErasingMode() {
        setTool(DrawingToolMode.ERASE)
    }

    fun setFillMode() {
        setTool(DrawingToolMode.FILL)
    }

    fun setShapeMode(shapeKind: ShapeKind) {
        selectedShapeKind = shapeKind
        if (!shapeKind.isTextOrBalloonShape()) {
            selectedDrawingShapeKind = shapeKind
            drawingStateService.setSelectedDrawingShapeKind(shapeKind)
        }
        persistSelectedShapeKind()
        setTool(DrawingToolMode.SHAPES, clearPreview = false)
    }

    fun getSelectedShapeKind(): ShapeKind = selectedShapeKind

    fun getSelectedDrawingShapeKind(): ShapeKind = selectedDrawingShapeKind

    fun activateLastDrawingShapeMode() {
        setShapeMode(selectedDrawingShapeKind)
    }

    fun getTextStyleFor(shapeKind: ShapeKind): BalloonTextStyle {
        return if (shapeKind == ShapeKind.BALLOON) selectedBalloonTextStyle else selectedTextStyle
    }

    fun setTextStyleFor(shapeKind: ShapeKind, style: BalloonTextStyle) {
        if (shapeKind == ShapeKind.BALLOON) {
            setBalloonTextStyle(style)
        } else {
            setTextStyle(style)
        }
    }

    fun getBalloonTextStyle(): BalloonTextStyle = selectedBalloonTextStyle

    fun setBalloonTextStyle(style: BalloonTextStyle) {
        selectedBalloonTextStyle = style
        drawingStateService.setSelectedBalloonTextStyle(style)
    }

    private fun setTextStyle(style: BalloonTextStyle) {
        selectedTextStyle = style
        drawingStateService.setSelectedTextStyle(style)
    }

    fun getCurrentToolMode(): DrawingToolMode = currentTool

    fun isInteractionPassThroughEnabled(): Boolean = interactionPassThroughEnabled

    fun setInteractionPassThroughEnabled(enabled: Boolean) {
        if (interactionPassThroughEnabled == enabled) return
        interactionPassThroughEnabled = enabled
        drawingStateService.setInteractionPassThroughEnabled(enabled)
        canvasController.cancelActiveInteractions()
        updateToolCursor()
        repaint()
    }

    fun setSelectedColor(color: Color) {
        drawColor = Color(color.red, color.green, color.blue, drawColor.alpha)
        persistSelectedColor()
    }

    fun getSelectedColor(): Color = Color(drawColor.red, drawColor.green, drawColor.blue)

    fun chooseColor(parent: Window) {
        ColorPickerDialog(
            owner = parent,
            initialColor = getSelectedColor(),
            recentColors = recentColorStore.snapshot(),
            onChosen = { selected ->
                drawColor = Color(selected.red, selected.green, selected.blue, drawColor.alpha)
                persistSelectedColor()
                recentColorStore.remember(selected)
                onColorApplied()
            }
        ).isVisible = true
    }

    private fun openTextEditor(bounds: Rectangle, onCommit: (String?) -> Unit) {
        activeBalloonTextEditorCommit?.invoke()

        val editorBounds = clampEditorBounds(bounds)
        val textArea = JTextArea().apply {
            lineWrap = true
            wrapStyleWord = true
            isOpaque = false
            foreground = getSelectedColor()
            caretColor = getSelectedColor()
            selectedTextColor = Color.WHITE
            selectionColor = Color(getSelectedColor().red, getSelectedColor().green, getSelectedColor().blue, 140)
            font = Font("Dialog", Font.PLAIN, (editorBounds.height / 2).coerceIn(10, 32))
            margin = Insets(2, 4, 2, 4)
            border = BorderFactory.createLineBorder(
                Color(getSelectedColor().red, getSelectedColor().green, getSelectedColor().blue, 160),
                1,
                true
            )
            toolTipText = "Type text. Enter commits, Shift+Enter adds a new line, Esc cancels."
            setBounds(editorBounds)
        }

        var finished = false
        fun finish(text: String?) {
            if (finished) return
            finished = true
            if (activeBalloonTextEditor == textArea) {
                activeBalloonTextEditor = null
                activeBalloonTextEditorCommit = null
            }
            remove(textArea)
            revalidate()
            repaintWithPadding(editorBounds, dirtyPaddingPx)
            onCommit(text)
        }

        textArea.getInputMap(JComponent.WHEN_FOCUSED)
            .put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "commitBalloonText")
        textArea.actionMap.put("commitBalloonText", object : AbstractAction() {
            override fun actionPerformed(event: java.awt.event.ActionEvent?) {
                finish(textArea.text)
            }
        })

        textArea.getInputMap(JComponent.WHEN_FOCUSED)
            .put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK), "newLineBalloonText")
        textArea.actionMap.put("newLineBalloonText", object : AbstractAction() {
            override fun actionPerformed(event: java.awt.event.ActionEvent?) {
                textArea.insert("\n", textArea.caretPosition)
            }
        })

        textArea.getInputMap(JComponent.WHEN_FOCUSED)
            .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "cancelBalloonText")
        textArea.actionMap.put("cancelBalloonText", object : AbstractAction() {
            override fun actionPerformed(event: java.awt.event.ActionEvent?) {
                finish(null)
            }
        })

        textArea.addFocusListener(object : FocusAdapter() {
            override fun focusLost(event: FocusEvent?) {
                finish(null)
            }
        })

        activeBalloonTextEditor = textArea
        activeBalloonTextEditorCommit = { finish(textArea.text) }
        add(textArea, 0)
        revalidate()
        repaintWithPadding(editorBounds, dirtyPaddingPx)
        SwingUtilities.invokeLater {
            if (!textArea.requestFocusInWindow()) {
                textArea.requestFocus()
            }
        }
    }

    private fun clampEditorBounds(bounds: Rectangle): Rectangle {
        val canvasBounds = Rectangle(0, 0, width.coerceAtLeast(1), height.coerceAtLeast(1))
        val clamped = bounds.intersection(canvasBounds)
        return if (clamped.width >= 20 && clamped.height >= 16) {
            clamped
        } else {
            Rectangle(
                bounds.x,
                bounds.y,
                bounds.width.coerceAtLeast(20),
                bounds.height.coerceAtLeast(16)
            )
        }
    }

    private fun repaintWithPadding(bounds: Rectangle, padding: Int) {
        val repaintBounds = Rectangle(bounds)
        repaintBounds.grow(padding, padding)
        repaint(repaintBounds)
    }

    fun clearCanvas() {
        canvasController.clearCanvas()
    }

    fun undo() {
        canvasController.undo()
    }

    fun redo() {
        canvasController.redo()
    }

    fun canUndo(): Boolean = historyStore.canUndo(editor?.document)
    fun canRedo(): Boolean = historyStore.canRedo(editor?.document)
    fun hasDrawings(): Boolean = currentStrokes().isNotEmpty()

    private fun loadPersistedDrawColor(): Color {
        val saved = Color(drawingStateService.getSelectedColorRgb(), true)
        return Color(saved.red, saved.green, saved.blue, 210)
    }

    private fun persistSelectedColor() {
        drawingStateService.setSelectedColorRgb(Color(drawColor.red, drawColor.green, drawColor.blue).rgb)
    }

    private fun persistSelectedShapeKind() {
        drawingStateService.setSelectedShapeKind(selectedShapeKind)
    }

    private fun ShapeKind.isTextOrBalloonShape(): Boolean {
        return this == ShapeKind.TEXT || this == ShapeKind.BALLOON
    }

    private fun textStyleForSelectedShape(): BalloonTextStyle {
        return getTextStyleFor(selectedShapeKind)
    }

    fun isGridEnabled(): Boolean = gridEnabled

    fun toggleGrid() {
        gridEnabled = !gridEnabled
        drawingStateService.setGridEnabled(gridEnabled)
        repaint()
    }

    private fun setTool(tool: DrawingToolMode, clearPreview: Boolean = true) {
        coordinateMapper.endFreehandStraightWrap()
        val previousTool = currentTool
        currentTool = tool
        drawingStateService.setSelectedToolMode(tool)
        if (clearPreview) {
            shapePreview = null
        }
        if (tool != DrawingToolMode.SELECT) {
            canvasController.clearSelection()
        }
        updateToolCursor()
        if (previousTool == DrawingToolMode.ERASE || tool == DrawingToolMode.ERASE) {
            repaintToolPreviewAt(toolPreviewPoint)
        }
    }

    private fun setToolPreviewPoint(point: Point?) {
        val oldPoint = toolPreviewPoint
        if (oldPoint == point) return
        toolPreviewPoint = point

        if (currentTool == DrawingToolMode.ERASE) {
            repaintToolPreviewAt(oldPoint)
            repaintToolPreviewAt(point)
        }
    }

    private fun repaintToolPreviewAt(point: Point?) {
        if (point == null) return
        DrawingViewportTools.repaintAround(
            canvas = this,
            points = listOf(point),
            padding = dirtyPaddingPx + eraseRadius.roundToInt() + 4
        )
    }

    private fun updateToolCursor() {
        cursor = if (interactionPassThroughEnabled) {
            Cursor.getDefaultCursor()
        } else {
            when (currentTool) {
                DrawingToolMode.SELECT -> Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
                DrawingToolMode.DRAW -> Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR)
                DrawingToolMode.ERASE -> Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR)
                DrawingToolMode.FILL -> Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                DrawingToolMode.SHAPES -> Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR)
            }
        }
    }

    private fun bindFoldListener(editor: Editor) {
        unbindFoldListener()
        val foldingModel = editor.foldingModel as? FoldingModelEx ?: return
        val listener = object : FoldingListener {
            override fun onFoldRegionStateChange(region: FoldRegion) {
                refreshAfterFoldChange(editor)
            }

            override fun onCustomFoldRegionPropertiesChange(region: com.intellij.openapi.editor.CustomFoldRegion, flags: Int) {
                refreshAfterFoldChange(editor)
            }

            override fun beforeFoldRegionDisposed(region: FoldRegion) = Unit

            override fun beforeFoldRegionRemoved(region: FoldRegion) = Unit

            override fun onFoldProcessingStart() = Unit

            override fun onFoldProcessingEnd() {
                refreshAfterFoldChange(editor)
            }
        }
        foldingModel.addListener(listener, foldListenerDisposable)
    }

    private fun unbindFoldListener() {
        runCatching { Disposer.dispose(foldListenerDisposable) }
        foldListenerDisposable = Disposer.newDisposable("DrawingFoldListener")
    }

    private fun refreshAfterFoldChange(editor: Editor) {
        refreshFoldLayoutState(editor)
        val document = editor.document
        strokeWorkspace.rebuildStrokeBounds(document)
        strokeWorkspace.resetStrokeGeometryCache(document)
        repaint()
    }

    private fun refreshFoldLayoutState(editor: Editor? = this.editor) {
        val currentEditor = editor ?: run {
            collapsedFoldRegions = emptyList()
            coordinateMapper.refreshFoldLayoutSignature()
            return
        }
        val currentDocument = currentEditor.document
        collapsedFoldRegions = computeCollapsedFoldRegions(currentEditor)
        coordinateMapper.refreshFoldLayoutSignature()
        if (currentDocument.lineCount <= 0) {
            collapsedFoldRegions = emptyList()
        }
    }

    private fun computeCollapsedFoldRegions(editor: Editor): List<CollapsedFoldRegionSnapshot> {
        val foldingModel = editor.foldingModel as? FoldingModelEx ?: return emptyList()
        return com.intellij.openapi.application.ApplicationManager.getApplication().runReadAction<List<CollapsedFoldRegionSnapshot>> {
            foldingModel.allFoldRegions
                .asSequence()
                .filterNot { it.isExpanded }
                .mapNotNull { region ->
                    val startLine = editor.document.getLineNumber(region.startOffset)
                    val startColumn = region.startOffset - editor.document.getLineStartOffset(startLine)
// Use the actual fold start column, not column 0
                    val foldStartPoint = editor.logicalPositionToXY(
                        com.intellij.openapi.editor.LogicalPosition(startLine, startColumn)
                    )
                    val placeholderText = region.placeholderText ?: "..."
// Use the same font metrics the content component actually uses for rendering
                    val placeholderWidth = editor.contentComponent.getFontMetrics(
                        editor.contentComponent.font
                    ).stringWidth(placeholderText)
                    CollapsedFoldRegionSnapshot(
                        startOffset = region.startOffset,
                        endOffset = region.endOffset,
                        placeholderPoint = foldStartPoint,
                        placeholderWidth = placeholderWidth
                    )
                }
                .toList()
        }
    }

    fun collapsedFoldRegionsSnapshot(): List<CollapsedFoldRegionSnapshot> = collapsedFoldRegions.toList()

    private fun refreshHistoryState() {
        onHistoryChanged(canUndo(), canRedo())
    }

    private fun currentStrokes(): MutableList<StrokePath> = strokeWorkspace.currentStrokes()

    override fun isOpaque(): Boolean = false

    override fun paintComponent(graphics: Graphics) {
        canvasPainter.paint(graphics)
    }
}
