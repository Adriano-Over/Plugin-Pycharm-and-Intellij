package com.drawing

import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Point
import java.awt.Polygon
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import kotlin.math.ceil
import kotlin.math.max

internal object FillGeometryEngine {

    private const val FILL_STROKE_WIDTH_PX = 1.0f
    private const val FILL_BOUNDS_PADDING_PX = 12
    private const val RASTER_FILL_EDGE_OVERLAP_PX = 2
    private const val MAX_FILLED_PIXELS = 900_000
    private const val MAX_MERGED_FILL_RECTS = 3_000

    /**
     * Safety cap for the temporary flood-fill image.
     * A very large editor viewport can otherwise allocate huge arrays and freeze the UI.
     */
    private const val MAX_FILL_SNAPSHOT_PIXELS = 2_000_000

    fun fillAt(
        strokes: List<StrokePath>,
        seedPoint: Point,
        fillColor: Color,
        panelBounds: Rectangle,
        toViewPoint: (AnchorPoint) -> Point?
    ): MutableList<StrokePath> {
        val snapshotBounds = resolveSnapshotBounds(
            strokes = strokes,
            seedPoint = seedPoint,
            panelBounds = panelBounds,
            toViewPoint = toViewPoint
        ) ?: return mutableListOf()

        val width = snapshotBounds.width
        val height = snapshotBounds.height
        val pixelCount = width.toLong() * height.toLong()
        if (pixelCount > MAX_FILL_SNAPSHOT_PIXELS) return mutableListOf()

        val offsetX = snapshotBounds.x
        val offsetY = snapshotBounds.y
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
            targetArgb = targetArgb,
            maxFilledPixels = MAX_FILLED_PIXELS
        )

        if (result.filledPixelCount == 0 || result.aborted) return mutableListOf()

        // The editor background is transparent in this snapshot. If the region reaches
        // the snapshot edge, the user clicked an open/background area instead of a
        // closed region. Returning empty protects against accidental whole-editor fills.
        if (isTransparent(targetArgb) && result.touchesEdge) return mutableListOf()

