package com.floatbar

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics2D
import java.awt.Point
import java.awt.Polygon
import java.awt.Rectangle
import java.awt.geom.Path2D
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

class DrawingStrokeRenderer(
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
                buildSmoothFreehandPath(contentPoints)
            } else {
                buildPolylinePath(contentPoints)
            }
            StrokeGeometryContent(
                path = path,
                polygon = null,
                bounds = computeGeometryBounds(contentPoints, boundsPadding)
            )
        }
    }

    fun paintStroke(
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

data class StrokeGeometryContent(
    val path: Path2D.Float?,
    val polygon: Polygon?,
    val bounds: Rectangle
)
