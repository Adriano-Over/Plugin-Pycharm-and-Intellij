package com.drawing

import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Point
import java.awt.Rectangle
import java.awt.geom.Path2D
import java.awt.image.BufferedImage

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

            val image = runCatching { RasterFillCodec.decodePngBase64(fill.pngBase64) }.getOrNull() ?: continue
            val changed = clearEraserPath(image, localPoints, topLeft, radius)
            if (!changed) continue

            rebuiltByFill[fill.id] = if (isFullyTransparent(image)) {
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
    ): Boolean {
        val beforeAlpha = alphaSum(image)
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

        return alphaSum(image) != beforeAlpha
    }

    private fun alphaSum(image: BufferedImage): Long {
        var sum = 0L
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                sum += (image.getRGB(x, y) ushr 24)
            }
        }
        return sum
    }

    private fun isFullyTransparent(image: BufferedImage): Boolean {
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                if ((image.getRGB(x, y) ushr 24) != 0) return false
            }
        }
        return true
    }
}

