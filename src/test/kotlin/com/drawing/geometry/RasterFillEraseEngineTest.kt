package com.drawing.geometry

import com.drawing.AnchorPoint
import com.drawing.RasterFillCodec
import com.drawing.RasterFillEraseEngine
import com.drawing.RasterFillPath
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.Point
import java.awt.image.BufferedImage

class RasterFillEraseEngineTest {
    private val toViewPoint: (AnchorPoint) -> Point? = { Point(it.dx, it.dy) }

    @Test
    fun `erase clears only pixels inside radius`() {
        val fill = solidFill(width = 10, height = 10)

        val rebuilt = RasterFillEraseEngine.eraseAlongPathByFill(
            fills = listOf(fill),
            localPoints = listOf(Point(5, 5)),
            radius = 2.0,
            toViewPoint = toViewPoint
        )

        val erased = rebuilt[fill.id]
        assertTrue(erased != null, "Partially erased fill should remain")
        val image = RasterFillCodec.decodePngBase64(erased!!.pngBase64)
        assertEquals(0, image.getRGB(5, 5) ushr 24, "Center pixel should be transparent after erase")
        assertEquals(255, image.getRGB(0, 0) ushr 24, "Untouched corner should remain opaque")
    }

    @Test
    fun `erase removes fill when all pixels become transparent`() {
        val fill = solidFill(width = 4, height = 4)

        val rebuilt = RasterFillEraseEngine.eraseAlongPathByFill(
            fills = listOf(fill),
            localPoints = listOf(Point(2, 2)),
            radius = 20.0,
            toViewPoint = toViewPoint
        )

        assertEquals(null, rebuilt[fill.id], "Fully erased raster fills should be removed")
    }

    @Test
    fun `erase removes sparse fill when dirty region contains all remaining opaque pixels`() {
        val image = BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB)
        image.setRGB(10, 10, Color.GREEN.rgb)
        val fill = rasterFillFromImage(image)

        val rebuilt = RasterFillEraseEngine.eraseAlongPathByFill(
            fills = listOf(fill),
            localPoints = listOf(Point(10, 10)),
            radius = 2.0,
            toViewPoint = toViewPoint
        )

        assertEquals(null, rebuilt[fill.id], "Sparse fills should be removed when their only opaque pixels are erased")
    }

    private fun solidFill(width: Int, height: Int): RasterFillPath {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until height) {
            for (x in 0 until width) {
                image.setRGB(x, y, Color.GREEN.rgb)
            }
        }
        return rasterFillFromImage(image)
    }

    private fun rasterFillFromImage(image: BufferedImage): RasterFillPath {
        return RasterFillPath(
            id = 88L,
            color = Color.GREEN,
            anchor = AnchorPoint(line = 0, column = 0, dx = 0, dy = 0),
            width = image.width,
            height = image.height,
            pngBase64 = RasterFillCodec.encodePngBase64(image)
        )
    }
}
