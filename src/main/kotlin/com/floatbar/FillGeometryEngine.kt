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
    private const val FILL_STROKE_WIDTH_PX = 3.0f

    /**
     * Safety cap for the temporary flood-fill image.
     * A very large editor viewport can otherwise allocate huge arrays and freeze the UI.
     */
    private const val MAX_FILL_SNAPSHOT_PIXELS = 12_000_000

    fun fillAt(
        strokes: List<StrokePath>,
        seedPoint: Point,
        fillColor: Color,
        panelBounds: Rectangle,
        toViewPoint: (AnchorPoint) -> Point?
    ): MutableList<StrokePath> {
        if (panelBounds.width <= 0 || panelBounds.height <= 0) return mutableListOf()

        val width = panelBounds.width
        val height = panelBounds.height
        val pixelCount = width.toLong() * height.toLong()
        if (pixelCount > MAX_FILL_SNAPSHOT_PIXELS) return mutableListOf()

        val offsetX = panelBounds.x
        val offsetY = panelBounds.y
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
        if (isSameVisibleColor(targetArgb, fillColor.rgb)) return mutableListOf()

        val result = floodFillSameColor(
            image = image,
            startX = seedX,
            startY = seedY,
            targetArgb = targetArgb
        )

        if (result.filledPixelCount == 0) return mutableListOf()

        // The editor background is transparent in this snapshot. If the region reaches
        // the snapshot edge, the user clicked an open/background area instead of a
        // closed region. Returning empty protects against accidental whole-editor fills.
        if (isTransparent(targetArgb) && result.touchesEdge) return mutableListOf()

        return buildDenseFillStrokes(
            visited = result.visited,
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
                    drawFilledStrokeSnapshot(
                        stroke = stroke,
                        offsetX = offsetX,
                        offsetY = offsetY,
                        toViewPoint = toViewPoint,
                        drawPolygon = { polygon ->
                            g.fillPolygon(polygon)
                            g.stroke = BasicStroke(
                                max(1.5f, stroke.width / 2f),
                                BasicStroke.CAP_ROUND,
                                BasicStroke.JOIN_ROUND
                            )
                            g.drawPolygon(polygon)
                        }
                    )
                    continue
                }

                if (points.size < 2) continue

                val shifted = points.map { Point(it.x - offsetX, it.y - offsetY) }
                g.stroke = BasicStroke(
                    max(1f, stroke.width),
                    BasicStroke.CAP_ROUND,
                    BasicStroke.JOIN_ROUND
                )
                g.draw(buildPath(shifted))
                closeTinyStrokeGapIfNeeded(shifted, stroke.width) { start, end ->
                    g.drawLine(start.x, start.y, end.x, end.y)
                }
            }
        } finally {
            g.dispose()
        }

        return image
    }

    private fun drawFilledStrokeSnapshot(
        stroke: StrokePath,
        offsetX: Int,
        offsetY: Int,
        toViewPoint: (AnchorPoint) -> Point?,
        drawPolygon: (Polygon) -> Unit
    ) {
        val polygon = GeometryAreaUtils.buildPolygon(stroke, toViewPoint) ?: return
        if (polygon.npoints < 3) return

        val shifted = Polygon(
            IntArray(polygon.npoints) { i -> polygon.xpoints[i] - offsetX },
            IntArray(polygon.npoints) { i -> polygon.ypoints[i] - offsetY },
            polygon.npoints
        )
        drawPolygon(shifted)
    }

    private fun buildPath(points: List<Point>): Path2D.Float {
        val path = Path2D.Float()
        path.moveTo(points.first().x.toDouble(), points.first().y.toDouble())
        for (point in points.drop(1)) {
            path.lineTo(point.x.toDouble(), point.y.toDouble())
        }
        return path
    }

    private fun closeTinyStrokeGapIfNeeded(
        points: List<Point>,
        strokeWidth: Float,
        drawClosingLine: (Point, Point) -> Unit
    ) {
        if (points.size < 3) return

        val first = points.first()
        val last = points.last()
        val closeThreshold = max(6.0, strokeWidth * 1.8)
        if (first.distance(last) <= closeThreshold) {
            drawClosingLine(last, first)
        }
    }

    private fun floodFillSameColor(
        image: BufferedImage,
        startX: Int,
        startY: Int,
        targetArgb: Int
    ): FloodFillResult {
        val width = image.width
        val height = image.height
        val visited = BooleanArray(width * height)
        val queue = IntArray(width * height)
        var head = 0
        var tail = 0
        var filledPixelCount = 0
        var touchesEdge = false

        fun enqueue(x: Int, y: Int) {
            val index = y * width + x
            if (visited[index]) return
            if (image.getRGB(x, y) != targetArgb) return

            visited[index] = true
            queue[tail++] = index
            filledPixelCount++
            if (x == 0 || y == 0 || x == width - 1 || y == height - 1) {
                touchesEdge = true
            }
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

        return FloodFillResult(
            visited = visited,
            filledPixelCount = filledPixelCount,
            touchesEdge = touchesEdge
        )
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

    private fun isTransparent(argb: Int): Boolean {
        return (argb ushr 24) == 0
    }

    private fun isSameVisibleColor(firstArgb: Int, secondArgb: Int): Boolean {
        if (isTransparent(firstArgb) || isTransparent(secondArgb)) return firstArgb == secondArgb
        return (firstArgb and 0x00FFFFFF) == (secondArgb and 0x00FFFFFF)
    }

    private data class FloodFillResult(
        val visited: BooleanArray,
        val filledPixelCount: Int,
        val touchesEdge: Boolean
    )
}
