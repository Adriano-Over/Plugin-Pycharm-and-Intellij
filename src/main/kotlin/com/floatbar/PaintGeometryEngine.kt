package com.floatbar

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Point
import java.awt.Polygon
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.geom.Area
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.awt.geom.PathIterator
import java.awt.image.BufferedImage
import kotlin.math.hypot
import kotlin.math.max

object PaintGeometryEngine {

    fun eraseAt(
        strokes: List<StrokePath>,
        localPoint: Point,
        radius: Double,
        toViewPoint: (AnchorPoint) -> Point?
    ): MutableList<StrokePath> {
        val rebuilt = mutableListOf<StrokePath>()
        val eraserArea = Area(
            Ellipse2D.Double(
                localPoint.x - radius,
                localPoint.y - radius,
                radius * 2,
                radius * 2
            )
        )

        for (stroke in strokes) {
            if (stroke.points.isEmpty()) continue

            if (stroke.filled) {
                val area = buildArea(stroke, toViewPoint) ?: run {
                    rebuilt += stroke.deepCopy()
                    continue
                }
                area.subtract(eraserArea)
                rebuilt += areaToFilledStrokes(area, stroke.color, stroke.width)
                continue
            }

            rebuilt += cutStrokeByEraser(stroke, localPoint, radius, toViewPoint)
        }

        return rebuilt
    }

    fun fillAt(
        strokes: List<StrokePath>,
        seedPoint: Point,
        fillColor: Color,
        panelBounds: Rectangle,
        toViewPoint: (AnchorPoint) -> Point?
    ): MutableList<StrokePath> {
        if (panelBounds.width <= 2 || panelBounds.height <= 2) return mutableListOf()

        val offsetX = panelBounds.x
        val offsetY = panelBounds.y

        val image = BufferedImage(panelBounds.width, panelBounds.height, BufferedImage.TYPE_BYTE_BINARY)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF)
        g.color = Color.WHITE
        g.fillRect(0, 0, image.width, image.height)
        g.color = Color.BLACK

        for (stroke in strokes) {
            val points = stroke.points.mapNotNull(toViewPoint)
            if (points.isEmpty()) continue

            if (stroke.filled) {
                val polygon = buildPolygon(stroke, toViewPoint)
                if (polygon != null && polygon.npoints >= 3) {
                    val shifted = Polygon(
                        IntArray(polygon.npoints) { i -> polygon.xpoints[i] - offsetX },
                        IntArray(polygon.npoints) { i -> polygon.ypoints[i] - offsetY },
                        polygon.npoints
                    )
                    g.fillPolygon(shifted)
                }
                continue
            }

            if (points.size < 2) continue

            val shifted = points.map { Point(it.x - offsetX, it.y - offsetY) }
            g.stroke = BasicStroke(
                max(2f, stroke.width),
                BasicStroke.CAP_ROUND,
                BasicStroke.JOIN_ROUND
            )

            val path = Path2D.Float()
            path.moveTo(shifted.first().x.toDouble(), shifted.first().y.toDouble())
            for (point in shifted.drop(1)) {
                path.lineTo(point.x.toDouble(), point.y.toDouble())
            }
            g.draw(path)

            if (shifted.first().distance(shifted.last()) <= max(6.0, stroke.width * 1.8)) {
                g.drawLine(
                    shifted.last().x,
                    shifted.last().y,
                    shifted.first().x,
                    shifted.first().y
                )
            }
        }
        g.dispose()

        val seedX = seedPoint.x - offsetX
        val seedY = seedPoint.y - offsetY

        if (seedX !in 0 until image.width || seedY !in 0 until image.height) return mutableListOf()
        if (image.getRGB(seedX, seedY) != Color.WHITE.rgb) return mutableListOf()

        val visited = Array(image.height) { BooleanArray(image.width) }
        val queue = ArrayDeque<Point>()
        queue.add(Point(seedX, seedY))
        visited[seedY][seedX] = true

