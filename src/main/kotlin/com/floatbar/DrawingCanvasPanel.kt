package com.floatbar

import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
import javax.swing.JDialog
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.math.abs
import kotlin.math.roundToInt

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

    init {
        isOpaque = false
        preferredSize = Dimension(10, 10)

        val inputController = DrawingInputController(
            currentToolProvider = { currentTool },
            clampPoint = coordinateMapper::clampPointToDrawableArea,
            onFillPressed = ::handleFillPressed,
            onErasePressed = ::handleErasePressed,
            onEraseDragged = ::handleEraseDragged,
            onEraseReleased = ::handleEraseReleased,
            onShapePressed = ::handleShapePressed,
            onShapeDragged = ::handleShapeDragged,
            onShapeReleased = ::handleShapeReleased,
            onDrawPressed = ::handleDrawPressed,
            onDrawDragged = ::handleDrawDragged,
            onDrawReleased = ::handleDrawReleased
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
        val document = editor?.document ?: return
        saveStateForUndo()
        strokeWorkspace.clearDocument(document)
        currentStroke = null
        shapePreview = null
        documentSync.persistCurrentStrokes()
        refreshHistoryState()
        repaint()
    }

    fun undo() {
        val document = editor?.document ?: return
        val restored = historyStore.restoreUndo(document, currentStrokes()) ?: return
        strokeWorkspace.setStrokes(document, restored.toMutableList())
        strokeWorkspace.rebuildStrokeBounds(document)
        strokeWorkspace.resetStrokeGeometryCache(document)
        documentSync.persistCurrentStrokes()
        refreshHistoryState()
        repaint()
    }

    fun redo() {
        val document = editor?.document ?: return
        val restored = historyStore.restoreRedo(document, currentStrokes()) ?: return
        strokeWorkspace.setStrokes(document, restored.toMutableList())
        strokeWorkspace.rebuildStrokeBounds(document)
        strokeWorkspace.resetStrokeGeometryCache(document)
        documentSync.persistCurrentStrokes()
        refreshHistoryState()
        repaint()
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

    private fun saveStateForUndo() {
        val document = editor?.document ?: return
        historyStore.saveStateForUndo(document, currentStrokes())
        refreshHistoryState()
    }

    private fun refreshHistoryState() {
        onHistoryChanged(canUndo(), canRedo())
    }

    internal fun handleFillPressed(safePoint: Point) {
        saveStateForUndo()
        val filledStrokes = PaintGeometryEngine.fillAt(
            strokes = currentStrokes(),
            seedPoint = safePoint,
            fillColor = drawColor,
            panelBounds = Rectangle(
                -canvasPadding,
                -canvasPadding,
                width + canvasPadding * 2,
                height + canvasPadding * 2
            ),
            toViewPoint = coordinateMapper::toViewPoint
        )
        if (filledStrokes.isNotEmpty()) {
            for (stroke in filledStrokes.map { convertViewStrokeToAnchors(it) }) {
                strokeWorkspace.addStroke(stroke)
            }
            documentSync.schedulePersistCurrentStrokes()
            refreshHistoryState()
            repaint()
        }
    }

    internal fun handleErasePressed(safePoint: Point) {
        saveStateForUndo()
        applyErasePath(listOf(safePoint))
        DrawingViewportTools.repaintAround(this, listOf(safePoint), dirtyPaddingPx + eraseRadius.roundToInt())
    }

    internal fun handleEraseDragged(previous: Point?, safePoint: Point) {
        if (previous == null) {
            applyErasePath(listOf(safePoint))
            DrawingViewportTools.repaintAround(this, listOf(safePoint), dirtyPaddingPx + eraseRadius.roundToInt())
            return
        }

        if (previous.distance(safePoint) < eraseMinMovePx) {
            return
        }

        val samples = strokePathTools.buildEraseSamples(previous, safePoint)
        applyErasePath(samples)
        DrawingViewportTools.repaintAround(this, samples, dirtyPaddingPx + eraseRadius.roundToInt())
    }

    internal fun handleEraseReleased() {
        documentSync.schedulePersistCurrentStrokes()
        refreshHistoryState()
        repaint()
    }

    internal fun handleShapePressed(@Suppress("UNUSED_PARAMETER") safePoint: Point) {
        saveStateForUndo()
        shapePreview = null
    }

    internal fun handleShapeDragged(start: Point, safePoint: Point, isShiftDown: Boolean) {
        val oldPreviewPoints = shapePreview?.points?.mapNotNull(coordinateMapper::toViewPoint).orEmpty()
        shapePreview = buildShapeStroke(start, safePoint, selectedShapeKind, isShiftDown)
        val newPreviewPoints = shapePreview?.points?.mapNotNull(coordinateMapper::toViewPoint).orEmpty()
        DrawingViewportTools.repaintAround(this, oldPreviewPoints + newPreviewPoints + listOf(start, safePoint), dirtyPaddingPx)
    }

    internal fun handleShapeReleased() {
        val preview = shapePreview
        if (preview != null && preview.points.size >= 2) {
            val committed = preview.deepCopy()
            strokeWorkspace.addStroke(committed)
            documentSync.schedulePersistCurrentStrokes()
            refreshHistoryState()
        }
        shapePreview = null
        repaint()
    }

    internal fun handleDrawPressed(safePoint: Point) {
        saveStateForUndo()
        val stroke = StrokePath(color = drawColor, width = 3.5f)
        currentStroke = stroke
        strokeWorkspace.addStroke(stroke)
        addAnchorPoint(stroke, safePoint)
        DrawingViewportTools.repaintAround(this, listOf(safePoint), dirtyPaddingPx)
    }

    internal fun handleDrawDragged(previous: Point?, safePoint: Point) {
        val stroke = currentStroke ?: return
        if (previous == null) {
            addAnchorPoint(stroke, safePoint)
            DrawingViewportTools.repaintAround(this, listOf(safePoint), dirtyPaddingPx)
        } else {
            val samples = strokePathTools.buildDrawSamples(previous, safePoint)
            for (p in samples) {
                addAnchorPoint(stroke, p)
            }
            DrawingViewportTools.repaintAround(this, samples + listOf(previous, safePoint), dirtyPaddingPx)
        }
    }

    internal fun handleDrawReleased() {
        currentStroke?.let { stroke ->
            if (strokePathTools.simplifyFreehandStrokeInPlace(stroke)) {
                strokeWorkspace.updateStrokeBounds(stroke)
                strokeWorkspace.invalidateStrokeGeometry(stroke)
            }
        }
        currentStroke = null
        documentSync.schedulePersistCurrentStrokes()
        refreshHistoryState()
    }

    private fun applyErasePath(points: List<Point>) {
        if (points.isEmpty()) return
        val document = editor?.document ?: return

        val allStrokes = currentStrokes()
        if (allStrokes.isEmpty()) return

        val currentEditor = editor ?: return
        val candidateRange = DrawingViewportTools.computeEraseCandidateLineRange(this, currentEditor, coordinateMapper, points)
        val boundsMap = strokeWorkspace.currentStrokeBounds()
        val geometryMap = strokeWorkspace.currentStrokeGeometries()

        val candidates = mutableListOf<StrokePath>()

        for (stroke in allStrokes) {
            val bounds = boundsMap[stroke.id]
                ?: DrawingViewportTools.computeStrokeLineBounds(stroke)?.also { boundsMap[stroke.id] = it }
            if (bounds == null) {
                continue
            }

            val intersectsLineRange =
                bounds.maxLine >= candidateRange.first && bounds.minLine <= candidateRange.second

            if (intersectsLineRange) {
                candidates += stroke
            }
        }

        if (candidates.isEmpty()) return

        val rebuiltByStroke = PaintGeometryEngine.eraseAlongPathByStroke(
            strokes = candidates,
            localPoints = points,
            radius = eraseRadius,
            toViewPoint = coordinateMapper::toViewPoint
        )

        if (rebuiltByStroke.isEmpty()) return

        var replacementCount = 0
        for (replacements in rebuiltByStroke.values) {
            replacementCount += replacements.size
        }

        for (stroke in candidates) {
            boundsMap.remove(stroke.id)
            geometryMap.remove(stroke.id)
        }

        val merged = ArrayList<StrokePath>(allStrokes.size - candidates.size + replacementCount)
        for (stroke in allStrokes) {
            val replacements = rebuiltByStroke[stroke.id]
            if (replacements == null) {
                merged += stroke
                continue
            }

            for (replacement in replacements) {
                merged += replacement
                DrawingViewportTools.computeStrokeLineBounds(replacement)?.let { boundsMap[replacement.id] = it }
                geometryMap.remove(replacement.id)
            }
        }

        strokeWorkspace.setStrokes(document, merged)
    }

    private fun addAnchorPoint(stroke: StrokePath, point: Point) {
        val anchor = coordinateMapper.viewPointToAnchor(point) ?: return
        val last = stroke.points.lastOrNull()
        if (last != null &&
            last.line == anchor.line &&
            last.column == anchor.column &&
            abs(last.dx - anchor.dx) < 2 &&
            abs(last.dy - anchor.dy) < 2
        ) {
            return
        }
        stroke.points += anchor
        strokeWorkspace.expandStrokeBoundsWithAnchor(stroke, anchor)
        strokeWorkspace.invalidateStrokeGeometry(stroke)
    }

    private fun convertViewStrokeToAnchors(stroke: StrokePath): StrokePath {
        val converted = stroke.points.mapNotNull { source ->
            val view = Point(source.dx, source.dy)
            coordinateMapper.viewPointToAnchor(view)
        }.toMutableList()

        return StrokePath(
            color = stroke.color,
            width = stroke.width,
            points = converted,
            filled = stroke.filled,
            kind = stroke.kind
        )
    }

    private fun buildShapeStroke(start: Point, end: Point, kind: ShapeKind, constrain: Boolean): StrokePath {
        return ShapeStrokeFactory.buildShapeStroke(
            start = start,
            end = end,
            kind = kind,
            constrain = constrain,
            color = drawColor,
            width = 3.5f,
            shapeEdgeSpacing = shapeEdgeSpacing,
            ellipseSegments = ellipseSegments,
            toAnchor = coordinateMapper::viewPointToAnchor
        )
    }

    override fun paintComponent(graphics: Graphics) {
        super.paintComponent(graphics)
        val currentEditor = editor ?: return
        val g = graphics as Graphics2D
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        val clip = g.clipBounds ?: Rectangle(0, 0, width, height)
        val lineHeight = currentEditor.lineHeight.takeIf { it > 0 } ?: 16

        if (gridEnabled) {
            strokeRenderer.paintGridWithEdge(
                g = g,
                cellSize = lineHeight,
                clip = clip,
                width = width,
                height = height
            )
        }

        val visibleLineRange = DrawingViewportTools.resolveVisibleLineRange(this, currentEditor, clip)
        val boundsMap = strokeWorkspace.currentStrokeBounds()

        val contentOrigin = SwingUtilities.convertPoint(currentEditor.contentComponent, Point(0, 0), this)
        val contentClip = Rectangle(
            clip.x - contentOrigin.x,
            clip.y - contentOrigin.y,
            clip.width,
            clip.height
        )
        val gContent = g.create() as Graphics2D
        gContent.translate(contentOrigin.x.toDouble(), contentOrigin.y.toDouble())

        for (stroke in currentStrokes()) {
            val bounds = boundsMap[stroke.id]
                ?: DrawingViewportTools.computeStrokeLineBounds(stroke)?.also { boundsMap[stroke.id] = it }
            if (bounds != null && bounds.maxLine >= visibleLineRange.first && bounds.minLine <= visibleLineRange.second) {
                paintStroke(gContent, stroke, visibleContentClip = contentClip)
            }
        }

        shapePreview?.let {
            val previewBounds = DrawingViewportTools.computeStrokeLineBounds(it)
            if (previewBounds != null &&
                previewBounds.maxLine >= visibleLineRange.first &&
                previewBounds.minLine <= visibleLineRange.second
            ) {
                paintStroke(gContent, it, preview = true, visibleContentClip = contentClip)
            }
        }

        gContent.dispose()
    }

    private fun paintStroke(
        g: Graphics2D,
        stroke: StrokePath,
        preview: Boolean = false,
        visibleContentClip: Rectangle? = null
    ) {
        val geometry = if (preview) {
            strokeWorkspace.buildStrokeGeometryContent(stroke)
        } else {
            strokeWorkspace.getOrBuildStrokeGeometryContent(stroke)
        } ?: return

        strokeRenderer.paintStroke(
            g = g,
            stroke = stroke,
            geometry = geometry,
            preview = preview,
            visibleContentClip = visibleContentClip
        )
    }
}
