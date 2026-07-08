package com.drawing

import java.awt.image.BufferedImage

private const val MAX_CACHED_RASTER_FILL_IMAGES = 256

internal class RasterFillImageCache {
    private data class CacheKey(val id: Long, val pngHash: Int)

    private val decodedByKey = object : LinkedHashMap<CacheKey, BufferedImage>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CacheKey, BufferedImage>?): Boolean {
            return size > MAX_CACHED_RASTER_FILL_IMAGES
        }
    }

    fun get(fill: RasterFillPath): BufferedImage {
        val key = CacheKey(fill.id, fill.pngBase64.hashCode())
        decodedByKey[key]?.let { image ->
            DrawingPerformanceDiagnostics.recordRasterFillImageCacheHit()
            return image
        }
        DrawingPerformanceDiagnostics.recordRasterFillImageCacheMiss()
        return RasterFillCodec.decodePngBase64(
            pngBase64 = fill.pngBase64,
            expectedWidth = fill.width,
            expectedHeight = fill.height
        ).also { image ->
            decodedByKey[key] = image
        }
    }

    fun invalidate(fillId: Long) {
        decodedByKey.keys.removeIf { it.id == fillId }
    }

    fun clear() {
        decodedByKey.clear()
    }
}