        var touchesEdge = false
        while (queue.isNotEmpty()) {
            val p = queue.removeFirst()
            if (p.x == 0 || p.y == 0 || p.x == image.width - 1 || p.y == image.height - 1) {
                touchesEdge = true
            }

            for (dy in -1..1) {
                for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = p.x + dx
                    val ny = p.y + dy
                    if (nx !in 0 until image.width || ny !in 0 until image.height) continue
                    if (visited[ny][nx]) continue
                    if (image.getRGB(nx, ny) != Color.WHITE.rgb) continue
                    visited[ny][nx] = true
                    queue.add(Point(nx, ny))
                }
            }
        }

        if (touchesEdge) return mutableListOf()

        val result = mutableListOf<StrokePath>()
        for (y in visited.indices) {
            var x = 0
            while (x < visited[y].size) {
                while (x < visited[y].size && !visited[y][x]) x++
                val start = x
                while (x < visited[y].size && visited[y][x]) x++
                if (start < x) {
                    val yWorld = y + offsetY
                    val x1World = start + offsetX
                    val x2World = (x - 1) + offsetX
                    result += StrokePath(
                        color = fillColor,
                        width = 1.8f,
                        points = mutableListOf(
                            AnchorPoint(0, x1World, yWorld),
                            AnchorPoint(0, x2World, yWorld)
                        ),
                        filled = false,
                        kind = null
                    )
                }
            }
        }

        return result
    }

    private fun cutStrokeByEraser(
        stroke: StrokePath,
        localPoint: Point,
        radius: Double,
        toViewPoint: (AnchorPoint) -> Point?
    ): MutableList<StrokePath> {
        val viewPoints = stroke.points.mapNotNull { anchor ->
            toViewPoint(anchor)?.let { anchor to it }
        }
        if (viewPoints.size < 2) {
            return mutableListOf(stroke.deepCopy())
        }

        val keptSegments = mutableListOf<MutableList<AnchorPoint>>()
        var current = mutableListOf<AnchorPoint>()

        fun flushCurrent() {
            if (current.size >= 2) {
                keptSegments += current
            }
            current = mutableListOf()
        }

        for (i in 0 until viewPoints.lastIndex) {
            val (aAnchor, aPoint) = viewPoints[i]
            val (bAnchor, bPoint) = viewPoints[i + 1]
            val hits = distancePointToSegment(localPoint, aPoint, bPoint) <= radius

            if (!hits) {
                if (current.isEmpty()) current += aAnchor.copy()
                current += bAnchor.copy()
            } else {
                flushCurrent()
            }
        }

        flushCurrent()

        if (keptSegments.isEmpty()) return mutableListOf()

        return keptSegments.mapTo(mutableListOf()) { segment ->
            StrokePath(
                color = stroke.color,
                width = stroke.width,
                points = segment,
                filled = false,
                kind = stroke.kind
            )
        }
    }

    fun areaToFilledStrokes(area: Area, color: Color, width: Float): MutableList<StrokePath> {
        val results = mutableListOf<StrokePath>()
        val path = area.getPathIterator(null, 1.0)
        val coords = DoubleArray(6)
        var current = mutableListOf<AnchorPoint>()

        while (!path.isDone) {
            when (path.currentSegment(coords)) {
                PathIterator.SEG_MOVETO -> {
                    if (current.size >= 3) {
                        results += StrokePath(color = color, width = width, points = current, filled = true)
                    }
                    current = mutableListOf(AnchorPoint(0, coords[0].toInt(), coords[1].toInt()))
                }
                PathIterator.SEG_LINETO -> {
                    current += AnchorPoint(0, coords[0].toInt(), coords[1].toInt())
                }
                PathIterator.SEG_CLOSE -> {
                    if (current.size >= 3) {
                        results += StrokePath(color = color, width = width, points = current, filled = true)
                    }
                    current = mutableListOf()
                }
            }
            path.next()
        }

        if (current.size >= 3) {
            results += StrokePath(color = color, width = width, points = current, filled = true)
        }

        return results
    }

    fun buildPolygon(stroke: StrokePath, toViewPoint: (AnchorPoint) -> Point?): Polygon? {
        val points = stroke.points.mapNotNull(toViewPoint)
        if (points.size < 2) return null
        val polygon = Polygon()
        points.forEach { polygon.addPoint(it.x, it.y) }
        return polygon
    }

    fun buildArea(stroke: StrokePath, toViewPoint: (AnchorPoint) -> Point?): Area? {
        val polygon = buildPolygon(stroke, toViewPoint) ?: return null
        if (polygon.npoints < 3) return null
        return Area(polygon)
    }

    private fun distancePointToSegment(p: Point, a: Point, b: Point): Double {
        val dx = (b.x - a.x).toDouble()
        val dy = (b.y - a.y).toDouble()
        if (dx == 0.0 && dy == 0.0) return p.distance(a)

        val t = (((p.x - a.x) * dx) + ((p.y - a.y) * dy)) / (dx * dx + dy * dy)
        val clamped = t.coerceIn(0.0, 1.0)
        val px = a.x + clamped * dx
        val py = a.y + clamped * dy
        return hypot(p.x - px, p.y - py)
    }
}
