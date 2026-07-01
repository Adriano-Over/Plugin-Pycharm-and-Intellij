package com.drawing

import java.awt.Point
import java.awt.Rectangle
import javax.swing.JPanel
import javax.swing.SwingUtilities

class DrawingDirtyRepaintScheduler(
    private val canvas: JPanel
) {
    private var repaintPending = false
    private var pendingDirtyBounds: Rectangle? = null

    fun repaintDirty(bounds: Rectangle?, padding: Int = 0, coalesce: Boolean = false) {
        if (bounds == null) {
            canvas.repaint()
            return
        }

        val dirty = Rectangle(bounds)
        dirty.grow(padding, padding)
        if (coalesce) {
            scheduleDirtyRepaint(dirty)
        } else {
            repaintNow(dirty)
        }
    }

    fun repaintAround(points: List<Point>, padding: Int, coalesce: Boolean = false) {
        repaintDirty(boundsFor(points), padding, coalesce)
    }

    private fun scheduleDirtyRepaint(bounds: Rectangle) {
        pendingDirtyBounds = pendingDirtyBounds?.apply { add(bounds) } ?: Rectangle(bounds)
        if (repaintPending) return

        repaintPending = true
        SwingUtilities.invokeLater {
            repaintPending = false
            val dirty = pendingDirtyBounds
            pendingDirtyBounds = null
            if (dirty == null) {
                canvas.repaint()
            } else {
                repaintNow(dirty)
            }
        }
    }

    private fun repaintNow(bounds: Rectangle) {
        canvas.repaint(bounds.x, bounds.y, bounds.width.coerceAtLeast(1), bounds.height.coerceAtLeast(1))
    }

    private fun boundsFor(points: List<Point>): Rectangle? {
        if (points.isEmpty()) return null
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var maxY = Int.MIN_VALUE
        for (point in points) {
            minX = minOf(minX, point.x)
            minY = minOf(minY, point.y)
            maxX = maxOf(maxX, point.x)
            maxY = maxOf(maxY, point.y)
        }
        if (minX == Int.MAX_VALUE) return null
        return Rectangle(minX, minY, (maxX - minX).coerceAtLeast(1), (maxY - minY).coerceAtLeast(1))
    }
}
