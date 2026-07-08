package com.drawing

import java.awt.Point
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import javax.swing.SwingUtilities

class DrawingInputController(
    private val currentToolProvider: () -> DrawingToolMode,
    private val interactionPassThroughEnabledProvider: () -> Boolean,
    private val clampPoint: (Point, DrawingToolMode) -> Point?,
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
    private val onPassthroughMouseEvent: (MouseEvent) -> Unit,
    private val onTextEditorOutsidePressed: (Point) -> Boolean = { false }
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
        if (onTextEditorOutsidePressed(e.point)) {
            shapeStartPoint = null
            lastDragPoint = null
            onToolPreviewPointChanged(null)
            onDrawGestureFinished()
            e.consume()
            return
        }

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
        if (currentTool == DrawingToolMode.DRAW) {
            onDrawGestureStarted(e.point)
        } else {
            onDrawGestureFinished()
        }

        val safePoint = clampPoint(e.point, currentTool) ?: run {
            DrawingDiagnosticLog.warn("INPUT", "mousePressed clamp rejected raw=${e.point} tool=$currentTool")
            onToolPreviewPointChanged(null)
            return
        }
        onToolPreviewPointChanged(safePoint)

        when (currentTool) {
            DrawingToolMode.SELECT -> {
                onSelectPressed(safePoint)
                lastDragPoint = safePoint
            }

            DrawingToolMode.FILL -> {
                onFillPressed(safePoint)
                lastDragPoint = null
            }

            DrawingToolMode.ERASE -> {
                onErasePressed(safePoint)
                lastDragPoint = safePoint
            }

            DrawingToolMode.SHAPES -> {
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
            DrawingToolMode.SELECT -> {
                onSelectDragged(lastDragPoint, safePoint)
                lastDragPoint = safePoint
            }

            DrawingToolMode.FILL -> return

            DrawingToolMode.ERASE -> {
                onEraseDragged(lastDragPoint, safePoint)
                lastDragPoint = safePoint
            }

            DrawingToolMode.SHAPES -> {
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
            DrawingToolMode.SELECT -> {
                lastDragPoint = null
                onSelectReleased()
            }

            DrawingToolMode.ERASE -> {
                lastDragPoint = null
                onEraseReleased()
            }

            DrawingToolMode.SHAPES -> {
                shapeStartPoint = null
                lastDragPoint = null
                onShapeReleased()
            }

            DrawingToolMode.FILL -> {
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
