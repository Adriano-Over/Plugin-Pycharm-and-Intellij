package com.drawing

import com.intellij.openapi.editor.Editor
import java.awt.Point
import java.awt.Rectangle
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

object DrawingViewportTools {
    private const val OBJECT_ANCHOR_LINE_ESTIMATE_PX = 8

    fun computeStrokeLineBounds(stroke: StrokePath): StrokeLineBounds? {
        if (stroke.points.isEmpty()) return null

        var minLine = Int.MAX_VALUE
        var maxLine = Int.MIN_VALUE

        for (point in stroke.points) {
            minLine = min(minLine, point.line)
            maxLine = max(maxLine, point.line)
        }

        if (minLine == Int.MAX_VALUE) return null

        if (stroke.usesRigidObjectAnchoring() && minLine == maxLine) {
            val minDy = stroke.points.minOf { it.dy }
            val maxDy = stroke.points.maxOf { it.dy }
            val topLineOffset = Math.floorDiv(minDy, OBJECT_ANCHOR_LINE_ESTIMATE_PX) - 1
            val bottomLineOffset = Math.ceil(maxDy.toDouble() / OBJECT_ANCHOR_LINE_ESTIMATE_PX.toDouble()).toInt() + 1
            minLine = min(minLine, minLine + topLineOffset)
            maxLine = max(maxLine, maxLine + bottomLineOffset)
        }

        return StrokeLineBounds(minLine.coerceAtLeast(0), maxLine.coerceAtLeast(0))
    }

    fun shouldUseRigidObjectAnchorForFreehand(
        stroke: StrokePath,
        toViewPoint: (AnchorPoint) -> Point?
    ): Boolean {
        if (stroke.rigidObjectAnchor || stroke.kind != null || stroke.filled || stroke.points.size < 2) return false

        val viewPoints = stroke.points.mapNotNull(toViewPoint)
        return viewPoints.size >= 2
    }

    fun computeEraseCandidateLineRange(
        canvas: JPanel,
        editor: Editor,
        coordinateMapper: DrawingCoordinateMapper,
        points: List<Point>,
        linePadding: Int = 2
    ): Pair<Int, Int> {
        val document = editor.document
        if (document.lineCount <= 0 || points.isEmpty()) return 0 to 0

        var minLine = Int.MAX_VALUE
        var maxLine = Int.MIN_VALUE

        for (point in points) {
            val clamped = coordinateMapper.clampPointToDrawableArea(point, allowCodeArea = true) ?: continue
            val editorPoint = SwingUtilities.convertPoint(canvas, clamped, editor.contentComponent)
            val lineInfo = coordinateMapper.resolveLineInfo(editorPoint) ?: continue
            minLine = min(minLine, lineInfo.line)
            maxLine = max(maxLine, lineInfo.line)
        }

        if (minLine == Int.MAX_VALUE) {
            return 0 to (document.lineCount - 1)
        }

        return (minLine - linePadding).coerceAtLeast(0) to
            (maxLine + linePadding).coerceAtMost(document.lineCount - 1)
    }

    fun repaintAround(
        canvas: JPanel,
        points: List<Point>,
        padding: Int
    ) {
        if (points.isEmpty()) {
            canvas.repaint()
            return
        }

        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var maxY = Int.MIN_VALUE

        for (point in points) {
            minX = min(minX, point.x)
            minY = min(minY, point.y)
            maxX = max(maxX, point.x)
            maxY = max(maxY, point.y)
        }

        if (minX == Int.MAX_VALUE) {
            canvas.repaint()
            return
        }

        val x = (minX - padding).coerceAtLeast(0)
        val y = (minY - padding).coerceAtLeast(0)
        val w = (maxX - minX + padding * 2).coerceAtLeast(1)
        val h = (maxY - minY + padding * 2).coerceAtLeast(1)

        canvas.repaint(x, y, w, h)
    }

    fun repaintRect(canvas: JPanel, rect: Rectangle?) {
        if (rect == null) {
            canvas.repaint()
            return
        }
        canvas.repaint(rect.x, rect.y, rect.width.coerceAtLeast(1), rect.height.coerceAtLeast(1))
    }

