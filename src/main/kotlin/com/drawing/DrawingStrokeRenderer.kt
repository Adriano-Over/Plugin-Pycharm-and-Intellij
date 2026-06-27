package com.drawing

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics2D
import java.awt.Point
import java.awt.Polygon
import java.awt.Rectangle
import java.awt.geom.Path2D
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

open class DrawingStrokeRenderer(
    private val canvasPadding: Int,
    private val gridExtendLeftPx: Int
) {
    fun paintGridWithEdge(
        g: Graphics2D,
        cellSize: Int,
        clip: Rectangle,
        width: Int,
        height: Int
    ) {
        val startX = -gridExtendLeftPx
        val major = Color(255, 255, 255, 20)
        val minor = Color(255, 255, 255, 9)

        val minGridX = (((clip.x - startX) / cellSize) - 1) * cellSize + startX
        val maxGridX = clip.x + clip.width + cellSize
        val minGridY = ((clip.y / cellSize) - 1) * cellSize
        val maxGridY = clip.y + clip.height + cellSize

        var colIndex = (minGridX - startX) / cellSize
        var x = minGridX
        while (x <= maxGridX) {
            g.color = if (colIndex % 2 == 0) major else minor
            g.drawLine(x, clip.y - canvasPadding, x, clip.y + clip.height + canvasPadding)
            colIndex++
            x += cellSize
        }

        var rowIndex = minGridY / cellSize
        var y = minGridY
        while (y <= maxGridY) {
            g.color = if (rowIndex % 2 == 0) major else minor
            g.drawLine(clip.x - canvasPadding, y, clip.x + clip.width + canvasPadding, y)
            rowIndex++
            y += cellSize
        }

        g.color = major
        if (clip.y <= 0) g.drawLine(0, 0, width - 1, 0)
        if (clip.y + clip.height >= height - 1) g.drawLine(0, height - 1, width - 1, height - 1)
        if (clip.x <= 0) g.drawLine(0, 0, 0, height - 1)
        if (clip.x + clip.width >= width - 1) g.drawLine(width - 1, 0, width - 1, height - 1)
    }

    fun buildStrokeGeometryContent(
        stroke: StrokePath,
        toContentPoint: (AnchorPoint) -> Point?
    ): StrokeGeometryContent? {
        val contentPoints = stroke.points.mapNotNull(toContentPoint)
        if (contentPoints.size != stroke.points.size) {
            DrawingDiagnosticLog.warn("RENDERER", "mappedPointDrop ${DrawingDiagnosticLog.strokeSummary(stroke)} mapped=${contentPoints.size}")
        }
        DrawingDiagnosticLog.sample("rendererBuild-${stroke.id}", 700, "RENDERER") {
            "rendererBuild ${DrawingDiagnosticLog.strokeSummary(stroke)} ${DrawingDiagnosticLog.pointSummary(contentPoints)}"
        }
        if (contentPoints.isEmpty()) return null

        val boundsPadding = max(4, ceil(stroke.width.toDouble() / 2.0).toInt() + 2)

        return if (stroke.filled) {
            if (contentPoints.size < 3) return null
            val polygon = Polygon()
            contentPoints.forEach { polygon.addPoint(it.x, it.y) }
            val bounds = Rectangle(polygon.bounds)
            bounds.grow(boundsPadding, boundsPadding)
            StrokeGeometryContent(path = null, polygon = polygon, bounds = bounds)
        } else {
            if (contentPoints.size < 2) return null
            val path = if (stroke.kind == null) {
                buildCornerPreservingFreehandPath(contentPoints)
            } else {
                buildShapePath(contentPoints, stroke.kind)
            }
            StrokeGeometryContent(
                path = path,
                polygon = null,
                bounds = computeGeometryBounds(contentPoints, boundsPadding)
            )
        }
    }

    open fun paintStroke(
        g: Graphics2D,
        stroke: StrokePath,
        geometry: StrokeGeometryContent,
        preview: Boolean = false,
        visibleContentClip: Rectangle? = null
    ) {
        val alphaColor = if (preview) {
            Color(stroke.color.red, stroke.color.green, stroke.color.blue, 140)
        } else {
            stroke.color
        }

        if (visibleContentClip != null && !geometry.bounds.intersects(visibleContentClip)) {
            DrawingDiagnosticLog.sample("paintSkipClip-${stroke.id}", 700, "RENDERER") {
                "paintSkipClip ${DrawingDiagnosticLog.strokeSummary(stroke)} ${DrawingDiagnosticLog.geometrySummary(geometry)} clip=${DrawingDiagnosticLog.rectSummary(visibleContentClip)}"
            }
            return
        }

        g.color = alphaColor

        if (stroke.filled) {
            val polygon = geometry.polygon ?: return
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

        g.stroke = if (preview && stroke.kind != null) {
            BasicStroke(
                stroke.width,
                BasicStroke.CAP_ROUND,
                BasicStroke.JOIN_ROUND,
                10f,
                floatArrayOf(10f, 8f),
                0f
            )
        } else {
            BasicStroke(
                stroke.width,
                BasicStroke.CAP_ROUND,
                BasicStroke.JOIN_ROUND
            )
        }

        geometry.path?.let { g.draw(it) }
    }

    private fun buildShapePath(points: List<Point>, kind: ShapeKind?): Path2D.Float {
        return when (kind) {
            ShapeKind.PREDEFINED_PROCESS -> buildPredefinedProcessPath(rawBounds(points))
            ShapeKind.DOCUMENT -> buildDocumentPath(rawBounds(points))
            ShapeKind.MULTIPLE_DOCUMENTS -> buildMultipleDocumentsPath(rawBounds(points))
            ShapeKind.STORED_DATA -> buildStoredDataPath(rawBounds(points))
            else -> buildPolylinePath(points, kind)
        }
    }

    private fun rawBounds(points: List<Point>): Rectangle {
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

        return if (minX == Int.MAX_VALUE) {
            Rectangle()
        } else {
            Rectangle(minX, minY, (maxX - minX).coerceAtLeast(1), (maxY - minY).coerceAtLeast(1))
        }
    }

    private fun buildPredefinedProcessPath(bounds: Rectangle): Path2D.Float {
        val path = Path2D.Float()
        val radius = min(14.0, max(5.0, min(bounds.width, bounds.height) / 16.0))
        appendRoundedRect(path, bounds.x.toDouble(), bounds.y.toDouble(), bounds.maxX, bounds.maxY, radius)

        val barInset = max(radius + 5.0, bounds.width / 10.0).coerceAtMost(bounds.width / 3.0)
        val top = bounds.y + radius / 2.0
        val bottom = bounds.maxY - radius / 2.0
        val leftBarX = bounds.x + barInset
        val rightBarX = bounds.maxX - barInset

        path.moveTo(leftBarX, top)
        path.lineTo(leftBarX, bottom)
        path.moveTo(rightBarX, top)
        path.lineTo(rightBarX, bottom)

        return path
    }

    private fun buildDocumentPath(bounds: Rectangle): Path2D.Float {
        return Path2D.Float().also { path ->
            appendDocument(path, bounds.x.toDouble(), bounds.y.toDouble(), bounds.maxX, bounds.maxY)
        }
    }

    private fun buildMultipleDocumentsPath(bounds: Rectangle): Path2D.Float {
        val path = Path2D.Float()
        val offset = max(6.0, min(bounds.width, bounds.height) / 10.0)
            .coerceAtMost(max(1.0, min(bounds.width, bounds.height) / 4.0))

        appendDocument(
            path,
            bounds.x.toDouble(),
            bounds.y.toDouble(),
            bounds.maxX - offset * 2.0,
            bounds.maxY - offset * 2.0
        )
        appendDocument(
            path,
            bounds.x + offset,
            bounds.y + offset,
            bounds.maxX - offset,
            bounds.maxY - offset
        )
        appendDocument(
            path,
            bounds.x + offset * 2.0,
            bounds.y + offset * 2.0,
            bounds.maxX,
            bounds.maxY
        )

        return path
    }

    private fun buildStoredDataPath(bounds: Rectangle): Path2D.Float {
        val path = Path2D.Float()
        val curve = max(10.0, bounds.width / 6.0)
        val left = bounds.x.toDouble()
        val right = bounds.maxX
        val top = bounds.y.toDouble()
        val bottom = bounds.maxY
        val height = bounds.height.toDouble()

        path.moveTo(left + curve, top)
        path.lineTo(right, top)
        path.curveTo(
            right - curve,
            top + height / 3.0,
            right - curve,
            bottom - height / 3.0,
            right,
            bottom
        )
        path.lineTo(left + curve, bottom)
        path.curveTo(
            left - curve / 2.0,
            bottom - height / 3.0,
            left - curve / 2.0,
            top + height / 3.0,
            left + curve,
            top
        )
        path.closePath()

        return path
    }

    private fun appendRoundedRect(path: Path2D.Float, left: Double, top: Double, right: Double, bottom: Double, radius: Double) {
        val safeRadius = min(radius, min((right - left) / 2.0, (bottom - top) / 2.0)).coerceAtLeast(1.0)

        path.moveTo(left + safeRadius, top)
        path.lineTo(right - safeRadius, top)
        path.quadTo(right, top, right, top + safeRadius)
        path.lineTo(right, bottom - safeRadius)
        path.quadTo(right, bottom, right - safeRadius, bottom)
        path.lineTo(left + safeRadius, bottom)
        path.quadTo(left, bottom, left, bottom - safeRadius)
        path.lineTo(left, top + safeRadius)
        path.quadTo(left, top, left + safeRadius, top)
        path.closePath()
    }

    private fun appendDocument(path: Path2D.Float, left: Double, top: Double, right: Double, bottom: Double) {
        if (right <= left || bottom <= top) return

        val width = right - left
        val height = bottom - top
        val wave = max(8.0, height / 6.0).coerceAtMost(height / 3.0)
        val waveTopY = bottom - wave
        val centerX = left + width / 2.0

        path.moveTo(left, top)
        path.lineTo(right, top)
        path.lineTo(right, waveTopY)
        path.curveTo(
            right - width / 4.0,
            bottom,
            centerX + width / 6.0,
            bottom,
            centerX,
            bottom
        )
        path.curveTo(
            centerX - width / 6.0,
            bottom,
            left + width / 4.0,
            waveTopY - wave / 2.0,
            left,
            waveTopY
        )
        path.closePath()
    }

    private fun computeGeometryBounds(points: List<Point>, padding: Int): Rectangle {
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
            return Rectangle()
        }

        return Rectangle(
            minX - padding,
            minY - padding,
            (maxX - minX + padding * 2).coerceAtLeast(1),
            (maxY - minY + padding * 2).coerceAtLeast(1)
        )
    }

    private fun buildPolylinePath(points: List<Point>, kind: ShapeKind?): Path2D.Float {
        val path = Path2D.Float()
        val closedOutline = kind?.isClosedOutline() == true
        val normalizedPoints = if (closedOutline && points.size >= 2 && points.first() == points.last()) {
            points.dropLast(1)
        } else {
            points
        }

        path.moveTo(normalizedPoints.first().x.toDouble(), normalizedPoints.first().y.toDouble())
        for (point in normalizedPoints.drop(1)) {
            path.lineTo(point.x.toDouble(), point.y.toDouble())
        }
        if (closedOutline) {
            path.closePath()
        }
        return path
    }

    private fun buildCornerPreservingFreehandPath(points: List<Point>): Path2D.Float {
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
            val previous = points[i - 1]
            val current = points[i]
            val next = points[i + 1]

            if (shouldPreserveFreehandCorner(previous, current, next)) {
                path.lineTo(current.x.toDouble(), current.y.toDouble())
                continue
            }

            val midX = (current.x + next.x) / 2.0
            val midY = (current.y + next.y) / 2.0
            path.quadTo(
                current.x.toDouble(),
                current.y.toDouble(),
                midX,
                midY
            )
        }

        val last = points.last()
        path.lineTo(last.x.toDouble(), last.y.toDouble())

        return path
    }

    private fun shouldPreserveFreehandCorner(previous: Point, current: Point, next: Point): Boolean {
        val aX = (current.x - previous.x).toDouble()
        val aY = (current.y - previous.y).toDouble()
        val bX = (next.x - current.x).toDouble()
        val bY = (next.y - current.y).toDouble()

        val aLength = hypot(aX, aY)
        val bLength = hypot(bX, bY)
        if (aLength < 1.0 || bLength < 1.0) return true

        val axisAlignedRun =
            (abs(aX) <= 1.5 && abs(bX) <= 1.5) ||
                (abs(aY) <= 1.5 && abs(bY) <= 1.5)
        if (axisAlignedRun) return true

        val cosine = ((aX * bX) + (aY * bY)) / (aLength * bLength)
        return cosine < 0.72
    }
}

data class StrokeGeometryContent(
    val path: Path2D.Float?,
    val polygon: Polygon?,
    val bounds: Rectangle,
    val foldLayoutSignature: Int = 0
)
