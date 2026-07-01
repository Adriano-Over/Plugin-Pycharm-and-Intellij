package com.drawing

data class PaintPerformanceStats(
    var paintMs: Long = 0,
    var strokesInspected: Int = 0,
    var strokesPainted: Int = 0,
    var rasterFillsInspected: Int = 0,
    var rasterFillsPainted: Int = 0,
    var annotationsInspected: Int = 0,
    var annotationsPainted: Int = 0,
    var geometryCacheHits: Int = 0,
    var geometryCacheMisses: Int = 0,
    var rasterFillImageCacheHits: Int = 0,
    var rasterFillImageCacheMisses: Int = 0,
    var annotationImageCacheHits: Int = 0,
    var annotationImageCacheMisses: Int = 0,
    var boundsCacheHits: Int = 0,
    var boundsCacheMisses: Int = 0
)

object DrawingPerformanceDiagnostics {
    private val enabled = System.getProperty("drawing.performance.debug") == "true" ||
        System.getenv("DRAWING_PERFORMANCE_DEBUG") == "true"
    private val activePaintStats = ThreadLocal<PaintPerformanceStats?>()

    fun beginPaint(stats: PaintPerformanceStats) {
        activePaintStats.set(stats)
    }

    fun endPaint() {
        activePaintStats.remove()
    }

    fun recordGeometryCacheHit() {
        activePaintStats.get()?.let { it.geometryCacheHits += 1 }
    }

    fun recordGeometryCacheMiss() {
        activePaintStats.get()?.let { it.geometryCacheMisses += 1 }
    }

    fun recordRasterFillImageCacheHit() {
        activePaintStats.get()?.let { it.rasterFillImageCacheHits += 1 }
    }

    fun recordRasterFillImageCacheMiss() {
        activePaintStats.get()?.let { it.rasterFillImageCacheMisses += 1 }
    }

    fun recordAnnotationImageCacheHit() {
        activePaintStats.get()?.let { it.annotationImageCacheHits += 1 }
    }

    fun recordAnnotationImageCacheMiss() {
        activePaintStats.get()?.let { it.annotationImageCacheMisses += 1 }
    }

    fun recordBoundsCacheHit() {
        activePaintStats.get()?.let { it.boundsCacheHits += 1 }
    }

    fun recordBoundsCacheMiss() {
        activePaintStats.get()?.let { it.boundsCacheMisses += 1 }
    }

    fun logSlowPaint(stats: PaintPerformanceStats, thresholdMs: Long = 32L) {
        if (!enabled || stats.paintMs < thresholdMs) return
        DrawingDebugLog.throttled(
            key = "slow-paint",
            throttleMs = 1000L,
            message =
            "paintMs=${stats.paintMs} " +
                "strokes=${stats.strokesPainted}/${stats.strokesInspected} " +
                "fills=${stats.rasterFillsPainted}/${stats.rasterFillsInspected} " +
                "annotations=${stats.annotationsPainted}/${stats.annotationsInspected} " +
                "geometryCache=${stats.geometryCacheHits}/${stats.geometryCacheMisses} " +
                "rasterImageCache=${stats.rasterFillImageCacheHits}/${stats.rasterFillImageCacheMisses} " +
                "annotationImageCache=${stats.annotationImageCacheHits}/${stats.annotationImageCacheMisses} " +
                "boundsCache=${stats.boundsCacheHits}/${stats.boundsCacheMisses}"
        )
    }
}
