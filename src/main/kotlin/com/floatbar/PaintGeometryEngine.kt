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
import java.awt.geom.Rectangle2D
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

            val fragments = cutStrokeByEraser(stroke, localPoint, radius, toViewPoint)
            rebuilt += fragments
        }

        return rebuilt
    }

    /**
     * Rewritten to behave more like a real paint app:
     * - rasterizes the *visible* outlines of all tools into one mask
     * - closes tiny endpoint gaps before flood-fill
     * - uses 8-connected flood fill to prevent diagonal leaks
     * - returns the filled region as a normal filled StrokePath
     */
    fun fillAt(
        strokes: List<StrokePath>,
        seedPoint: Point,
        fillColor: Color,
        panelBounds: Rectangle,
        toViewPoint: (AnchorPoint) -> Point?
    ): StrokePath? {
        if (panelBounds.width <= 2 || panelBounds.height <= 2) return null

        val offsetX = panelBounds.x
        val offsetY = panelBounds.y

        val image = BufferedImage(panelBounds.width, panelBounds.height, BufferedImage.TYPE_BYTE_BINARY)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF)
        g.color = Color.WHITE
        g.fillRect(0, 0, image.width, image.height)
        g.color = Color.BLACK

        val endpoints = mutableListOf<Pair<Point, Float>>()

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
            val strokeWidth = max(2f, stroke.width)
            g.stroke = BasicStroke(
                strokeWidth,
                BasicStroke.CAP_ROUND,
                BasicStroke.JOIN_ROUND
            )

            val path = Path2D.Float()
            path.moveTo(shifted.first().x.toDouble(), shifted.first().y.toDouble())
            for (point in shifted.drop(1)) {
                path.lineTo(point.x.toDouble(), point.y.toDouble())
            }
            g.draw(path)

            endpoints += shifted.first() to strokeWidth
            endpoints += shifted.last() to strokeWidth

            val closeDistance = max(6.0, strokeWidth * 2.0)
            if (shifted.first().distance(shifted.last()) <= closeDistance) {
                g.drawLine(
                    shifted.last().x,
                    shifted.last().y,
                    shifted.first().x,
                    shifted.first().y
                )
            }
        }

        bridgeNearbyEndpoints(g, endpoints)

        g.dispose()

        val seedX = seedPoint.x - offsetX
        val seedY = seedPoint.y - offsetY

        if (seedX !in 0 until image.width || seedY !in 0 until image.height) return null
        if (image.getRGB(seedX, seedY) != Color.WHITE.rgb) return null

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

        if (touchesEdge) return null

        val area = Area()
        for (y in visited.indices) {
            var x = 0
            while (x < visited[y].size) {
                while (x < visited[y].size && !visited[y][x]) x++
                val start = x
                while (x < visited[y].size && visited[y][x]) x++
                if (start < x) {
                    area.add(
                        Area(
                            Rectangle2D.Double(
                                (start + offsetX).toDouble(),
                                (y + offsetY).toDouble(),
                                (x - start).toDouble(),
                                1.0
                            )
                        )
                    )
                }
            }
        }

        val filled = areaToFilledStrokes(area, fillColor, 1.5f)
        return largestFilledStroke(filled)
    }

    private fun bridgeNearbyEndpoints(g: java.awt.Graphics2D, endpoints: List<Pair<Point, Float>>) {
        for (i in endpoints.indices) {
            val (a, aw) = endpoints[i]
            for (j in i + 1 until endpoints.size) {
                val (b, bw) = endpoints[j]
                val distance = a.distance(b)
                val threshold = max(6.0, ((aw + bw) * 1.6).toDouble())
                if (distance <= threshold) {
                    g.stroke = BasicStroke(
                        max(2f, max(aw, bw)),
                        BasicStroke.CAP_ROUND,
                        BasicStroke.JOIN_ROUND
                    )
                    g.drawLine(a.x, a.y, b.x, b.y)
                }
            }
        }
    }

    private fun largestFilledStroke(strokes: List<StrokePath>): StrokePath? {
        return strokes.maxByOrNull { stroke ->
            val pts = stroke.points
            if (pts.size < 3) return@maxByOrNull 0.0
            var area = 0.0
            for (i in pts.indices) {
                val a = pts[i]
                val b = pts[(i + 1) % pts.size]
                area += (a.x * b.dy - b.x * a.dy).toDouble()
            }
            kotlin.math.abs(area) / 2.0
        }
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
                        results += StrokePath(
                            color = color,
                            width = width,
                            points = current,
                            filled = true
                        )
                    }
                    current = mutableListOf(
                        AnchorPoint(0, coords[0].toInt(), coords[1].toInt())
                    )
                }

                PathIterator.SEG_LINETO -> {
                    current += AnchorPoint(0, coords[0].toInt(), coords[1].toInt())
                }

                PathIterator.SEG_CLOSE -> {
                    if (current.size >= 3) {
                        results += StrokePath(
                            color = color,
                            width = width,
                            points = current,
                            filled = true
                        )
                    }
                    current = mutableListOf()
                }
            }
            path.next()
        }

        if (current.size >= 3) {
            results += StrokePath(
                color = color,
                width = width,
                points = current,
                filled = true
            )
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