        return buildMergedFillStrokes(
            visited = result.visited,
            width = width,
            height = height,
            fillColor = fillColor,
            offsetX = offsetX,
            offsetY = offsetY
        )
    }

    fun fillRasterAt(
        strokes: List<StrokePath>,
        existingRasterFills: List<RasterFillPath>,
        seedPoint: Point,
        fillColor: Color,
        panelBounds: Rectangle,
        toViewPoint: (AnchorPoint) -> Point?,
        toAnchor: (Point) -> AnchorPoint?
    ): RasterFillPath? {
        val snapshotBounds = resolveSnapshotBounds(
            strokes = strokes,
            rasterFills = existingRasterFills,
            seedPoint = seedPoint,
            panelBounds = panelBounds,
            toViewPoint = toViewPoint
        ) ?: return null

        val width = snapshotBounds.width
        val height = snapshotBounds.height
        val pixelCount = width.toLong() * height.toLong()
        if (pixelCount > MAX_FILL_SNAPSHOT_PIXELS) return null

        val offsetX = snapshotBounds.x
        val offsetY = snapshotBounds.y
        val seedX = seedPoint.x - offsetX
        val seedY = seedPoint.y - offsetY

        if (seedX !in 0 until width || seedY !in 0 until height) return null

        val image = renderColorSnapshot(
            strokes = strokes,
            rasterFills = existingRasterFills,
            width = width,
            height = height,
            offsetX = offsetX,
            offsetY = offsetY,
            toViewPoint = toViewPoint
        )

        val targetArgb = image.getRGB(seedX, seedY)
        if (isSameVisibleColor(targetArgb, fillColor.rgb)) return null

        val result = floodFillSameColor(
            image = image,
            startX = seedX,
            startY = seedY,
            targetArgb = targetArgb,
            maxFilledPixels = MAX_FILLED_PIXELS
        )

        if (result.filledPixelCount == 0 || result.aborted || result.touchesEdge) return null

        val outputBounds = Rectangle(result.bounds)
        outputBounds.grow(RASTER_FILL_EDGE_OVERLAP_PX, RASTER_FILL_EDGE_OVERLAP_PX)
        outputBounds.x = outputBounds.x.coerceAtLeast(0)
        outputBounds.y = outputBounds.y.coerceAtLeast(0)
        val maxOutputX = (result.bounds.x + result.bounds.width + RASTER_FILL_EDGE_OVERLAP_PX).coerceAtMost(width)
        val maxOutputY = (result.bounds.y + result.bounds.height + RASTER_FILL_EDGE_OVERLAP_PX).coerceAtMost(height)
        outputBounds.width = (maxOutputX - outputBounds.x).coerceAtLeast(1)
        outputBounds.height = (maxOutputY - outputBounds.y).coerceAtLeast(1)

        val expandedVisited = expandVisitedMask(
            visited = result.visited,
            width = width,
            height = height,
            radius = RASTER_FILL_EDGE_OVERLAP_PX
        )

        val output = BufferedImage(outputBounds.width, outputBounds.height, BufferedImage.TYPE_INT_ARGB)
        for (y in outputBounds.y until outputBounds.y + outputBounds.height) {
            val rowStart = y * width
            for (x in outputBounds.x until outputBounds.x + outputBounds.width) {
                if (expandedVisited[rowStart + x]) {
                    output.setRGB(x - outputBounds.x, y - outputBounds.y, fillColor.rgb)
                }
            }
        }

        val anchorPoint = Point(outputBounds.x + offsetX, outputBounds.y + offsetY)
        val anchor = toAnchor(anchorPoint) ?: return null
        return RasterFillPath(
            color = fillColor,
            anchor = anchor,
            width = output.width,
            height = output.height,
            pngBase64 = RasterFillCodec.encodePngBase64(output)
        )
    }

    private fun expandVisitedMask(
        visited: BooleanArray,
        width: Int,
        height: Int,
        radius: Int
    ): BooleanArray {
        if (radius <= 0) return visited
        val expanded = visited.copyOf()
        for (y in 0 until height) {
            val rowStart = y * width
            for (x in 0 until width) {
                if (!visited[rowStart + x]) continue
                for (dy in -radius..radius) {
                    val ny = y + dy
                    if (ny !in 0 until height) continue
                    for (dx in -radius..radius) {
                        val nx = x + dx
                        if (nx !in 0 until width) continue
                        expanded[ny * width + nx] = true
                    }
                }
            }
        }
        return expanded
    }

    private fun resolveSnapshotBounds(
        strokes: List<StrokePath>,
        rasterFills: List<RasterFillPath> = emptyList(),
        seedPoint: Point,
        panelBounds: Rectangle,
        toViewPoint: (AnchorPoint) -> Point?
    ): Rectangle? {
        if (panelBounds.width <= 0 || panelBounds.height <= 0) return null

        val drawingBounds = (strokes.mapNotNull { stroke -> strokeViewBounds(stroke, toViewPoint) } +
            rasterFills.mapNotNull { fill -> rasterFillViewBounds(fill, toViewPoint) })
            .fold(null as Rectangle?) { union, bounds ->
                union?.apply { add(bounds) } ?: Rectangle(bounds)
            }
            ?: return null

        if (!drawingBounds.contains(seedPoint)) return null

        val snapshotBounds = drawingBounds.intersection(panelBounds)
        return if (snapshotBounds.width > 0 && snapshotBounds.height > 0) snapshotBounds else null
    }

    private fun rasterFillViewBounds(
        fill: RasterFillPath,
        toViewPoint: (AnchorPoint) -> Point?
    ): Rectangle? {
        if (fill.width <= 0 || fill.height <= 0) return null
        val topLeft = toViewPoint(fill.anchor.copy()) ?: return null
        return Rectangle(topLeft.x, topLeft.y, fill.width, fill.height)
    }

    private fun strokeViewBounds(
        stroke: StrokePath,
        toViewPoint: (AnchorPoint) -> Point?
    ): Rectangle? {
        val points = stroke.points.mapNotNull(toViewPoint)
        if (points.isEmpty()) return null

        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var maxY = Int.MIN_VALUE
        for (point in points) {
            minX = minOf(minX, point.x)
            minY = minOf(minY, point.y)
            maxX = maxOf(maxX, point.x)
            maxY = maxOf(maxY, point.y)
        }

        val bounds = Rectangle(minX, minY, (maxX - minX).coerceAtLeast(1), (maxY - minY).coerceAtLeast(1))
        val padding = max(FILL_BOUNDS_PADDING_PX, ceil(stroke.width.toDouble()).toInt() + FILL_BOUNDS_PADDING_PX)
        bounds.grow(padding, padding)
        return bounds
    }

    private fun renderColorSnapshot(
        strokes: List<StrokePath>,
        rasterFills: List<RasterFillPath> = emptyList(),
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

            for (fill in rasterFills) {
                val topLeft = toViewPoint(fill.anchor.copy()) ?: continue
                val image = runCatching { RasterFillCodec.decodePngBase64(fill.pngBase64) }.getOrNull() ?: continue
                g.drawImage(image, topLeft.x - offsetX, topLeft.y - offsetY, null)
            }

            for (stroke in strokes) {
                if (stroke.annotationText != null) {
                    continue
                }
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
        targetArgb: Int,
        maxFilledPixels: Int
    ): FloodFillResult {
        val width = image.width
        val height = image.height
        val visited = BooleanArray(width * height)
        val queue = IntArray(width * height)
        var head = 0
        var tail = 0
        var filledPixelCount = 0
        var touchesEdge = false
        var aborted = false
        var minX = startX
        var maxX = startX
        var minY = startY
        var maxY = startY

        fun enqueue(x: Int, y: Int) {
            if (aborted) return
            val index = y * width + x
            if (visited[index]) return
            if (image.getRGB(x, y) != targetArgb) return

            visited[index] = true
            queue[tail++] = index
            filledPixelCount++
            if (filledPixelCount > maxFilledPixels) {
                aborted = true
                return
            }
            if (x == 0 || y == 0 || x == width - 1 || y == height - 1) {
                touchesEdge = true
            }
            minX = minOf(minX, x)
            maxX = maxOf(maxX, x)
            minY = minOf(minY, y)
            maxY = maxOf(maxY, y)
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
            touchesEdge = touchesEdge,
            aborted = aborted,
            bounds = Rectangle(minX, minY, maxX - minX + 1, maxY - minY + 1)
        )
    }

    private fun buildMergedFillStrokes(
        visited: BooleanArray,
        width: Int,
        height: Int,
        fillColor: Color,
        offsetX: Int,
        offsetY: Int
    ): MutableList<StrokePath> {
        val rectangles = mergeFilledRuns(
            runsByRow = collectFilledRunsByRow(visited, width, height)
        )
        if (rectangles.size > MAX_MERGED_FILL_RECTS) return mutableListOf()
        return rectangles.mapTo(mutableListOf()) { rectangle ->
            StrokePath(
                color = fillColor,
                width = FILL_STROKE_WIDTH_PX,
                points = mutableListOf(
                    AnchorPoint(0, 0, rectangle.x + offsetX, rectangle.y + offsetY),
                    AnchorPoint(0, 0, rectangle.x + rectangle.width + offsetX, rectangle.y + offsetY),
                    AnchorPoint(0, 0, rectangle.x + rectangle.width + offsetX, rectangle.y + rectangle.height + offsetY),
                    AnchorPoint(0, 0, rectangle.x + offsetX, rectangle.y + rectangle.height + offsetY)
                ),
                filled = true,
                kind = null
            )
        }
    }

    private fun collectFilledRunsByRow(
        visited: BooleanArray,
        width: Int,
        height: Int
    ): List<List<IntRange>> {
        val rows = MutableList(height) { mutableListOf<IntRange>() }

        for (y in 0 until height) {
            val rowStart = y * width
            var x = 0

            while (x < width) {
                while (x < width && !visited[rowStart + x]) x++
                val start = x
                while (x < width && visited[rowStart + x]) x++
                val end = x - 1

                if (start <= end) {
                    rows[y] += start..end
                }
            }
        }

        return rows
    }

    private fun mergeFilledRuns(runsByRow: List<List<IntRange>>): List<Rectangle> {
        val active = linkedMapOf<IntRange, Rectangle>()
        val merged = mutableListOf<Rectangle>()

        for ((y, runs) in runsByRow.withIndex()) {
            val current = linkedMapOf<IntRange, Rectangle>()

            for (run in runs) {
                val continuing = active.remove(run)
                current[run] = if (continuing != null) {
                    continuing.apply { height += 1 }
                } else {
                    Rectangle(run.first, y, run.last - run.first + 1, 1)
                }
            }

            merged += active.values
            active.clear()
            active.putAll(current)
        }

        merged += active.values
        return merged
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
        val touchesEdge: Boolean,
        val aborted: Boolean,
        val bounds: Rectangle
    )
}
