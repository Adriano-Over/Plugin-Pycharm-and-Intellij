package com.floatbar

import java.awt.AlphaComposite
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
    private const val MAX_FILL_SEGMENT_WIDTH_PX = 9
    private const val FILL_STROKE_WIDTH_PX = 2.0f

    fun fillAt(
        strokes: List<StrokePath>,
        seedPoint: Point,
        fillColor: Color,
        panelBounds: Rectangle,
        toViewPoint: (AnchorPoint) -> Point?
    ): MutableList<StrokePath> {
        if (panelBounds.width <= 0 || panelBounds.height <= 0) return mutableListOf()

        val offsetX = panelBounds.x
        val offsetY = panelBounds.y
        val width = panelBounds.width
        val height = panelBounds.height

        val seedX = seedPoint.x - offsetX
        val seedY = seedPoint.y - offsetY
        if (seedX !in 0 until width || seedY !in 0 until height) return mutableListOf()

        val image = renderColorSnapshot(
            strokes = strokes,
            width = width,
            height = height,
            offsetX = offsetX,
            offsetY = offsetY,
            toViewPoint = toViewPoint
        )

        val targetArgb = image.getRGB(seedX, seedY)
        if (targetArgb == fillColor.rgb) return mutableListOf()

        val visited = floodFillSameColor(
            image = image,
            startX = seedX,
            startY = seedY,
            targetArgb = targetArgb
        )

        return buildDenseFillStrokes(
            visited = visited,
            width = width,
            height = height,
            fillColor = fillColor,
            offsetX = offsetX,
            offsetY = offsetY
        )
    }

    private fun renderColorSnapshot(
        strokes: List<StrokePath>,
        width: Int,
        height: Int,
        offsetX: Int,
        offsetY: Int,
        toViewPoint: (AnchorPoint) -> Point?
    ): BufferedImage {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()

        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF)
            g.composite = AlphaComposite.Src
            g.color = Color(0, 0, 0, 0)
            g.fillRect(0, 0, width, height)
            g.composite = AlphaComposite.SrcOver

            for (stroke in strokes) {
                val points = stroke.points.mapNotNull(toViewPoint)
                if (points.isEmpty()) continue

                g.color = stroke.color

                if (stroke.filled) {
                    val polygon = GeometryAreaUtils.buildPolygon(stroke, toViewPoint)
                    if (polygon != null && polygon.npoints >= 3) {
                        val shifted = Polygon(
                            IntArray(polygon.npoints) { i -> polygon.xpoints[i] - offsetX },
                            IntArray(polygon.npoints) { i -> polygon.ypoints[i] - offsetY },
                            polygon.npoints
                        )
                        g.fillPolygon(shifted)
                        g.stroke = BasicStroke(
                            max(1.5f, stroke.width / 2f),
                            BasicStroke.CAP_ROUND,
                            BasicStroke.JOIN_ROUND
                        )
                        g.drawPolygon(shifted)
                    }
                    continue
                }

                if (points.size < 2) continue

                val shifted = points.map { Point(it.x - offsetX, it.y - offsetY) }
                val path = Path2D.Float()
                path.moveTo(shifted.first().x.toDouble(), shifted.first().y.toDouble())
                for (point in shifted.drop(1)) {
                    path.lineTo(point.x.toDouble(), point.y.toDouble())
                }

                g.stroke = BasicStroke(
                    max(1f, stroke.width),
                    BasicStroke.CAP_ROUND,
                    BasicStroke.JOIN_ROUND
                )
                g.draw(path)
            }
        } finally {
            g.dispose()
        }

        return image
    }

    private fun floodFillSameColor(
        image: BufferedImage,
        startX: Int,
        startY: Int,
        targetArgb: Int
    ): BooleanArray {
        val width = image.width
        val height = image.height
        val visited = BooleanArray(width * height)
        val queue = IntArray(width * height)
        var head = 0
        var tail = 0

        fun enqueue(x: Int, y: Int) {
            val index = y * width + x
            if (visited[index]) return
            if (image.getRGB(x, y) != targetArgb) return

            visited[index] = true
            queue[tail++] = index
        }

        enqueue(startX, startY)

        while (head < tail) {
            val index = queue[head++]
            val x = index % width
            val y = index / width

            if (x > 0) enqueue(x - 1, y)
            if (x < width - 1) enqueue(x + 1, y)
            if (y > 0) enqueue(x, y - 1)
            if (y < height - 1) enqueue(x, y + 1)
        }

        return visited
    }

    private fun buildDenseFillStrokes(
        visited: BooleanArray,
        width: Int,
        height: Int,
        fillColor: Color,
        offsetX: Int,
        offsetY: Int
    ): MutableList<StrokePath> {
        val result = mutableListOf<StrokePath>()

        for (y in 0 until height) {
            val rowStart = y * width
            var x = 0

            while (x < width) {
                while (x < width && !visited[rowStart + x]) x++
                val start = x

                while (x < width && visited[rowStart + x]) x++
                val end = x - 1

                if (start <= end) {
                    var segmentStart = start
                    while (segmentStart <= end) {
                        val segmentEnd = minOf(segmentStart + MAX_FILL_SEGMENT_WIDTH_PX - 1, end)
                        result += StrokePath(
                            color = fillColor,
                            width = FILL_STROKE_WIDTH_PX,
                            points = mutableListOf(
                                AnchorPoint(0, 0, segmentStart + offsetX, y + offsetY),
                                AnchorPoint(0, 0, segmentEnd + offsetX, y + offsetY)
                            ),
                            filled = false,
                            kind = null
                        )
                        segmentStart = segmentEnd + 1
                    }
                }
            }
        }

        return result
    }
}