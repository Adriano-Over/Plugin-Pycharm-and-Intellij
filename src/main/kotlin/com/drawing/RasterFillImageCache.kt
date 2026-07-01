package com.drawing

import java.awt.image.BufferedImage

internal class RasterFillImageCache {
    private data class CacheKey(val id: Long, val pngHash: Int)

    private val decodedByKey = mutableMapOf<CacheKey, BufferedImage>()

    fun get(fill: RasterFillPath): BufferedImage {
        val key = CacheKey(fill.id, fill.pngBase64.hashCode())
        return decodedByKey.getOrPut(key) {
            RasterFillCodec.decodePngBase64(fill.pngBase64)
        }
    }

    fun clear() {
        decodedByKey.clear()
    }
}

