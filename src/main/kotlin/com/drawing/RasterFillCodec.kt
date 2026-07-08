package com.drawing

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO

internal object RasterFillCodec {
    private const val MAX_ENCODED_PNG_BYTES = 16 * 1024 * 1024
    private const val MAX_RASTER_FILL_PIXELS = 16 * 1024 * 1024
    private const val MAX_RASTER_FILL_SIDE_PX = 16_384

    fun encodePngBase64(image: BufferedImage): String {
        require(isSupportedDimensions(image.width, image.height)) {
            "Raster fill image is too large: ${image.width}x${image.height}"
        }
        val output = ByteArrayOutputStream()
        check(ImageIO.write(image, "png", output)) { "No PNG writer available" }
        require(output.size() <= MAX_ENCODED_PNG_BYTES) {
            "Raster fill PNG is too large: ${output.size()} bytes"
        }
        return Base64.getEncoder().encodeToString(output.toByteArray())
    }

    fun decodePngBase64(
        pngBase64: String,
        expectedWidth: Int? = null,
        expectedHeight: Int? = null
    ): BufferedImage {
        require(isSupportedEncodedText(pngBase64)) {
            "Raster fill PNG payload is too large"
        }
        val bytes = Base64.getDecoder().decode(pngBase64)
        require(bytes.size <= MAX_ENCODED_PNG_BYTES) {
            "Raster fill PNG is too large: ${bytes.size} bytes"
        }

        val imageInput = ImageIO.createImageInputStream(ByteArrayInputStream(bytes))
            ?: error("Could not create PNG image input")
        imageInput.use { input ->
            val readers = ImageIO.getImageReaders(input)
            require(readers.hasNext()) { "Could not decode raster fill PNG" }
            val reader = readers.next()
            try {
                reader.input = input
                validateDimensions(
                    width = reader.getWidth(0),
                    height = reader.getHeight(0),
                    expectedWidth = expectedWidth,
                    expectedHeight = expectedHeight
                )
                return reader.read(0) ?: error("Could not decode raster fill PNG")
            } finally {
                reader.dispose()
            }
        }
    }

    fun isSupportedPersistedRasterFill(width: Int, height: Int, pngBase64: String): Boolean {
        return isSupportedDimensions(width, height) && isSupportedEncodedText(pngBase64)
    }

    private fun validateDimensions(
        width: Int,
        height: Int,
        expectedWidth: Int?,
        expectedHeight: Int?
    ) {
        require(isSupportedDimensions(width, height)) {
            "Raster fill image is too large: ${width}x$height"
        }
        if (expectedWidth != null) {
            require(width == expectedWidth) {
                "Raster fill PNG width mismatch: expected $expectedWidth but was $width"
            }
        }
        if (expectedHeight != null) {
            require(height == expectedHeight) {
                "Raster fill PNG height mismatch: expected $expectedHeight but was $height"
            }
        }
    }

    private fun isSupportedDimensions(width: Int, height: Int): Boolean {
        if (width <= 0 || height <= 0) return false
        if (width > MAX_RASTER_FILL_SIDE_PX || height > MAX_RASTER_FILL_SIDE_PX) return false
        return width.toLong() * height.toLong() <= MAX_RASTER_FILL_PIXELS
    }

    private fun isSupportedEncodedText(pngBase64: String): Boolean {
        if (pngBase64.isBlank()) return false
        val estimatedBytes = (pngBase64.length.toLong() * 3L) / 4L
        if (estimatedBytes > MAX_ENCODED_PNG_BYTES) return false
        return runCatching {
            Base64.getDecoder().decode(pngBase64).size <= MAX_ENCODED_PNG_BYTES
        }.getOrDefault(false)
    }
}
