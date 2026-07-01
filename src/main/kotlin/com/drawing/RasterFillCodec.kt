package com.drawing

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO

internal object RasterFillCodec {
    fun encodePngBase64(image: BufferedImage): String {
        val output = ByteArrayOutputStream()
        check(ImageIO.write(image, "png", output)) { "No PNG writer available" }
        return Base64.getEncoder().encodeToString(output.toByteArray())
    }

    fun decodePngBase64(pngBase64: String): BufferedImage {
        val bytes = Base64.getDecoder().decode(pngBase64)
        return ImageIO.read(ByteArrayInputStream(bytes))
            ?: error("Could not decode raster fill PNG")
    }
}

