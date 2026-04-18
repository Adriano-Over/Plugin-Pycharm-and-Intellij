package com.floatbar

import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
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

    private val strokeStore = DrawingStrokeStore(
        stateService = project.service<FloatBarDrawingStateService>()
    )
    private val historyStore = DrawingHistoryStore(maxUndoDepth = 50)

    private val persistenceDebounceMs = 200

    private var currentStroke: StrokePath? = null
    private var currentTool = FloatBarToolMode.DRAW
    private var selectedShapeKind: ShapeKind = ShapeKind.RECTANGLE
    private var shapePreview: StrokePath? = null

    private var drawColor = Color(255, 0, 0, 210)
    private var gridEnabled = true
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
        refreshHistoryState = { onHistoryChanged(canUndo(), canRedo()) },
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
        strokeWorkspace = strokeWorkspace
    )

    init {
        isOpaque = false
        preferredSize = Dimension(10, 10)

        val inputController = DrawingInputController(
            currentToolProvider = { currentTool },
            clampPoint = coordinateMapper::clampPointToDrawableArea,
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
        this.currentFile = FileDocumentManager.getInstance().getFile(editor.document)
        currentStroke = null
        shapePreview = null
        documentSync.loadPersistedStrokes()
        documentSync.bindDocumentListener(editor.document)
        onHistoryChanged(canUndo(), canRedo())
        repaint()
    }

    fun unbindEditor() {
        documentSync.persistCurrentStrokes()
        documentSync.unbindDocumentListener()
        editor = null
        currentFile = null
        currentStroke = null
        shapePreview = null
        repaint()
    }

    fun setDrawingMode() {
        currentTool = FloatBarToolMode.DRAW
        shapePreview = null
    }

    fun setErasingMode() {
        currentTool = FloatBarToolMode.ERASE
        shapePreview = null
    }

    fun setFillMode() {
        currentTool = FloatBarToolMode.FILL
        shapePreview = null
    }

    fun setShapeMode(shapeKind: ShapeKind) {
        currentTool = FloatBarToolMode.SHAPES
        selectedShapeKind = shapeKind
    }

    fun getSelectedShapeKind(): ShapeKind = selectedShapeKind

    fun setSelectedColor(color: Color) {
        drawColor = Color(color.red, color.green, color.blue, drawColor.alpha)
        currentTool = FloatBarToolMode.DRAW
    }

    fun getSelectedColor(): Color = Color(drawColor.red, drawColor.green, drawColor.blue)

    fun chooseColor(parent: JDialog) {
        ColorPickerDialog(
            owner = parent,
            initialColor = getSelectedColor(),
            recentColors = recentColorStore.snapshot(),
            onChosen = { selected ->
                drawColor = Color(selected.red, selected.green, selected.blue, drawColor.alpha)
                currentTool = FloatBarToolMode.DRAW
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

    fun isGridEnabled(): Boolean = gridEnabled

    fun setGridEnabled(enabled: Boolean) {
        gridEnabled = enabled
        repaint()
    }

    fun toggleGrid() {
        gridEnabled = !gridEnabled
        repaint()
    }

    private fun currentStrokes(): MutableList<StrokePath> = strokeWorkspace.currentStrokes()

    override fun paintComponent(graphics: Graphics) {
        super.paintComponent(graphics)
        canvasPainter.paint(graphics)
    }
}
