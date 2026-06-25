package com.floatbar

import java.awt.Point
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import javax.swing.SwingUtilities

class DrawingInputController(
    private val currentToolProvider: () -> FloatBarToolMode,
    private val interactionPassThroughEnabledProvider: () -> Boolean,
    private val clampPoint: (Point, FloatBarToolMode) -> Point?,
    private val onToolPreviewPointChanged: (Point?) -> Unit,
    private val onSelectPressed: (Point) -> Unit,
    private val onSelectDragged: (Point?, Point) -> Unit,
    private val onSelectReleased: () -> Unit,
    private val onFillPressed: (Point) -> Unit,
    private val onErasePressed: (Point) -> Unit,
    private val onEraseDragged: (Point?, Point) -> Unit,
    private val onEraseReleased: () -> Unit,
    private val onShapePressed: (Point) -> Unit,
    private val onShapeDragged: (Point, Point, Boolean) -> Unit,
    private val onShapeReleased: () -> Unit,
    private val onDrawPressed: (Point) -> Unit,
    private val onDrawDragged: (Point?, Point) -> Unit,
    private val onDrawReleased: () -> Unit,
    private val onDrawGestureStarted: (Point) -> Unit,
    private val onDrawGestureFinished: () -> Unit,
    private val onMouseWheel: (MouseWheelEvent) -> Unit,
    private val onPassthroughMouseEvent: (MouseEvent) -> Unit
) : MouseAdapter() {

    private var shapeStartPoint: Point? = null
    private var lastDragPoint: Point? = null

    private fun isPassThroughEnabled(): Boolean = interactionPassThroughEnabledProvider()

    private fun passThrough(event: MouseEvent) {
        onToolPreviewPointChanged(null)
        onDrawGestureFinished()
        onPassthroughMouseEvent(event)
    }

    override fun mousePressed(e: MouseEvent) {
        if (isPassThroughEnabled()) {
            passThrough(e)
            return
        }
        if (!SwingUtilities.isLeftMouseButton(e)) {
            onPassthroughMouseEvent(e)
            return
        }
        e.consume()

        val currentTool = currentToolProvider()
        if (currentTool == FloatBarToolMode.DRAW) {
            onDrawGestureStarted(e.point)
        } else {
            onDrawGestureFinished()
        }

        val safePoint = clampPoint(e.point, currentTool) ?: run {
            FloatBarDiagnosticLog.warn("INPUT", "mousePressed clamp rejected raw=${e.point} tool=$currentTool")
            onToolPreviewPointChanged(null)
            return
        }
        onToolPreviewPointChanged(safePoint)

        when (currentTool) {
            FloatBarToolMode.SELECT -> {
                onSelectPressed(safePoint)
                lastDragPoint = safePoint
            }

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
        if (isPassThroughEnabled()) {
            passThrough(e)
            return
        }
        if ((e.modifiersEx and MouseEvent.BUTTON1_DOWN_MASK) == 0) {
            onPassthroughMouseEvent(e)
            return
        }
        e.consume()

        val currentTool = currentToolProvider()

        val safePoint = clampPoint(e.point, currentTool) ?: run {
            onToolPreviewPointChanged(null)
            return
        }
        onToolPreviewPointChanged(safePoint)

        when (currentTool) {
            FloatBarToolMode.SELECT -> {
                onSelectDragged(lastDragPoint, safePoint)
                lastDragPoint = safePoint
            }

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
        if (isPassThroughEnabled()) {
            passThrough(e)
            return
        }
        if (!SwingUtilities.isLeftMouseButton(e)) {
            onPassthroughMouseEvent(e)
            return
        }
        e.consume()

        val currentTool = currentToolProvider()
        val releaseSafePoint = clampPoint(e.point, currentTool)
        onToolPreviewPointChanged(releaseSafePoint)

        when (currentTool) {
            FloatBarToolMode.SELECT -> {
                lastDragPoint = null
                onSelectReleased()
            }

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
                onDrawGestureFinished()
            }
        }
    }

    override fun mouseMoved(e: MouseEvent) {
        if (isPassThroughEnabled()) {
            passThrough(e)
            return
        }
        onToolPreviewPointChanged(clampPoint(e.point, currentToolProvider()))
    }

    override fun mouseClicked(e: MouseEvent) {
        if (isPassThroughEnabled()) {
            passThrough(e)
            return
        }
        if (!SwingUtilities.isLeftMouseButton(e)) {
            onPassthroughMouseEvent(e)
        }
    }

    override fun mouseWheelMoved(e: MouseWheelEvent) {
        onMouseWheel(e)
    }

    override fun mouseExited(e: MouseEvent) {
        if (isPassThroughEnabled()) {
            passThrough(e)
            return
        }
        onToolPreviewPointChanged(null)
        onDrawGestureFinished()
    }
}
