package com.floatbar

import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
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

    init {
        isOpaque = false
        preferredSize = Dimension(10, 10)

        val mouseHandler = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                when (currentTool) {
                    FloatBarToolMode.FILL -> {
                        saveStateForUndo()
                        val filled = PaintGeometryEngine.fillAt(
                            strokes = currentStrokes(),
                            seedPoint = e.point,
                            fillColor = drawColor,
                            panelBounds = Rectangle(
                                -canvasPadding,
                                -canvasPadding,
                                width + canvasPadding * 2,
                                height + canvasPadding * 2
                            ),
                            toViewPoint = ::toViewPoint
                        )
                        if (filled != null) {
                            currentStrokes().add(convertViewStrokeToAnchors(filled))
                            persistCurrentStrokes()
                            refreshHistoryState()
                            repaint()
                        }
                        lastDragPoint = null
                    }

                    FloatBarToolMode.ERASE -> {
                        saveStateForUndo()
                        applyEraseAt(e.point)
                        lastDragPoint = e.point
                    }

                    FloatBarToolMode.SHAPES -> {
                        saveStateForUndo()
                        shapeStartPoint = e.point
                        shapePreview = null
                        lastDragPoint = e.point
                    }

                    else -> {
                        saveStateForUndo()
                        val stroke = StrokePath(color = drawColor, width = 3.5f)
                        currentStroke = stroke
                        currentStrokes().add(stroke)
                        addAnchorPoint(stroke, e.point)
                        lastDragPoint = e.point
                        repaint()
                    }
                }
            }

            override fun mouseDragged(e: MouseEvent) {
                when (currentTool) {
                    FloatBarToolMode.FILL -> return

                    FloatBarToolMode.ERASE -> {
                        val previous = lastDragPoint
                        if (previous == null) {
                            applyEraseAt(e.point)
                        } else {
                            eraseInterpolated(previous, e.point)
                        }
                        lastDragPoint = e.point
                        repaint()
                    }

                    FloatBarToolMode.SHAPES -> {
                        val start = shapeStartPoint ?: return
                        shapePreview = buildShapeStroke(start, e.point, selectedShapeKind, e.isShiftDown)
                        repaint()
                    }

                    else -> {
                        val stroke = currentStroke ?: return
                        val previous = lastDragPoint
                        if (previous == null) {
                            addAnchorPoint(stroke, e.point)
                        } else {
                            addInterpolatedPoints(stroke, previous, e.point)
                        }
                        lastDragPoint = e.point
                        repaint()
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
                            // Normalize committed shapes into the same point-based drawing
                            // model used by pencil strokes so all tools interact consistently.
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

    fun canUndo(): Boolean =
        editor?.document?.let { undoByDocument[it]?.isNotEmpty() == true } == true

    fun canRedo(): Boolean =
        editor?.document?.let { redoByDocument[it]?.isNotEmpty() == true } == true

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
                val removedLines = event.oldFragment.count { it == '\n' }
                val addedLines = event.newFragment.count { it == '\n' }
                val delta = addedLines - removedLines

                if (delta < 0) {
                    val removeCount = -delta
                    val startLine = document.getLineNumber(event.offset)
                    val strokes = strokesByDocument[document] ?: return

                    for (stroke in strokes) {
                        for (point in stroke.points) {
                            if (point.line > startLine) {
                                point.line = max(startLine, point.line - removeCount)
                            }
                        }
                    }

                    persistCurrentStrokes()
                    repaint()
                }
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

    private fun snapshotCurrentStrokes(): List<StrokePath> =
        currentStrokes().map { it.deepCopy() }

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
        val filePath = currentFile?.path ?: return
        val stateService = project.service<FloatBarDrawingStateService>()

        val loaded = stateService.getStrokes(filePath).map { saved ->
            StrokePath(
                color = Color(saved.color, true),
                width = saved.width,
                points = saved.points.map { point ->
                    AnchorPoint(point.line, point.x, point.dy)
                }.toMutableList(),
                filled = saved.filled,
                kind = saved.kind?.let { runCatching { ShapeKind.valueOf(it) }.getOrNull() }
            )
        }.toMutableList()

        strokesByDocument[editor.document] = loaded
    }

    private fun persistCurrentStrokes() {
        val filePath = currentFile?.path ?: return
        val stateService = project.service<FloatBarDrawingStateService>()

        val saved = currentStrokes().map { stroke ->
            SavedStroke(
                color = stroke.color.rgb,
                width = stroke.width,
                points = stroke.points.map { point ->
                    SavedPoint(line = point.line, x = point.x, dy = point.dy)
                }.toMutableList(),
                filled = stroke.filled,
                kind = stroke.kind?.name
            )
        }

        stateService.setStrokes(filePath, saved)
    }

    private fun applyEraseAt(point: Point) {
        val document = editor?.document ?: return

        val erased = PaintGeometryEngine.eraseAt(
            strokes = currentStrokes(),
            localPoint = point,
            radius = eraseRadius,
            toViewPoint = ::toViewPoint
        )

        strokesByDocument[document] = erased.toMutableList()
    }

    private fun eraseInterpolated(from: Point, to: Point) {
        val distance = from.distance(to)
        val steps = max(1, ceil(distance / 6.0).toInt())

        for (i in 1..steps) {
            val t = i.toDouble() / steps
            val p = Point(
                (from.x + (to.x - from.x) * t).toInt(),
                (from.y + (to.y - from.y) * t).toInt()
            )
            applyEraseAt(p)
        }
    }

    private fun addInterpolatedPoints(stroke: StrokePath, from: Point, to: Point) {
        val distance = from.distance(to)
        val steps = max(1, ceil(distance / 2.0).toInt())

        for (i in 1..steps) {
            val t = i.toDouble() / steps
            val p = Point(
                (from.x + (to.x - from.x) * t).toInt(),
                (from.y + (to.y - from.y) * t).toInt()
            )
            addAnchorPoint(stroke, p)
        }
    }

    private fun addAnchorPoint(stroke: StrokePath, point: Point) {
        val anchor = viewPointToAnchor(point) ?: return
        val last = stroke.points.lastOrNull()

        if (last != null &&
            last.line == anchor.line &&
            abs(last.x - anchor.x) < 2 &&
            abs(last.dy - anchor.dy) < 2
        ) {
            return
        }

        stroke.points += anchor
    }

    private fun viewPointToAnchor(point: Point): AnchorPoint? {
        val editor = editor ?: return null
        val lineHeight = editor.lineHeight.takeIf { it > 0 } ?: 16

        val visibleLine = max(0, (point.y - canvasPadding) / lineHeight)
        val line = max(0, visibleLine)
        val dy = point.y - (canvasPadding + line * lineHeight)

        return AnchorPoint(
            line = line,
            x = point.x + canvasPadding,
            dy = dy
        )
    }

    private fun toViewPoint(anchor: AnchorPoint): Point? {
        val editor = editor ?: return null
        val lineHeight = editor.lineHeight.takeIf { it > 0 } ?: 16
        val line = anchor.line.coerceAtLeast(0)
        val y = canvasPadding + line * lineHeight + anchor.dy
        return Point(anchor.x - canvasPadding, y)
    }

    private fun convertViewStrokeToAnchors(stroke: StrokePath): StrokePath {
        val converted = stroke.points.mapNotNull { source ->
            val view = Point(source.x, source.dy)
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

    private fun buildShapeStroke(
        start: Point,
        end: Point,
        kind: ShapeKind,
        constrain: Boolean
    ): StrokePath {
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

    private fun linePoints(a: Point, b: Point): List<Point> =
        interpolateLine(a, b, 2.0)

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

        val path = Path2D.Float()
        path.moveTo(points.first().x.toDouble(), points.first().y.toDouble())
        for (point in points.drop(1)) {
            path.lineTo(point.x.toDouble(), point.y.toDouble())
        }
        g.draw(path)
    }
}
