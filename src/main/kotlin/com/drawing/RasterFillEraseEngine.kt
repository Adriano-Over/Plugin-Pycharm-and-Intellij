package com.drawing

import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Point
import java.awt.Rectangle
import java.awt.geom.Path2D
import java.awt.image.BufferedImage

private data class RasterEraseResult(
    val changed: Boolean,
    val fullyTransparent: Boolean
)

internal object RasterFillEraseEngine {
    fun eraseAlongPathByFill(
        fills: List<RasterFillPath>,
        localPoints: List<Point>,
        radius: Double,
        toViewPoint: (AnchorPoint) -> Point?
    ): LinkedHashMap<Long, RasterFillPath?> {
        val rebuiltByFill = LinkedHashMap<Long, RasterFillPath?>()
        if (localPoints.isEmpty() || fills.isEmpty()) return rebuiltByFill

        val eraserBounds = eraserPathBounds(localPoints, radius)
        for (fill in fills) {
            val topLeft = toViewPoint(fill.anchor.copy()) ?: continue
            val fillBounds = Rectangle(topLeft.x, topLeft.y, fill.width, fill.height)
            if (!fillBounds.intersects(eraserBounds)) continue

            val image = runCatching {
                RasterFillCodec.decodePngBase64(
                    pngBase64 = fill.pngBase64,
                    expectedWidth = fill.width,
                    expectedHeight = fill.height
                )
            }.getOrNull() ?: continue
            val result = clearEraserPath(image, localPoints, topLeft, radius)
            if (!result.changed) continue

            rebuiltByFill[fill.id] = if (result.fullyTransparent) {
                null
            } else {
                fill.copy(
                    pngBase64 = RasterFillCodec.encodePngBase64(image)
                )
            }
        }

        return rebuiltByFill
    }

    private fun eraserPathBounds(points: List<Point>, radius: Double): Rectangle {
        var minX = points.minOf { it.x }
        var maxX = points.maxOf { it.x }
        var minY = points.minOf { it.y }
        var maxY = points.maxOf { it.y }
        val padding = kotlin.math.ceil(radius).toInt().coerceAtLeast(1)
        minX -= padding
        minY -= padding
        maxX += padding
        maxY += padding
        return Rectangle(minX, minY, maxX - minX + 1, maxY - minY + 1)
    }

    private fun clearEraserPath(
        image: BufferedImage,
        points: List<Point>,
        fillTopLeft: Point,
        radius: Double
    ): RasterEraseResult {
        val dirtyBounds = imageDirtyBounds(image, eraserPathBounds(points, radius), fillTopLeft)
            ?: return RasterEraseResult(changed = false, fullyTransparent = false)
        val beforeAlpha = alphaSum(image, dirtyBounds)
        val g = image.createGraphics()
        try {
            g.composite = AlphaComposite.Clear
            val diameter = kotlin.math.ceil(radius * 2.0).toInt().coerceAtLeast(1)
            val r = kotlin.math.ceil(radius).toInt().coerceAtLeast(1)

            if (points.size == 1) {
                val point = points.first()
                g.fillOval(point.x - fillTopLeft.x - r, point.y - fillTopLeft.y - r, diameter, diameter)
            } else {
                val path = Path2D.Double()
                path.moveTo(
                    (points.first().x - fillTopLeft.x).toDouble(),
                    (points.first().y - fillTopLeft.y).toDouble()
                )
                for (point in points.drop(1)) {
                    path.lineTo((point.x - fillTopLeft.x).toDouble(), (point.y - fillTopLeft.y).toDouble())
                }
                g.stroke = BasicStroke(
                    diameter.toFloat(),
                    BasicStroke.CAP_ROUND,
                    BasicStroke.JOIN_ROUND
                )
                g.draw(path)
            }
        } finally {
            g.dispose()
        }

        val afterAlpha = alphaSum(image, dirtyBounds)
        if (afterAlpha == beforeAlpha) {
            return RasterEraseResult(changed = false, fullyTransparent = false)
        }

        val fullyTransparent = afterAlpha == 0L && isFullyTransparentOutside(image, dirtyBounds)
        return RasterEraseResult(changed = true, fullyTransparent = fullyTransparent)
    }

    private fun imageDirtyBounds(image: BufferedImage, eraserBounds: Rectangle, fillTopLeft: Point): Rectangle? {
        val bounds = Rectangle(eraserBounds)
        bounds.translate(-fillTopLeft.x, -fillTopLeft.y)
        val imageBounds = Rectangle(0, 0, image.width, image.height)
        return bounds.intersection(imageBounds).takeIf { !it.isEmpty }
    }

    private fun alphaSum(image: BufferedImage, bounds: Rectangle): Long {
        var sum = 0L
        val maxX = bounds.x + bounds.width
        val maxY = bounds.y + bounds.height
        for (y in bounds.y until maxY) {
            for (x in bounds.x until maxX) {
                sum += (image.getRGB(x, y) ushr 24)
            }
        }
        return sum
    }

    private fun isFullyTransparentOutside(image: BufferedImage, dirtyBounds: Rectangle): Boolean {
        if (dirtyBounds.contains(Rectangle(0, 0, image.width, image.height))) {
            return true
        }

        if (!isTransparentBand(image, 0, dirtyBounds.y, 0, image.width)) return false
        if (!isTransparentBand(image, dirtyBounds.y + dirtyBounds.height, image.height, 0, image.width)) return false
        if (!isTransparentBand(image, dirtyBounds.y, dirtyBounds.y + dirtyBounds.height, 0, dirtyBounds.x)) return false
        return isTransparentBand(
            image = image,
            startY = dirtyBounds.y,
            endY = dirtyBounds.y + dirtyBounds.height,
            startX = dirtyBounds.x + dirtyBounds.width,
            endX = image.width
        )
    }

    private fun isTransparentBand(
        image: BufferedImage,
        startY: Int,
        endY: Int,
        startX: Int,
        endX: Int
    ): Boolean {
        if (startY >= endY || startX >= endX) return true
        for (y in startY until endY) {
            for (x in startX until endX) {
                if ((image.getRGB(x, y) ushr 24) != 0) return false
            }
        }
        return true
    }
}
