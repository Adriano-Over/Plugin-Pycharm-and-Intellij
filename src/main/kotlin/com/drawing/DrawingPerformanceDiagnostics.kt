package com.drawing

data class PaintPerformanceStats(
    var paintMs: Long = 0,
    var strokesInspected: Int = 0,
    var strokesPainted: Int = 0,
    var rasterFillsInspected: Int = 0,
    var rasterFillsPainted: Int = 0,
    var annotationsInspected: Int = 0,
    var annotationsPainted: Int = 0
)

object DrawingPerformanceDiagnostics {
    private val enabled = System.getProperty("drawing.performance.debug") == "true" ||
        System.getenv("DRAWING_PERFORMANCE_DEBUG") == "true"

    fun logSlowPaint(stats: PaintPerformanceStats, thresholdMs: Long = 32L) {
        if (!enabled || stats.paintMs < thresholdMs) return
        DrawingDebugLog.throttled(
            key = "slow-paint",
            throttleMs = 1000L,
            message =
            "paintMs=${stats.paintMs} " +
                "strokes=${stats.strokesPainted}/${stats.strokesInspected} " +
                "fills=${stats.rasterFillsPainted}/${stats.rasterFillsInspected} " +
                "annotations=${stats.annotationsPainted}/${stats.annotationsInspected}"
        )
    }
}
