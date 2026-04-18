package com.floatbar

import com.intellij.openapi.editor.Editor
import java.awt.Point
import java.awt.Rectangle
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.math.max
import kotlin.math.min

object DrawingViewportTools {
    fun computeStrokeLineBounds(stroke: StrokePath): StrokeLineBounds? {
        if (stroke.points.isEmpty()) return null

        var minLine = Int.MAX_VALUE
        var maxLine = Int.MIN_VALUE

        for (point in stroke.points) {
            minLine = min(minLine, point.line)
            maxLine = max(maxLine, point.line)
        }

        return if (minLine == Int.MAX_VALUE) null else StrokeLineBounds(minLine, maxLine)
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
            val clamped = coordinateMapper.clampPointToDrawableArea(point) ?: continue
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
}
