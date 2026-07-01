package com.drawing.geometry

import com.drawing.RasterFillCodec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage

class RasterFillCodecTest {
    @Test
    fun `png base64 round trip preserves dimensions and alpha`() {
        val image = BufferedImage(3, 2, BufferedImage.TYPE_INT_ARGB)
        image.setRGB(0, 0, Color(10, 20, 30, 255).rgb)
        image.setRGB(1, 0, Color(40, 50, 60, 128).rgb)
        image.setRGB(2, 1, 0x00000000)

        val encoded = RasterFillCodec.encodePngBase64(image)
        val decoded = RasterFillCodec.decodePngBase64(encoded)

        assertEquals(3, decoded.width)
        assertEquals(2, decoded.height)
        assertEquals(Color(10, 20, 30, 255).rgb, decoded.getRGB(0, 0))
        assertEquals(Color(40, 50, 60, 128).rgb, decoded.getRGB(1, 0))
        assertEquals(0, decoded.getRGB(2, 1) ushr 24)
    }
}

