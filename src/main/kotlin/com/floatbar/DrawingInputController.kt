package com.floatbar

import java.awt.Point
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent

class DrawingInputController(
    private val currentToolProvider: () -> FloatBarToolMode,
    private val clampPoint: (Point) -> Point?,
    private val onFillPressed: (Point) -> Unit,
    private val onErasePressed: (Point) -> Unit,
    private val onEraseDragged: (Point?, Point) -> Unit,
    private val onEraseReleased: () -> Unit,
    private val onShapePressed: (Point) -> Unit,
    private val onShapeDragged: (Point, Point, Boolean) -> Unit,
    private val onShapeReleased: () -> Unit,
    private val onDrawPressed: (Point) -> Unit,
    private val onDrawDragged: (Point?, Point) -> Unit,
    private val onDrawReleased: () -> Unit
) : MouseAdapter() {

    private var shapeStartPoint: Point? = null
    private var lastDragPoint: Point? = null

    override fun mousePressed(e: MouseEvent) {
        val safePoint = clampPoint(e.point) ?: return
        when (currentToolProvider()) {
            FloatBarToolMode.FILL -> {
                onFillPressed(safePoint)
                lastDragPoint = null
            }

            FloatBarToolMode.ERASE -> {
                onErasePressed(safePoint)
                lastDragPoint = safePoint
            }

            FloatBarToolMode.SHAPES -> {
                onShapePressed(safePoint)
                shapeStartPoint = safePoint
                lastDragPoint = safePoint
            }

            else -> {
                onDrawPressed(safePoint)
                lastDragPoint = safePoint
            }
        }
    }

    override fun mouseDragged(e: MouseEvent) {
        val safePoint = clampPoint(e.point) ?: return
        when (currentToolProvider()) {
            FloatBarToolMode.FILL -> return

            FloatBarToolMode.ERASE -> {
                onEraseDragged(lastDragPoint, safePoint)
                lastDragPoint = safePoint
            }

            FloatBarToolMode.SHAPES -> {
                val start = shapeStartPoint ?: return
                onShapeDragged(start, safePoint, e.isShiftDown)
                lastDragPoint = safePoint
            }

            else -> {
                onDrawDragged(lastDragPoint, safePoint)
                lastDragPoint = safePoint
            }
        }
    }

    override fun mouseReleased(e: MouseEvent) {
        when (currentToolProvider()) {
            FloatBarToolMode.ERASE -> {
                lastDragPoint = null
                onEraseReleased()
            }

            FloatBarToolMode.SHAPES -> {
                shapeStartPoint = null
                lastDragPoint = null
                onShapeReleased()
            }

            FloatBarToolMode.FILL -> {
                lastDragPoint = null
            }

            else -> {
                lastDragPoint = null
                onDrawReleased()
            }
        }
    }
}
