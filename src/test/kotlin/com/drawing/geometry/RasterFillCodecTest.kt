package com.drawing.geometry

import com.drawing.RasterFillCodec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
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

    @Test
    fun `png decode rejects oversized base64 before image allocation`() {
        val oversizedBase64 = "A".repeat(23 * 1024 * 1024)

        assertThrows(IllegalArgumentException::class.java) {
            RasterFillCodec.decodePngBase64(oversizedBase64)
        }
    }

    @Test
    fun `png decode rejects images that do not match persisted dimensions`() {
        val image = BufferedImage(3, 2, BufferedImage.TYPE_INT_ARGB)
        val encoded = RasterFillCodec.encodePngBase64(image)

        assertThrows(IllegalArgumentException::class.java) {
            RasterFillCodec.decodePngBase64(
                pngBase64 = encoded,
                expectedWidth = 2,
                expectedHeight = 3
            )
        }
    }
}
