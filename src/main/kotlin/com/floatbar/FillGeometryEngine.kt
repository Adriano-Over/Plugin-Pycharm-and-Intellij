package com.floatbar

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Point
import java.awt.Polygon
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import kotlin.math.max

internal object FillGeometryEngine {

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
                val polygon = GeometryAreaUtils.buildPolygon(stroke, toViewPoint)
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

        return buildDenseFillStrokes(visited, fillColor, offsetX, offsetY)
    }

    private fun buildDenseFillStrokes(
        visited: Array<BooleanArray>,
        fillColor: Color,
        offsetX: Int,
        offsetY: Int
    ): MutableList<StrokePath> {
        val result = mutableListOf<StrokePath>()

        for (y in visited.indices) {
            var x = 0
            while (x < visited[y].size) {
                while (x < visited[y].size && !visited[y][x]) x++
                val start = x
                while (x < visited[y].size && visited[y][x]) x++
                val end = x - 1

                if (start <= end) {
                    var segStart = start
                    while (segStart <= end) {
                        val segEnd = minOf(segStart + 6, end)
                        result += StrokePath(
                            color = fillColor,
                            width = 2.0f,
                            points = mutableListOf(
                                AnchorPoint(0, 0, segStart + offsetX, y + offsetY),
                                AnchorPoint(0, 0, segEnd + offsetX, y + offsetY)
                            ),
                            filled = false,
                            kind = null
                        )
                        segStart = segEnd + 1
                    }
                }
            }
        }

        return result
    }
}