    fun computeStrokeViewBounds(
        stroke: StrokePath,
        toViewPoint: (AnchorPoint) -> Point?,
        extraPadding: Int = 0
    ): Rectangle? {
        val points = stroke.points.mapNotNull(toViewPoint)
        if (points.isEmpty()) return null

        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var maxY = Int.MIN_VALUE
        for (point in points) {
            minX = min(minX, point.x)
            minY = min(minY, point.y)
            maxX = max(maxX, point.x)
            maxY = max(maxY, point.y)
        }
        if (minX == Int.MAX_VALUE) return null

        val padding = extraPadding + ceil(stroke.width.toDouble() / 2.0).toInt() + 2
        return Rectangle(
            (minX - padding).coerceAtLeast(0),
            (minY - padding).coerceAtLeast(0),
            (maxX - minX + padding * 2).coerceAtLeast(1),
            (maxY - minY + padding * 2).coerceAtLeast(1)
        )
    }

    fun isStrokeHiddenByCollapsedFold(
        stroke: StrokePath,
        collapsedRegions: List<CollapsedFoldRegionSnapshot>
    ): Boolean {
        if (stroke.points.isEmpty() || collapsedRegions.isEmpty()) return false

        val strokeOffsets = stroke.points.map { it.offset }
        return collapsedRegions.any { region ->
            strokeOffsets.all { offset -> offset in region.startOffset..region.endOffset }
        }
    }

    fun isRasterFillHiddenByCollapsedFold(
        fill: RasterFillPath,
        collapsedRegions: List<CollapsedFoldRegionSnapshot>
    ): Boolean {
        if (collapsedRegions.isEmpty()) return false
        return collapsedRegions.any { region ->
            fill.anchor.offset in region.startOffset..region.endOffset
        }
    }

    fun collapsedFoldMarkersFor(
        strokes: Iterable<StrokePath>,
        collapsedRegions: List<CollapsedFoldRegionSnapshot>
    ): List<CollapsedFoldRegionSnapshot> {
        if (collapsedRegions.isEmpty()) return emptyList()
        val hiddenRegions = linkedSetOf<CollapsedFoldRegionSnapshot>()
        for (stroke in strokes) {
            if (!isStrokeHiddenByCollapsedFold(stroke, collapsedRegions)) continue
            collapsedRegions.firstOrNull { region ->
                stroke.points.map { it.offset }.all { offset -> offset in region.startOffset..region.endOffset }
            }?.let(hiddenRegions::add)
        }
        return hiddenRegions.toList()
    }

    fun computeStrokesViewBounds(
        strokes: Iterable<StrokePath>,
        toViewPoint: (AnchorPoint) -> Point?,
        extraPadding: Int = 0
    ): Rectangle? {
        var union: Rectangle? = null
        for (stroke in strokes) {
            val bounds = computeStrokeViewBounds(stroke, toViewPoint, extraPadding) ?: continue
            union = if (union == null) Rectangle(bounds) else union.apply { add(bounds) }
        }
        return union
    }

    fun unionRectangles(first: Rectangle?, second: Rectangle?): Rectangle? {
        return when {
            first == null -> second?.let { Rectangle(it) }
            second == null -> Rectangle(first)
            else -> Rectangle(first).apply { add(second) }
        }
    }

    fun resolveVisibleLineRange(
        canvas: JPanel,
        editor: Editor,
        clip: Rectangle,
        linePadding: Int = 2
    ): Pair<Int, Int> {
        val document = editor.document
        if (document.lineCount <= 0) return 0 to 0

        val topEditorPoint = SwingUtilities.convertPoint(canvas, Point(clip.x, clip.y), editor.contentComponent)
        val bottomEditorPoint = SwingUtilities.convertPoint(
            canvas,
            Point(clip.x + clip.width, clip.y + clip.height),
            editor.contentComponent
        )

        val topLine = editor.xyToLogicalPosition(topEditorPoint).line.coerceIn(0, document.lineCount - 1)
        val bottomLine = editor.xyToLogicalPosition(bottomEditorPoint).line.coerceIn(0, document.lineCount - 1)

        return (topLine - linePadding).coerceAtLeast(0) to
            (bottomLine + linePadding).coerceAtMost(document.lineCount - 1)
    }

    fun resolveVisibleLineRangeInContent(
        editor: Editor,
        clip: Rectangle,
        linePadding: Int = 2
    ): Pair<Int, Int> {
        val document = editor.document
        if (document.lineCount <= 0) return 0 to 0

        val topLeft = Point(clip.x, clip.y)
        val bottomRight = Point(clip.x + clip.width, clip.y + clip.height)
        val topLine = editor.xyToLogicalPosition(topLeft).line.coerceIn(0, document.lineCount - 1)
        val bottomLine = editor.xyToLogicalPosition(bottomRight).line.coerceIn(0, document.lineCount - 1)

        return (topLine - linePadding).coerceAtLeast(0) to
            (bottomLine + linePadding).coerceAtMost(document.lineCount - 1)
    }
}
