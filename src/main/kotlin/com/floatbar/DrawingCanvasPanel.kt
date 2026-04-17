package com.floatbar

import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.Path2D
import javax.swing.JDialog
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

class DrawingCanvasPanel(
    private val project: Project,
    private val recentColorStore: RecentColorStore,
    private val onColorApplied: () -> Unit = {},
    private val onHistoryChanged: (Boolean, Boolean) -> Unit = { _, _ -> }
) : JPanel() {

    private var editor: Editor? = null
    private var currentFile: VirtualFile? = null

    private val strokesByDocument = mutableMapOf<Document, MutableList<StrokePath>>()
    private val undoByDocument = mutableMapOf<Document, MutableList<List<StrokePath>>>()
    private val redoByDocument = mutableMapOf<Document, MutableList<List<StrokePath>>>()

    private var documentListener: DocumentListener? = null

    private var currentStroke: StrokePath? = null
    private var currentTool = FloatBarToolMode.DRAW
    private var selectedShapeKind: ShapeKind = ShapeKind.RECTANGLE
    private var shapeStartPoint: Point? = null
    private var shapePreview: StrokePath? = null
    private var lastDragPoint: Point? = null

    private var drawColor = Color(255, 0, 0, 210)
    private var gridEnabled = true
    private val eraseRadius = 9.0

    private val canvasPadding = 10
    private val gridExtendLeftPx = 8
    private val minCodeClearancePx = 8
    private val dirtyPaddingPx = 28
    private val eraseMinMovePx = 2.0

    init {
        isOpaque = false
        preferredSize = Dimension(10, 10)

        val mouseHandler = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                when (currentTool) {
                    FloatBarToolMode.FILL -> {
                        val safePoint = clampPointToDrawableArea(e.point) ?: return
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
                            toViewPoint = ::toViewPoint
                        )
                        if (filledStrokes.isNotEmpty()) {
                            currentStrokes().addAll(
                                filledStrokes.map { convertViewStrokeToAnchors(it) }
                            )
                            persistCurrentStrokes()
                            refreshHistoryState()
                            repaint()
                        }
                        lastDragPoint = null
                    }

                    FloatBarToolMode.ERASE -> {
                        val safePoint = clampPointToDrawableArea(e.point) ?: return
                        saveStateForUndo()
                        applyErasePath(listOf(safePoint))
                        lastDragPoint = safePoint
                        repaintAround(listOf(safePoint), dirtyPaddingPx + eraseRadius.roundToInt())
                    }

                    FloatBarToolMode.SHAPES -> {
                        val safePoint = clampPointToDrawableArea(e.point) ?: return
                        saveStateForUndo()
                        shapeStartPoint = safePoint
                        shapePreview = null
                        lastDragPoint = safePoint
                    }

                    else -> {
                        val safePoint = clampPointToDrawableArea(e.point) ?: return
                        saveStateForUndo()
                        val stroke = StrokePath(color = drawColor, width = 3.5f)
                        currentStroke = stroke
                        currentStrokes().add(stroke)
                        addAnchorPoint(stroke, safePoint)
                        lastDragPoint = safePoint
                        repaintAround(listOf(safePoint))
                    }
                }
            }

            override fun mouseDragged(e: MouseEvent) {
                when (currentTool) {
                    FloatBarToolMode.FILL -> return

                    FloatBarToolMode.ERASE -> {
                        val safePoint = clampPointToDrawableArea(e.point) ?: return
                        val previous = lastDragPoint

                        if (previous == null) {
                            applyErasePath(listOf(safePoint))
                            lastDragPoint = safePoint
                            repaintAround(listOf(safePoint), dirtyPaddingPx + eraseRadius.roundToInt())
                            return
                        }

                        if (previous.distance(safePoint) < eraseMinMovePx) {
                            return
                        }

                        val samples = buildEraseSamples(previous, safePoint)
                        applyErasePath(samples)
                        lastDragPoint = safePoint
                        repaintAround(samples, dirtyPaddingPx + eraseRadius.roundToInt())
                    }

                    FloatBarToolMode.SHAPES -> {
                        val start = shapeStartPoint ?: return
                        val safePoint = clampPointToDrawableArea(e.point) ?: return
                        val oldPreviewPoints = shapePreview?.points?.mapNotNull(::toViewPoint).orEmpty()
                        shapePreview = buildShapeStroke(start, safePoint, selectedShapeKind, e.isShiftDown)
                        val newPreviewPoints = shapePreview?.points?.mapNotNull(::toViewPoint).orEmpty()
                        lastDragPoint = safePoint
                        repaintAround(oldPreviewPoints + newPreviewPoints + listOf(start, safePoint))
                    }

                    else -> {
                        val stroke = currentStroke ?: return
                        val safePoint = clampPointToDrawableArea(e.point) ?: return
                        val previous = lastDragPoint
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
                        lastDragPoint = safePoint
                    }
                }
            }

            override fun mouseReleased(e: MouseEvent) {
                when (currentTool) {
                    FloatBarToolMode.ERASE -> {
                        lastDragPoint = null
                        persistCurrentStrokes()
                        refreshHistoryState()
                        repaint()
                    }

                    FloatBarToolMode.SHAPES -> {
                        val preview = shapePreview
                        if (preview != null && preview.points.size >= 2) {
                            currentStrokes().add(preview.deepCopy())
                            persistCurrentStrokes()
                            refreshHistoryState()
                        }
                        shapeStartPoint = null
                        shapePreview = null
                        lastDragPoint = null
                        repaint()
                    }

                    FloatBarToolMode.FILL -> {
                        lastDragPoint = null
                    }

                    else -> {
                        currentStroke = null
                        lastDragPoint = null
                        persistCurrentStrokes()
                        refreshHistoryState()
                    }
                }
            }
        }

        addMouseListener(mouseHandler)
        addMouseMotionListener(mouseHandler)
    }

    fun bindEditor(editor: Editor) {
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
        unbindDocumentListener()
        editor = null
        currentFile = null
        currentStroke = null
        shapePreview = null
        lastDragPoint = null
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
        strokesByDocument[document] = mutableListOf()
        currentStroke = null
        shapePreview = null
        persistCurrentStrokes()
        refreshHistoryState()
        repaint()
    }

    fun undo() {
        val document = editor?.document ?: return
        val undo = undoByDocument.getOrPut(document) { mutableListOf() }
        if (undo.isEmpty()) return
        val redo = redoByDocument.getOrPut(document) { mutableListOf() }
        redo += snapshotCurrentStrokes()
        val restored = undo.removeAt(undo.lastIndex)
        strokesByDocument[document] = restored.map { it.deepCopy() }.toMutableList()
        persistCurrentStrokes()
        refreshHistoryState()
        repaint()
    }

    fun redo() {
        val document = editor?.document ?: return
        val redo = redoByDocument.getOrPut(document) { mutableListOf() }
        if (redo.isEmpty()) return
        val undo = undoByDocument.getOrPut(document) { mutableListOf() }
        undo += snapshotCurrentStrokes()
        val restored = redo.removeAt(redo.lastIndex)
        strokesByDocument[document] = restored.map { it.deepCopy() }.toMutableList()
        persistCurrentStrokes()
        refreshHistoryState()
        repaint()
    }

    fun canUndo(): Boolean = editor?.document?.let { undoByDocument[it]?.isNotEmpty() == true } == true
    fun canRedo(): Boolean = editor?.document?.let { redoByDocument[it]?.isNotEmpty() == true } == true

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
                remapAnchorsForDocumentChange(document, event)
                persistCurrentStrokes()
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
        return strokesByDocument.getOrPut(document) { mutableListOf() }
    }

    private fun snapshotCurrentStrokes(): List<StrokePath> = currentStrokes().map { it.deepCopy() }

    private fun saveStateForUndo() {
        val document = editor?.document ?: return
        val undo = undoByDocument.getOrPut(document) { mutableListOf() }
        undo += snapshotCurrentStrokes()
        if (undo.size > 50) undo.removeAt(0)
        redoByDocument.getOrPut(document) { mutableListOf() }.clear()
        refreshHistoryState()
    }

    private fun refreshHistoryState() {
        onHistoryChanged(canUndo(), canRedo())
    }

    private fun loadPersistedStrokes() {
        val editor = editor ?: return
        val document = editor.document
        val filePath = currentFile?.path ?: return
        val stateService = project.service<FloatBarDrawingStateService>()
        val loaded = stateService.getStrokes(filePath).map { saved ->
            StrokePath(
                color = Color(saved.color, true),
                width = saved.width,
                points = saved.points.map { point ->
                    val hasOffset = point.offset > 0 || point.line > 0 || point.column > 0
                    val anchor = if (hasOffset) {
                        AnchorPoint(
                            line = point.line,
                            column = point.column,
                            dx = point.dx,
                            dy = point.dy,
                            offset = point.offset
                        )
                    } else {
                        AnchorPoint(
                            line = point.line,
                            column = 0,
                            dx = point.x,
                            dy = point.dy,
                            offset = 0
                        )
                    }
                    normalizeAnchor(document, anchor)
                    anchor
                }.toMutableList(),
                filled = saved.filled,
                kind = saved.kind?.let { runCatching { ShapeKind.valueOf(it) }.getOrNull() }
            )
        }.toMutableList()
        strokesByDocument[document] = loaded
    }

    private fun persistCurrentStrokes() {
        val filePath = currentFile?.path ?: return
        val document = editor?.document ?: return
        currentStrokes().forEach { stroke ->
            stroke.points.forEach { point -> normalizeAnchor(document, point) }
        }
        val stateService = project.service<FloatBarDrawingStateService>()
        val saved = currentStrokes().map { stroke ->
            SavedStroke(
                color = stroke.color.rgb,
                width = stroke.width,
                points = stroke.points.map { point ->
                    SavedPoint(
                        line = point.line,
                        column = point.column,
                        dx = point.dx,
                        dy = point.dy,
                        offset = point.offset,
                        x = 0
                    )
                }.toMutableList(),
                filled = stroke.filled,
                kind = stroke.kind?.name
            )
        }
        stateService.setStrokes(filePath, saved)
    }

    private fun applyErasePath(points: List<Point>) {
        if (points.isEmpty()) return
        val document = editor?.document ?: return
        val erased = PaintGeometryEngine.eraseAlongPath(
            strokes = currentStrokes(),
            localPoints = points,
            radius = eraseRadius,
            toViewPoint = ::toViewPoint
        )
        strokesByDocument[document] = erased.toMutableList()
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

    private fun addInterpolatedPoints(stroke: StrokePath, from: Point, to: Point) {
        val samples = buildDrawSamples(from, to)
        for (p in samples) {
            addAnchorPoint(stroke, p)
        }
    }

    private fun addAnchorPoint(stroke: StrokePath, point: Point) {
        val anchor = viewPointToAnchor(point) ?: return
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
    }

    private data class LineInfo(
        val line: Int,
        val lineEndColumn: Int,
        val lineEndOffset: Int,
        val lineBaseX: Int,
        val lineBaseY: Int,
        val lineEndX: Int
    )

    private fun resolveLineInfo(editorPoint: Point): LineInfo? {
        val editor = editor ?: return null
        val document = editor.document
        if (document.lineCount <= 0) return null

        val logicalAtPoint = editor.xyToLogicalPosition(editorPoint)
        val safeLine = logicalAtPoint.line.coerceIn(0, document.lineCount - 1)
        val lineStartOffset = document.getLineStartOffset(safeLine)
        val lineEndOffset = document.getLineEndOffset(safeLine)
        val lineEndLogical = editor.offsetToLogicalPosition(lineEndOffset)
        val lineBase = editor.logicalPositionToXY(LogicalPosition(safeLine, 0))
        val lineEndPoint = editor.logicalPositionToXY(lineEndLogical)

        return LineInfo(
            line = safeLine,
            lineEndColumn = lineEndOffset - lineStartOffset,
            lineEndOffset = lineEndOffset,
            lineBaseX = lineBase.x,
            lineBaseY = lineBase.y,
            lineEndX = max(lineBase.x, lineEndPoint.x)
        )
    }

    private fun clampPointToDrawableArea(point: Point): Point? {
        val editor = editor ?: return null
        val editorPoint = SwingUtilities.convertPoint(this, point, editor.contentComponent)
        val lineInfo = resolveLineInfo(editorPoint) ?: return null
        val clampedEditorPoint = Point(
            max(editorPoint.x, lineInfo.lineEndX + minCodeClearancePx),
            editorPoint.y
        )
        return SwingUtilities.convertPoint(editor.contentComponent, clampedEditorPoint, this)
    }

    private fun viewPointToAnchor(point: Point): AnchorPoint? {
        val editor = editor ?: return null
        val safePoint = clampPointToDrawableArea(point) ?: return null
        val editorPoint = SwingUtilities.convertPoint(this, safePoint, editor.contentComponent)
        val lineInfo = resolveLineInfo(editorPoint) ?: return null

        return AnchorPoint(
            line = lineInfo.line,
            column = lineInfo.lineEndColumn,
            dx = editorPoint.x - lineInfo.lineEndX,
            dy = editorPoint.y - lineInfo.lineBaseY,
            offset = lineInfo.lineEndOffset
        )
    }

    private fun toViewPoint(anchor: AnchorPoint): Point? {
        val editor = editor ?: return null
        val document = editor.document
        normalizeAnchor(document, anchor)

        val safeLine = anchor.line.coerceIn(0, document.lineCount.coerceAtLeast(1) - 1)
        val lineEndOffset = document.getLineEndOffset(safeLine)
        val lineEndLogical = editor.offsetToLogicalPosition(lineEndOffset)
        val lineBase = editor.logicalPositionToXY(LogicalPosition(safeLine, 0))
        val lineEndPoint = editor.logicalPositionToXY(lineEndLogical)

        val editorPoint = Point(
            max(lineEndPoint.x, lineBase.x) + max(anchor.dx, minCodeClearancePx),
            lineBase.y + anchor.dy
        )

        return SwingUtilities.convertPoint(editor.contentComponent, editorPoint, this)
    }

    private fun convertViewStrokeToAnchors(stroke: StrokePath): StrokePath {
        val converted = stroke.points.mapNotNull { source ->
            val view = Point(source.dx, source.dy)
            viewPointToAnchor(view)
        }.toMutableList()

        return StrokePath(
            color = stroke.color,
            width = stroke.width,
            points = converted,
            filled = stroke.filled,
            kind = stroke.kind
        )
    }

    private fun remapAnchorsForDocumentChange(document: Document, event: DocumentEvent) {
        val strokes = strokesByDocument[document] ?: return
        val editStart = event.offset
        val replacedEnd = event.offset + event.oldLength
        val insertedLength = event.newLength
        val delta = insertedLength - event.oldLength

        for (stroke in strokes) {
            for (point in stroke.points) {
                point.offset = remapOffset(point.offset, editStart, replacedEnd, insertedLength, delta)
                syncAnchorFromOffset(document, point)
            }
        }
    }

    private fun remapOffset(
        offset: Int,
        editStart: Int,
        replacedEnd: Int,
        insertedLength: Int,
        delta: Int
    ): Int {
        return when {
            replacedEnd == editStart && offset >= editStart -> offset + insertedLength
            offset > replacedEnd -> offset + delta
            offset >= editStart -> editStart + insertedLength
            else -> offset
        }.coerceAtLeast(0)
    }

    private fun normalizeAnchor(document: Document, anchor: AnchorPoint) {
        val maxOffset = document.textLength.coerceAtLeast(0)

        if (anchor.offset <= 0 && (anchor.line > 0 || anchor.column > 0)) {
            val safeLine = anchor.line.coerceIn(0, document.lineCount.coerceAtLeast(1) - 1)
            val lineEnd = document.getLineEndOffset(safeLine)
            anchor.offset = lineEnd.coerceIn(0, maxOffset)
        }

        anchor.offset = anchor.offset.coerceIn(0, maxOffset)
        syncAnchorFromOffset(document, anchor)
        anchor.dx = max(anchor.dx, minCodeClearancePx)
    }

    private fun syncAnchorFromOffset(document: Document, anchor: AnchorPoint) {
        val clampedOffset = anchor.offset.coerceIn(0, document.textLength.coerceAtLeast(0))
        anchor.offset = clampedOffset
        val line = document.getLineNumber(clampedOffset)
        val lineStart = document.getLineStartOffset(line)
        val lineEnd = document.getLineEndOffset(line)
        anchor.line = line
        anchor.column = lineEnd - lineStart
    }

    private fun buildShapeStroke(start: Point, end: Point, kind: ShapeKind, constrain: Boolean): StrokePath {
        val adjusted = if (constrain) constrainPoint(start, end, kind) else end
        val viewPoints = when (kind) {
            ShapeKind.RECTANGLE, ShapeKind.PROCESS -> rectanglePoints(start, adjusted)
            ShapeKind.ELLIPSE, ShapeKind.CONNECTOR -> ellipsePoints(start, adjusted)
            ShapeKind.LINE -> linePoints(start, adjusted)
            ShapeKind.ARROW -> arrowPoints(start, adjusted)
            ShapeKind.DECISION -> diamondPoints(start, adjusted)
            ShapeKind.START_END -> roundedRectApproxPoints(start, adjusted)
            ShapeKind.INPUT_OUTPUT -> parallelogramPoints(start, adjusted)
            ShapeKind.DOCUMENT -> documentPoints(start, adjusted)
        }

        val anchors = viewPoints.mapNotNull { viewPointToAnchor(it) }.toMutableList()
        return StrokePath(
            color = drawColor,
            width = 3.5f,
            points = anchors,
            filled = false,
            kind = kind
        )
    }

    private fun constrainPoint(start: Point, end: Point, kind: ShapeKind): Point {
        return when (kind) {
            ShapeKind.LINE, ShapeKind.ARROW -> {
                val dx = end.x - start.x
                val dy = end.y - start.y
                val angle = Math.atan2(dy.toDouble(), dx.toDouble())
                val step = PI / 4.0
                val snapped = kotlin.math.round(angle / step) * step
                val length = hypot(dx.toDouble(), dy.toDouble())

                Point(
                    (start.x + cos(snapped) * length).roundToInt(),
                    (start.y + sin(snapped) * length).roundToInt()
                )
            }

            ShapeKind.RECTANGLE,
            ShapeKind.ELLIPSE,
            ShapeKind.PROCESS,
            ShapeKind.CONNECTOR,
            ShapeKind.DECISION,
            ShapeKind.START_END -> {
                val size = min(abs(end.x - start.x), abs(end.y - start.y))
                Point(
                    start.x + if (end.x >= start.x) size else -size,
                    start.y + if (end.y >= start.y) size else -size
                )
            }

            else -> end
        }
    }

    private fun rectanglePoints(a: Point, b: Point): List<Point> {
        val left = min(a.x, b.x)
        val right = max(a.x, b.x)
        val top = min(a.y, b.y)
        val bottom = max(a.y, b.y)
        return polyline(
            Point(left, top),
            Point(right, top),
            Point(right, bottom),
            Point(left, bottom),
            Point(left, top)
        )
    }

    private fun diamondPoints(a: Point, b: Point): List<Point> {
        val left = min(a.x, b.x)
        val right = max(a.x, b.x)
        val top = min(a.y, b.y)
        val bottom = max(a.y, b.y)
        val cx = (left + right) / 2
        val cy = (top + bottom) / 2
        return polyline(
            Point(cx, top),
            Point(right, cy),
            Point(cx, bottom),
            Point(left, cy),
            Point(cx, top)
        )
    }

    private fun parallelogramPoints(a: Point, b: Point): List<Point> {
        val left = min(a.x, b.x)
        val right = max(a.x, b.x)
        val top = min(a.y, b.y)
        val bottom = max(a.y, b.y)
        val slant = max(10, (right - left) / 6)
        return polyline(
            Point(left + slant, top),
            Point(right, top),
            Point(right - slant, bottom),
            Point(left, bottom),
            Point(left + slant, top)
        )
    }

    private fun documentPoints(a: Point, b: Point): List<Point> {
        val left = min(a.x, b.x)
        val right = max(a.x, b.x)
        val top = min(a.y, b.y)
        val bottom = max(a.y, b.y)
        val wave = max(8, (bottom - top) / 7)
        return polyline(
            Point(left, top),
            Point(right, top),
            Point(right, bottom - wave),
            Point((left + right * 2) / 3, bottom),
            Point((left * 2 + right) / 3, bottom - wave / 2),
            Point(left, bottom - wave),
            Point(left, top)
        )
    }

    private fun roundedRectApproxPoints(a: Point, b: Point): List<Point> {
        val left = min(a.x, b.x)
        val right = max(a.x, b.x)
        val top = min(a.y, b.y)
        val bottom = max(a.y, b.y)
        val r = max(8, min((right - left) / 4, (bottom - top) / 2))
        return polyline(
            Point(left + r, top),
            Point(right - r, top),
            Point(right, top + r),
            Point(right, bottom - r),
            Point(right - r, bottom),
            Point(left + r, bottom),
            Point(left, bottom - r),
            Point(left, top + r),
            Point(left + r, top)
        )
    }

    private fun ellipsePoints(a: Point, b: Point): List<Point> {
        val left = min(a.x, b.x)
        val right = max(a.x, b.x)
        val top = min(a.y, b.y)
        val bottom = max(a.y, b.y)
        val cx = (left + right) / 2.0
        val cy = (top + bottom) / 2.0
        val rx = max(1.0, (right - left) / 2.0)
        val ry = max(1.0, (bottom - top) / 2.0)

        return (0..72).map { i ->
            val t = (PI * 2.0) * i / 72.0
            Point(
                (cx + cos(t) * rx).roundToInt(),
                (cy + sin(t) * ry).roundToInt()
            )
        }
    }

    private fun linePoints(a: Point, b: Point): List<Point> = interpolateLine(a, b, 2.0)

    private fun arrowPoints(a: Point, b: Point): List<Point> {
        val dx = (b.x - a.x).toDouble()
        val dy = (b.y - a.y).toDouble()
        val length = max(1.0, hypot(dx, dy))
        val ux = dx / length
        val uy = dy / length
        val head = min(18.0, length / 3.0)
        val wingX = -uy
        val wingY = ux

        val tipLeft = Point(
            (b.x - ux * head + wingX * head * 0.6).roundToInt(),
            (b.y - uy * head + wingY * head * 0.6).roundToInt()
        )

        val tipRight = Point(
            (b.x - ux * head - wingX * head * 0.6).roundToInt(),
            (b.y - uy * head - wingY * head * 0.6).roundToInt()
        )

        val shaftEnd = Point(
            (b.x - ux * (head * 0.35)).roundToInt(),
            (b.y - uy * (head * 0.35)).roundToInt()
        )

        return polyline(
            *interpolateLine(a, shaftEnd, 2.0).toTypedArray(),
            *interpolateLine(tipLeft, b, 2.0).toTypedArray(),
            *interpolateLine(b, tipRight, 2.0).toTypedArray()
        )
    }

    private fun polyline(vararg points: Point): List<Point> {
        if (points.isEmpty()) return emptyList()
        val result = mutableListOf<Point>()
        for (i in 0 until points.lastIndex) {
            val segment = interpolateLine(points[i], points[i + 1], 2.0)
            if (result.isNotEmpty() && segment.isNotEmpty()) {
                result.removeAt(result.lastIndex)
            }
            result += segment
        }
        return result
    }

    private fun interpolateLine(a: Point, b: Point, spacing: Double): List<Point> {
        val distance = a.distance(b)
        val steps = max(1, ceil(distance / spacing).toInt())

        return (0..steps).map { i ->
            val t = i.toDouble() / steps
            Point(
                (a.x + (b.x - a.x) * t).roundToInt(),
                (a.y + (b.y - a.y) * t).roundToInt()
            )
        }
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

        val lineHeight = editor.lineHeight.takeIf { it > 0 } ?: 16

        if (gridEnabled) {
            paintGridWithEdge(g, lineHeight)
        }

        for (stroke in currentStrokes()) {
            paintStroke(g, stroke)
        }

        shapePreview?.let { paintStroke(g, it, preview = true) }
    }

    private fun paintGridWithEdge(g: Graphics2D, cellSize: Int) {
        val startX = -gridExtendLeftPx
        val major = Color(255, 255, 255, 20)
        val minor = Color(255, 255, 255, 9)

        var col = 0
        var x = startX
        while (x <= width + canvasPadding) {
            g.color = if (col % 2 == 0) major else minor
            g.drawLine(x, -canvasPadding, x, height + canvasPadding)
            col++
            x += cellSize
        }

        var row = 0
        var y = -canvasPadding
        while (y <= height + canvasPadding) {
            g.color = if (row % 2 == 0) major else minor
            g.drawLine(startX, y, width + canvasPadding, y)
            row++
            y += cellSize
        }

        g.color = major
        g.drawLine(0, 0, width - 1, 0)
        g.drawLine(0, height - 1, width - 1, height - 1)
        g.drawLine(0, 0, 0, height - 1)
        g.drawLine(width - 1, 0, width - 1, height - 1)
    }

    private fun paintStroke(g: Graphics2D, stroke: StrokePath, preview: Boolean = false) {
        val alphaColor = if (preview) {
            Color(stroke.color.red, stroke.color.green, stroke.color.blue, 140)
        } else {
            stroke.color
        }

        g.color = alphaColor

        val points = stroke.points.mapNotNull(::toViewPoint)
        if (points.size < 2) return

        if (stroke.filled) {
            val polygon = PaintGeometryEngine.buildPolygon(stroke, ::toViewPoint) ?: return
            g.fillPolygon(polygon)
            g.color = Color(alphaColor.red, alphaColor.green, alphaColor.blue, 220)
            g.stroke = BasicStroke(
                max(1.5f, stroke.width / 2f),
                BasicStroke.CAP_ROUND,
                BasicStroke.JOIN_ROUND
            )
            g.drawPolygon(polygon)
            return
        }

        g.stroke = BasicStroke(
            stroke.width,
            BasicStroke.CAP_ROUND,
            BasicStroke.JOIN_ROUND
        )

        val path = if (stroke.kind == null) {
            buildSmoothFreehandPath(points)
        } else {
            buildPolylinePath(points)
        }

        g.draw(path)
    }

    private fun buildPolylinePath(points: List<Point>): Path2D.Float {
        val path = Path2D.Float()
        path.moveTo(points.first().x.toDouble(), points.first().y.toDouble())
        for (point in points.drop(1)) {
            path.lineTo(point.x.toDouble(), point.y.toDouble())
        }
        return path
    }

    private fun buildSmoothFreehandPath(points: List<Point>): Path2D.Float {
        val path = Path2D.Float()
        if (points.isEmpty()) return path
        if (points.size == 1) {
            path.moveTo(points[0].x.toDouble(), points[0].y.toDouble())
            path.lineTo(points[0].x.toDouble(), points[0].y.toDouble())
            return path
        }
        if (points.size == 2) {
            path.moveTo(points[0].x.toDouble(), points[0].y.toDouble())
            path.lineTo(points[1].x.toDouble(), points[1].y.toDouble())
            return path
        }

        path.moveTo(points[0].x.toDouble(), points[0].y.toDouble())

        for (i in 1 until points.lastIndex) {
            val current = points[i]
            val next = points[i + 1]
            val midX = (current.x + next.x) / 2.0
            val midY = (current.y + next.y) / 2.0
            path.quadTo(
                current.x.toDouble(),
                current.y.toDouble(),
                midX,
                midY
            )
        }

        val penultimate = points[points.lastIndex - 1]
        val last = points.last()
        path.quadTo(
            penultimate.x.toDouble(),
            penultimate.y.toDouble(),
            last.x.toDouble(),
            last.y.toDouble()
        )

        return path
    }
}