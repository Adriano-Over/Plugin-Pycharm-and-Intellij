package com.floatbar

import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
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
import javax.swing.Timer
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
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

    private var documentListener: DocumentListener? = null
    private var persistenceTimer: Timer? = null

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
        persistCurrentStrokes()
        unbindDocumentListener()
        this.editor = editor
        this.currentFile = FileDocumentManager.getInstance().getFile(editor.document)
        currentStroke = null
        shapePreview = null
        loadPersistedStrokes()
        bindDocumentListener(editor.document)
        refreshHistoryState()
        repaint()
    }

    fun unbindEditor() {
        persistCurrentStrokes()
        unbindDocumentListener()
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
        strokeStore.clearDocument(document)
        currentStroke = null
        shapePreview = null
        persistCurrentStrokes()
        refreshHistoryState()
        repaint()
    }

    fun undo() {
        val document = editor?.document ?: return
        val restored = historyStore.restoreUndo(document, currentStrokes()) ?: return
        strokeStore.setStrokes(document, restored.toMutableList())
        rebuildStrokeBounds(document)
        resetStrokeGeometryCache(document)
        persistCurrentStrokes()
        refreshHistoryState()
        repaint()
    }

    fun redo() {
        val document = editor?.document ?: return
        val restored = historyStore.restoreRedo(document, currentStrokes()) ?: return
        strokeStore.setStrokes(document, restored.toMutableList())
        rebuildStrokeBounds(document)
        resetStrokeGeometryCache(document)
        persistCurrentStrokes()
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

    private fun bindDocumentListener(document: Document) {
        val listener = object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                coordinateMapper.remapAnchorsForDocumentChange(document, event, strokeStore.currentStrokes(document))
                rebuildStrokeBounds(document)
                resetStrokeGeometryCache(document)
                schedulePersistCurrentStrokes()
                repaint()
            }
        }
        document.addDocumentListener(listener)
        documentListener = listener
    }

    private fun unbindDocumentListener() {
        val doc = editor?.document
        val listener = documentListener
        if (doc != null && listener != null) {
            doc.removeDocumentListener(listener)
        }
        documentListener = null
    }

    private fun currentStrokes(): MutableList<StrokePath> {
        val document = editor?.document ?: return mutableListOf()
        return strokeStore.currentStrokes(document)
    }

    private fun currentStrokeBounds(): MutableMap<Long, StrokeLineBounds> {
        val document = editor?.document ?: return mutableMapOf()
        return strokeStore.currentStrokeBounds(document)
    }

    private fun currentStrokeGeometries(): MutableMap<Long, StrokeGeometryContent> {
        val document = editor?.document ?: return mutableMapOf()
        return strokeStore.currentStrokeGeometries(document)
    }

    private fun saveStateForUndo() {
        val document = editor?.document ?: return
        historyStore.saveStateForUndo(document, currentStrokes())
        refreshHistoryState()
    }

    private fun refreshHistoryState() {
        onHistoryChanged(canUndo(), canRedo())
    }

    private fun addStrokeToCurrentDocument(stroke: StrokePath) {
        currentStrokes().add(stroke)
        updateStrokeBounds(stroke)
        invalidateStrokeGeometry(stroke)
    }

    private fun rebuildStrokeBounds(document: Document) {
        val strokes = strokeStore.currentStrokes(document)
        val rebuilt = mutableMapOf<Long, StrokeLineBounds>()
        for (stroke in strokes) {
            computeStrokeLineBounds(stroke)?.let { rebuilt[stroke.id] = it }
        }
        strokeStore.currentStrokeBounds(document).apply {
            clear()
            putAll(rebuilt)
        }
    }

    private fun computeStrokeLineBounds(stroke: StrokePath): StrokeLineBounds? {
        if (stroke.points.isEmpty()) return null

        var minLine = Int.MAX_VALUE
        var maxLine = Int.MIN_VALUE

        for (point in stroke.points) {
            minLine = min(minLine, point.line)
            maxLine = max(maxLine, point.line)
        }

        return if (minLine == Int.MAX_VALUE) null else StrokeLineBounds(minLine, maxLine)
    }

    private fun updateStrokeBounds(stroke: StrokePath) {
        val bounds = computeStrokeLineBounds(stroke) ?: return
        currentStrokeBounds()[stroke.id] = bounds
    }

    private fun expandStrokeBoundsWithAnchor(stroke: StrokePath, anchor: AnchorPoint) {
        val boundsMap = currentStrokeBounds()
        val existing = boundsMap[stroke.id]
        boundsMap[stroke.id] = if (existing == null) {
            StrokeLineBounds(anchor.line, anchor.line)
        } else {
            StrokeLineBounds(
                min(existing.minLine, anchor.line),
                max(existing.maxLine, anchor.line)
            )
        }
    }

    private fun invalidateStrokeGeometry(stroke: StrokePath) {
        currentStrokeGeometries().remove(stroke.id)
    }

    private fun resetStrokeGeometryCache(document: Document) {
        strokeStore.currentStrokeGeometries(document).clear()
    }

    private fun schedulePersistCurrentStrokes() {
        val filePath = currentFile?.path ?: return
        val timer = persistenceTimer ?: Timer(persistenceDebounceMs) {
            persistCurrentStrokes()
        }.also {
            it.isRepeats = false
            persistenceTimer = it
        }

        if (filePath.isEmpty()) return
        timer.restart()
    }

    private fun loadPersistedStrokes() {
        val editor = editor ?: return
        val document = editor.document
        val filePath = currentFile?.path ?: return
        strokeStore.loadPersistedStrokes(filePath, document) { doc, anchor ->
            coordinateMapper.normalizeAnchor(doc, anchor)
        }
        rebuildStrokeBounds(document)
        resetStrokeGeometryCache(document)
    }

    private fun persistCurrentStrokes() {
        persistenceTimer?.stop()
        strokeStore.persistStrokes(currentFile?.path, currentStrokes())
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
                addStrokeToCurrentDocument(stroke)
            }
            schedulePersistCurrentStrokes()
            refreshHistoryState()
            repaint()
        }
    }

    internal fun handleErasePressed(safePoint: Point) {
        saveStateForUndo()
        applyErasePath(listOf(safePoint))
        repaintAround(listOf(safePoint), dirtyPaddingPx + eraseRadius.roundToInt())
    }

    internal fun handleEraseDragged(previous: Point?, safePoint: Point) {
        if (previous == null) {
            applyErasePath(listOf(safePoint))
            repaintAround(listOf(safePoint), dirtyPaddingPx + eraseRadius.roundToInt())
            return
        }

        if (previous.distance(safePoint) < eraseMinMovePx) {
            return
        }

        val samples = buildEraseSamples(previous, safePoint)
        applyErasePath(samples)
        repaintAround(samples, dirtyPaddingPx + eraseRadius.roundToInt())
    }

    internal fun handleEraseReleased() {
        schedulePersistCurrentStrokes()
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
        repaintAround(oldPreviewPoints + newPreviewPoints + listOf(start, safePoint))
    }

    internal fun handleShapeReleased() {
        val preview = shapePreview
        if (preview != null && preview.points.size >= 2) {
            val committed = preview.deepCopy()
            addStrokeToCurrentDocument(committed)
            schedulePersistCurrentStrokes()
            refreshHistoryState()
        }
        shapePreview = null
        repaint()
    }

    internal fun handleDrawPressed(safePoint: Point) {
        saveStateForUndo()
        val stroke = StrokePath(color = drawColor, width = 3.5f)
        currentStroke = stroke
        addStrokeToCurrentDocument(stroke)
        addAnchorPoint(stroke, safePoint)
        repaintAround(listOf(safePoint))
    }

    internal fun handleDrawDragged(previous: Point?, safePoint: Point) {
        val stroke = currentStroke ?: return
        if (previous == null) {
            addAnchorPoint(stroke, safePoint)
            repaintAround(listOf(safePoint))
        } else {
            val samples = buildDrawSamples(previous, safePoint)
            for (p in samples) {
                addAnchorPoint(stroke, p)
            }
            repaintAround(samples + listOf(previous, safePoint))
        }
    }

    internal fun handleDrawReleased() {
        currentStroke?.let { simplifyFreehandStrokeInPlace(it) }
        currentStroke = null
        schedulePersistCurrentStrokes()
        refreshHistoryState()
    }

    private fun applyErasePath(points: List<Point>) {
        if (points.isEmpty()) return
        val document = editor?.document ?: return

        val allStrokes = currentStrokes()
        if (allStrokes.isEmpty()) return

        val candidateRange = computeEraseCandidateLineRange(points)
        val boundsMap = currentStrokeBounds()
        val geometryMap = currentStrokeGeometries()

        val candidates = mutableListOf<StrokePath>()

        for (stroke in allStrokes) {
            val bounds = boundsMap[stroke.id] ?: computeStrokeLineBounds(stroke)?.also { boundsMap[stroke.id] = it }
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
                computeStrokeLineBounds(replacement)?.let { boundsMap[replacement.id] = it }
                geometryMap.remove(replacement.id)
            }
        }

        strokeStore.setStrokes(document, merged)
    }

    private fun computeEraseCandidateLineRange(points: List<Point>): Pair<Int, Int> {
        val editor = editor ?: return 0 to Int.MAX_VALUE
        val document = editor.document
        if (document.lineCount <= 0 || points.isEmpty()) return 0 to 0

        var minLine = Int.MAX_VALUE
        var maxLine = Int.MIN_VALUE

        for (point in points) {
            val clamped = coordinateMapper.clampPointToDrawableArea(point) ?: continue
            val editorPoint = SwingUtilities.convertPoint(this, clamped, editor.contentComponent)
            val lineInfo = coordinateMapper.resolveLineInfo(editorPoint) ?: continue
            minLine = min(minLine, lineInfo.line)
            maxLine = max(maxLine, lineInfo.line)
        }

        if (minLine == Int.MAX_VALUE) return 0 to (document.lineCount - 1)

        val linePadding = 2
        return (minLine - linePadding).coerceAtLeast(0) to
            (maxLine + linePadding).coerceAtMost(document.lineCount - 1)
    }

    private fun buildEraseSamples(from: Point, to: Point): List<Point> {
        val spacing = max(6.0, eraseRadius * 1.35)
        val distance = from.distance(to)
        val steps = max(1, ceil(distance / spacing).toInt())
        val points = ArrayList<Point>(steps + 1)
        for (i in 0..steps) {
            val t = i.toDouble() / steps
            points += Point(
                (from.x + (to.x - from.x) * t).roundToInt(),
                (from.y + (to.y - from.y) * t).roundToInt()
            )
        }
        return points
    }

    private fun buildDrawSamples(from: Point, to: Point): List<Point> {
        val distance = from.distance(to)
        val steps = max(1, ceil(distance / 2.5).toInt())
        val points = ArrayList<Point>(steps)
        for (i in 1..steps) {
            val t = i.toDouble() / steps
            points += Point(
                (from.x + (to.x - from.x) * t).roundToInt(),
                (from.y + (to.y - from.y) * t).roundToInt()
            )
        }
        return points
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
        expandStrokeBoundsWithAnchor(stroke, anchor)
        invalidateStrokeGeometry(stroke)
    }

    private fun simplifyFreehandStrokeInPlace(stroke: StrokePath) {
        if (stroke.kind != null) return
        if (stroke.filled) return
        if (stroke.points.size < freehandSimplifyMinPoints) return

        val simplified = simplifyFreehandAnchors(stroke.points, freehandSimplifyTolerancePx)
        if (simplified.size >= 2 && simplified.size < stroke.points.size) {
            stroke.points.clear()
            stroke.points.addAll(simplified)
            updateStrokeBounds(stroke)
            invalidateStrokeGeometry(stroke)
        }
    }

    private fun simplifyFreehandAnchors(
        points: List<AnchorPoint>,
        tolerancePx: Double
    ): MutableList<AnchorPoint> {
        if (points.size < 3) {
            return points.map { it.copy() }.toMutableList()
        }

        val resolved = ArrayList<Pair<AnchorPoint, Point>>(points.size)
        for (anchor in points) {
            val view = coordinateMapper.toViewPoint(anchor) ?: return points.map { it.copy() }.toMutableList()
            resolved += anchor.copy() to view
        }

        val keep = BooleanArray(resolved.size)
        keep[0] = true
        keep[resolved.lastIndex] = true

        simplifySegmentRdp(resolved, 0, resolved.lastIndex, tolerancePx, keep)

        val result = mutableListOf<AnchorPoint>()
        for (i in resolved.indices) {
            if (keep[i]) result += resolved[i].first
        }

        return if (result.size >= 2) result else points.map { it.copy() }.toMutableList()
    }

    private fun simplifySegmentRdp(
        points: List<Pair<AnchorPoint, Point>>,
        start: Int,
        end: Int,
        tolerancePx: Double,
        keep: BooleanArray
    ) {
        if (end <= start + 1) return

        val a = points[start].second
        val b = points[end].second

        var maxDistance = -1.0
        var maxIndex = -1

        for (i in start + 1 until end) {
            val p = points[i].second
            val distance = perpendicularDistance(p, a, b)
            if (distance > maxDistance) {
                maxDistance = distance
                maxIndex = i
            }
        }

        if (maxIndex >= 0 && maxDistance > tolerancePx) {
            keep[maxIndex] = true
            simplifySegmentRdp(points, start, maxIndex, tolerancePx, keep)
            simplifySegmentRdp(points, maxIndex, end, tolerancePx, keep)
        }
    }

    private fun perpendicularDistance(p: Point, a: Point, b: Point): Double {
        val dx = (b.x - a.x).toDouble()
        val dy = (b.y - a.y).toDouble()

        if (dx == 0.0 && dy == 0.0) return p.distance(a)

        val t = (((p.x - a.x) * dx) + ((p.y - a.y) * dy)) / (dx * dx + dy * dy)
        val clamped = t.coerceIn(0.0, 1.0)
        val projX = a.x + clamped * dx
        val projY = a.y + clamped * dy
        return hypot(p.x - projX, p.y - projY)
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

    private fun buildStrokeGeometryContent(stroke: StrokePath): StrokeGeometryContent? {
        return strokeRenderer.buildStrokeGeometryContent(
            stroke = stroke,
            toContentPoint = coordinateMapper::toContentPoint
        )
    }

    private fun getOrBuildStrokeGeometryContent(stroke: StrokePath): StrokeGeometryContent? {
        val cache = currentStrokeGeometries()
        val cached = cache[stroke.id]
        if (cached != null) return cached

        val built = buildStrokeGeometryContent(stroke) ?: return null
        cache[stroke.id] = built
        return built
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

    private fun repaintAround(points: List<Point>, padding: Int = dirtyPaddingPx) {
        if (points.isEmpty()) {
            repaint()
            return
        }

        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var maxY = Int.MIN_VALUE

        for (p in points) {
            minX = min(minX, p.x)
            minY = min(minY, p.y)
            maxX = max(maxX, p.x)
            maxY = max(maxY, p.y)
        }

        if (minX == Int.MAX_VALUE) {
            repaint()
            return
        }

        val x = (minX - padding).coerceAtLeast(0)
        val y = (minY - padding).coerceAtLeast(0)
        val w = (maxX - minX + padding * 2).coerceAtLeast(1)
        val h = (maxY - minY + padding * 2).coerceAtLeast(1)

        repaint(x, y, w, h)
    }

    override fun paintComponent(graphics: Graphics) {
        super.paintComponent(graphics)
        val editor = editor ?: return
        val g = graphics as Graphics2D
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        val clip = g.clipBounds ?: Rectangle(0, 0, width, height)
        val lineHeight = editor.lineHeight.takeIf { it > 0 } ?: 16

        if (gridEnabled) {
            paintGridWithEdge(g, lineHeight, clip)
        }

        val visibleLineRange = resolveVisibleLineRange(clip)
        val boundsMap = currentStrokeBounds()

        val contentOrigin = SwingUtilities.convertPoint(editor.contentComponent, Point(0, 0), this)
        val contentClip = Rectangle(
            clip.x - contentOrigin.x,
            clip.y - contentOrigin.y,
            clip.width,
            clip.height
        )
        val gContent = g.create() as Graphics2D
        gContent.translate(contentOrigin.x.toDouble(), contentOrigin.y.toDouble())

        for (stroke in currentStrokes()) {
            val bounds = boundsMap[stroke.id] ?: computeStrokeLineBounds(stroke)?.also { boundsMap[stroke.id] = it }
            if (bounds != null && bounds.maxLine >= visibleLineRange.first && bounds.minLine <= visibleLineRange.second) {
                paintStroke(gContent, stroke, visibleContentClip = contentClip)
            }
        }

        shapePreview?.let {
            val previewBounds = computeStrokeLineBounds(it)
            if (previewBounds != null &&
                previewBounds.maxLine >= visibleLineRange.first &&
                previewBounds.minLine <= visibleLineRange.second
            ) {
                paintStroke(gContent, it, preview = true, visibleContentClip = contentClip)
            }
        }

        gContent.dispose()
    }

    private fun resolveVisibleLineRange(clip: Rectangle): Pair<Int, Int> {
        val editor = editor ?: return 0 to Int.MAX_VALUE
        val document = editor.document
        if (document.lineCount <= 0) return 0 to 0

        val topEditorPoint = SwingUtilities.convertPoint(this, Point(clip.x, clip.y), editor.contentComponent)
        val bottomEditorPoint = SwingUtilities.convertPoint(
            this,
            Point(clip.x + clip.width, clip.y + clip.height),
            editor.contentComponent
        )

        val topLine = editor.xyToLogicalPosition(topEditorPoint).line.coerceIn(0, document.lineCount - 1)
        val bottomLine = editor.xyToLogicalPosition(bottomEditorPoint).line.coerceIn(0, document.lineCount - 1)

        return (topLine - 2).coerceAtLeast(0) to (bottomLine + 2).coerceAtMost(document.lineCount - 1)
    }

    private fun paintGridWithEdge(g: Graphics2D, cellSize: Int, clip: Rectangle) {
        strokeRenderer.paintGridWithEdge(
            g = g,
            cellSize = cellSize,
            clip = clip,
            width = width,
            height = height
        )
    }

    private fun paintStroke(
        g: Graphics2D,
        stroke: StrokePath,
        preview: Boolean = false,
        visibleContentClip: Rectangle? = null
    ) {
        val geometry = if (preview) {
            buildStrokeGeometryContent(stroke)
        } else {
            getOrBuildStrokeGeometryContent(stroke)
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
