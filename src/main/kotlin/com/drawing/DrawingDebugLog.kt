package com.drawing

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import java.awt.Point
import java.awt.Rectangle
import java.util.concurrent.ConcurrentHashMap

object DrawingDebugLog {
    private val log = Logger.getInstance("Drawing")
    private val lastLogByKey = ConcurrentHashMap<String, Long>()

    private const val DEFAULT_THROTTLE_MS = 500L

    fun info(message: String) {
        log.info("[Drawing] $message")
    }

    fun warn(message: String, throwable: Throwable? = null) {
        if (throwable == null) {
            log.warn("[Drawing] $message")
        } else {
            log.warn("[Drawing] $message", throwable)
        }
    }

    fun throttled(key: String, message: String, throttleMs: Long = DEFAULT_THROTTLE_MS) {
        val now = System.currentTimeMillis()
        val previous = lastLogByKey[key]
        if (previous == null || now - previous >= throttleMs) {
            lastLogByKey[key] = now
            info(message)
        }
    }

    inline fun <T> timed(operation: String, warnAfterMs: Long = 80L, block: () -> T): T {
        val start = System.nanoTime()
        try {
            return block()
        } finally {
            val elapsedMs = (System.nanoTime() - start) / 1_000_000
            if (elapsedMs >= warnAfterMs) {
                warn("SLOW $operation took ${elapsedMs}ms")
            }
        }
    }

    fun describeEditor(editor: Editor?): String {
        if (editor == null) return "editor=null"
        val document = editor.document
        val visibleArea = runCatching { editor.scrollingModel.visibleArea }.getOrNull()
        return "document=${describeDocument(document)}, visibleArea=${describeRect(visibleArea)}, lineHeight=${editor.lineHeight}"
    }

    fun describeDocument(document: Document?): String {
        if (document == null) return "document=null"
        return "doc@${System.identityHashCode(document)}, lines=${document.lineCount}, textLength=${document.textLength}"
    }

    fun describeStroke(stroke: StrokePath?): String {
        if (stroke == null) return "stroke=null"
        val first = stroke.points.firstOrNull()
        val last = stroke.points.lastOrNull()
        return "strokeId=${stroke.id}, kind=${stroke.kind ?: "FREEHAND"}, points=${stroke.points.size}, first=${describeAnchor(first)}, last=${describeAnchor(last)}"
    }

    fun describePoint(point: Point?): String {
        return if (point == null) "null" else "(${point.x},${point.y})"
    }

    fun describeRect(rectangle: Rectangle?): String {
        return if (rectangle == null) {
            "null"
        } else {
            "(${rectangle.x},${rectangle.y},${rectangle.width}x${rectangle.height})"
        }
    }

    private fun describeAnchor(anchor: AnchorPoint?): String {
        if (anchor == null) return "null"
        return "line=${anchor.line}, col=${anchor.column}, dx=${anchor.dx}, dy=${anchor.dy}, offset=${anchor.offset}, outside=${anchor.outsideCode}"
    }
}
