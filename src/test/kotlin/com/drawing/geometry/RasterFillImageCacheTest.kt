package com.drawing.geometry

import com.drawing.AnchorPoint
import com.drawing.RasterFillCodec
import com.drawing.RasterFillImageCache
import com.drawing.RasterFillPath
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage

class RasterFillImageCacheTest {
    @Test
    fun `cache reuses images and invalidates by fill id`() {
        val cache = RasterFillImageCache()
        val fill = rasterFill(id = 1L)

        val first = cache.get(fill)
        val second = cache.get(fill)
        cache.invalidate(fill.id)
        val third = cache.get(fill)

        assertSame(first, second)
        assertNotSame(first, third)
    }

    @Test
    fun `cache evicts oldest images after the bounded capacity`() {
        val cache = RasterFillImageCache()
        val firstFill = rasterFill(id = 1L)
        val firstImage = cache.get(firstFill)

        for (id in 2L..258L) {
            cache.get(rasterFill(id = id))
        }

        assertNotSame(firstImage, cache.get(firstFill))
    }

    private fun rasterFill(id: Long): RasterFillPath {
        return RasterFillPath(
            id = id,
            color = Color.GREEN,
            anchor = AnchorPoint(line = 0, column = 0, dx = 0, dy = 0),
            width = 1,
            height = 1,
            pngBase64 = onePixelPng()
        )
    }

    private fun onePixelPng(): String {
        val image = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
        image.setRGB(0, 0, Color.GREEN.rgb)
        return RasterFillCodec.encodePngBase64(image)
    }
}
