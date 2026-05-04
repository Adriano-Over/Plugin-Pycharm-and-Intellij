package com.floatbar

import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Point
import kotlin.math.roundToInt
import javax.swing.JDialog
import javax.swing.JPanel

class DrawingCanvasPanel(
    private val project: Project,
    private val recentColorStore: RecentColorStore,
    private val onColorApplied: () -> Unit = {},
    private val onHistoryChanged: (Boolean, Boolean) -> Unit = { _, _ -> }
) : JPanel() {

    private var editor: Editor? = null
    private var currentFile: VirtualFile? = null

    private val drawingStateService = project.service<FloatBarDrawingStateService>()

    private val strokeStore = DrawingStrokeStore(
        stateService = drawingStateService
    )
    private val historyStore = DrawingHistoryStore(maxUndoDepth = 50)

    private val persistenceDebounceMs = 200

    private var currentStroke: StrokePath? = null
    private var currentTool = FloatBarToolMode.DRAW
    private var toolPreviewPoint: Point? = null
    private var selectedShapeKind: ShapeKind = ShapeKind.RECTANGLE
    private var shapePreview: StrokePath? = null

    private var drawColor = loadPersistedDrawColor()
    private var gridEnabled = drawingStateService.isGridEnabled()
    private val eraseRadius = 9.0

    private val canvasPadding = 10
    private val gridExtendLeftPx = 8
    private val minCodeClearancePx = 8
    private val dirtyPaddingPx = 28
    private val eraseMinMovePx = 2.0

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
        eraseMinMovePx = eraseMinMovePx,
        shapeEdgeSpacing = shapeEdgeSpacing,
        ellipseSegments = ellipseSegments
    )

    private val canvasPainter = DrawingCanvasPainter(
        canvas = this,
        editorProvider = { editor },
        currentStrokesProvider = ::currentStrokes,
        shapePreviewProvider = { shapePreview },
        gridEnabledProvider = { gridEnabled },
        strokeRenderer = strokeRenderer,
        currentToolProvider = { currentTool },
        toolPreviewPointProvider = { toolPreviewPoint },
        eraseRadiusProvider = { eraseRadius },
        strokeWorkspace = strokeWorkspace
    )

    init {
        isOpaque = false
        preferredSize = Dimension(10, 10)
        updateToolCursor()

        val inputController = DrawingInputController(
            currentToolProvider = { currentTool },
            clampPoint = coordinateMapper::clampPointToDrawableArea,
            onToolPreviewPointChanged = ::setToolPreviewPoint,
            onFillPressed = canvasController::handleFillPressed,
            onErasePressed = canvasController::handleErasePressed,
            onEraseDragged = canvasController::handleEraseDragged,
            onEraseReleased = canvasController::handleEraseReleased,
            onShapePressed = { _ -> canvasController.handleShapePressed() },
            onShapeDragged = canvasController::handleShapeDragged,
            onShapeReleased = canvasController::handleShapeReleased,
            onDrawPressed = canvasController::handleDrawPressed,
            onDrawDragged = canvasController::handleDrawDragged,
            onDrawReleased = canvasController::handleDrawReleased
        )

        addMouseListener(inputController)
        addMouseMotionListener(inputController)
    }

    fun bindEditor(editor: Editor) {
        documentSync.persistCurrentStrokes()
        documentSync.unbindDocumentListener()
        this.editor = editor
        currentFile = FileDocumentManager.getInstance().getFile(editor.document)
        currentStroke = null
        shapePreview = null
        setToolPreviewPoint(null)
        updateToolCursor()
        documentSync.loadPersistedStrokes()
        documentSync.bindDocumentListener(editor.document)
        refreshHistoryState()
        repaint()
    }

    fun unbindEditor() {
        documentSync.persistCurrentStrokes()
        documentSync.unbindDocumentListener()
        editor = null
        currentFile = null
        currentStroke = null
        shapePreview = null
        setToolPreviewPoint(null)
        updateToolCursor()
        repaint()
    }

    fun setDrawingMode() {
        setTool(FloatBarToolMode.DRAW)
    }

    fun setErasingMode() {
        setTool(FloatBarToolMode.ERASE)
    }

    fun setFillMode() {
        setTool(FloatBarToolMode.FILL)
    }

    fun setShapeMode(shapeKind: ShapeKind) {
        selectedShapeKind = shapeKind
        setTool(FloatBarToolMode.SHAPES, clearPreview = false)
    }

    fun getSelectedShapeKind(): ShapeKind = selectedShapeKind

    fun setSelectedColor(color: Color) {
        drawColor = Color(color.red, color.green, color.blue, drawColor.alpha)
        persistSelectedColor()
        setTool(FloatBarToolMode.DRAW)
    }

    fun getSelectedColor(): Color = Color(drawColor.red, drawColor.green, drawColor.blue)

    fun chooseColor(parent: JDialog) {
        ColorPickerDialog(
            owner = parent,
            initialColor = getSelectedColor(),
            recentColors = recentColorStore.snapshot(),
            onChosen = { selected ->
                drawColor = Color(selected.red, selected.green, selected.blue, drawColor.alpha)
                persistSelectedColor()
                setTool(FloatBarToolMode.DRAW)
                recentColorStore.remember(selected)
                onColorApplied()
            }
        ).isVisible = true
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

    fun isGridEnabled(): Boolean = gridEnabled

    fun toggleGrid() {
        gridEnabled = !gridEnabled
        drawingStateService.setGridEnabled(gridEnabled)
        repaint()
    }

    private fun setTool(tool: FloatBarToolMode, clearPreview: Boolean = true) {
        val previousTool = currentTool
        currentTool = tool
        if (clearPreview) {
            shapePreview = null
        }
        updateToolCursor()
        if (previousTool == FloatBarToolMode.ERASE || tool == FloatBarToolMode.ERASE) {
            repaintToolPreviewAt(toolPreviewPoint)
        }
    }

    private fun setToolPreviewPoint(point: Point?) {
        val oldPoint = toolPreviewPoint
        if (oldPoint == point) return
        toolPreviewPoint = point

        if (currentTool == FloatBarToolMode.ERASE) {
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
        cursor = when (currentTool) {
            FloatBarToolMode.DRAW -> Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR)
            FloatBarToolMode.ERASE -> Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR)
            FloatBarToolMode.FILL -> Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            FloatBarToolMode.SHAPES -> Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR)
        }
    }

    private fun refreshHistoryState() {
        onHistoryChanged(canUndo(), canRedo())
    }

    private fun currentStrokes(): MutableList<StrokePath> = strokeWorkspace.currentStrokes()

    override fun paintComponent(graphics: Graphics) {
        super.paintComponent(graphics)
        canvasPainter.paint(graphics)
    }
}
