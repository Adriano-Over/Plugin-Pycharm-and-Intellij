package com.floatbar

import java.awt.Color
import java.awt.Point
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

object ShapeStrokeFactory {
    fun buildShapeStroke(
        start: Point,
        end: Point,
        kind: ShapeKind,
        constrain: Boolean,
        color: Color,
        width: Float,
        shapeEdgeSpacing: Double,
        ellipseSegments: Int,
        toAnchor: (Point) -> AnchorPoint?
    ): StrokePath {
        val adjusted = if (constrain) constrainPoint(start, end, kind) else end
        val viewPoints = when (kind) {
            ShapeKind.RECTANGLE, ShapeKind.PROCESS -> rectanglePoints(start, adjusted, shapeEdgeSpacing)
            ShapeKind.ELLIPSE, ShapeKind.CONNECTOR -> ellipsePoints(start, adjusted, ellipseSegments)
            ShapeKind.LINE -> linePoints(start, adjusted, shapeEdgeSpacing)
            ShapeKind.ARROW -> arrowPoints(start, adjusted, shapeEdgeSpacing)
            ShapeKind.DECISION -> diamondPoints(start, adjusted, shapeEdgeSpacing)
            ShapeKind.START_END -> roundedRectApproxPoints(start, adjusted, shapeEdgeSpacing)
            ShapeKind.INPUT_OUTPUT -> parallelogramPoints(start, adjusted, shapeEdgeSpacing)
            ShapeKind.DOCUMENT -> documentPoints(start, adjusted, shapeEdgeSpacing)
        }

        val anchors = viewPoints.mapNotNull(toAnchor).toMutableList()
        return StrokePath(
            color = color,
            width = width,
            points = anchors,
            filled = false,
            kind = kind
        )
    }

    private fun constrainPoint(start: Point, end: Point, kind: ShapeKind): Point {
        return when (kind) {
            ShapeKind.LINE, ShapeKind.ARROW -> {
                val dx = end.x - start.x
                val dy = end.y - start.y
                val angle = Math.atan2(dy.toDouble(), dx.toDouble())
                val step = PI / 4.0
                val snapped = kotlin.math.round(angle / step) * step
                val length = hypot(dx.toDouble(), dy.toDouble())

                Point(
                    (start.x + cos(snapped) * length).roundToInt(),
                    (start.y + sin(snapped) * length).roundToInt()
                )
            }

            ShapeKind.RECTANGLE,
            ShapeKind.ELLIPSE,
            ShapeKind.PROCESS,
            ShapeKind.CONNECTOR,
            ShapeKind.DECISION,
            ShapeKind.START_END -> {
                val size = min(abs(end.x - start.x), abs(end.y - start.y))
                Point(
                    start.x + if (end.x >= start.x) size else -size,
                    start.y + if (end.y >= start.y) size else -size
                )
            }

            else -> end
        }
    }

    private fun rectanglePoints(a: Point, b: Point, spacing: Double): List<Point> {
        val left = min(a.x, b.x)
        val right = max(a.x, b.x)
        val top = min(a.y, b.y)
        val bottom = max(a.y, b.y)
        return polyline(
            spacing,
            Point(left, top),
            Point(right, top),
            Point(right, bottom),
            Point(left, bottom),
            Point(left, top)
        )
    }

    private fun diamondPoints(a: Point, b: Point, spacing: Double): List<Point> {
        val left = min(a.x, b.x)
        val right = max(a.x, b.x)
        val top = min(a.y, b.y)
        val bottom = max(a.y, b.y)
        val cx = (left + right) / 2
        val cy = (top + bottom) / 2
        return polyline(
            spacing,
            Point(cx, top),
            Point(right, cy),
            Point(cx, bottom),
            Point(left, cy),
            Point(cx, top)
        )
    }

    private fun parallelogramPoints(a: Point, b: Point, spacing: Double): List<Point> {
        val left = min(a.x, b.x)
        val right = max(a.x, b.x)
        val top = min(a.y, b.y)
        val bottom = max(a.y, b.y)
        val slant = max(10, (right - left) / 6)
        return polyline(
            spacing,
            Point(left + slant, top),
            Point(right, top),
            Point(right - slant, bottom),
            Point(left, bottom),
            Point(left + slant, top)
        )
    }

    private fun documentPoints(a: Point, b: Point, spacing: Double): List<Point> {
        val left = min(a.x, b.x)
        val right = max(a.x, b.x)
        val top = min(a.y, b.y)
        val bottom = max(a.y, b.y)
        val wave = max(8, (bottom - top) / 7)
        return polyline(
            spacing,
            Point(left, top),
            Point(right, top),
            Point(right, bottom - wave),
            Point((left + right * 2) / 3, bottom),
            Point((left * 2 + right) / 3, bottom - wave / 2),
            Point(left, bottom - wave),
            Point(left, top)
        )
    }

    private fun roundedRectApproxPoints(a: Point, b: Point, spacing: Double): List<Point> {
        val left = min(a.x, b.x)
        val right = max(a.x, b.x)
        val top = min(a.y, b.y)
        val bottom = max(a.y, b.y)
        val r = max(8, min((right - left) / 4, (bottom - top) / 2))
        return polyline(
            spacing,
            Point(left + r, top),
            Point(right - r, top),
            Point(right, top + r),
            Point(right, bottom - r),
            Point(right - r, bottom),
            Point(left + r, bottom),
            Point(left, bottom - r),
            Point(left, top + r),
            Point(left + r, top)
        )
    }

    private fun ellipsePoints(a: Point, b: Point, ellipseSegments: Int): List<Point> {
        val left = min(a.x, b.x)
        val right = max(a.x, b.x)
        val top = min(a.y, b.y)
        val bottom = max(a.y, b.y)
        val cx = (left + right) / 2.0
        val cy = (top + bottom) / 2.0
        val rx = max(1.0, (right - left) / 2.0)
        val ry = max(1.0, (bottom - top) / 2.0)

        return (0..ellipseSegments).map { i ->
            val t = (PI * 2.0) * i / ellipseSegments.toDouble()
            Point(
                (cx + cos(t) * rx).roundToInt(),
                (cy + sin(t) * ry).roundToInt()
            )
        }
    }

    private fun linePoints(a: Point, b: Point, spacing: Double): List<Point> = interpolateLine(a, b, spacing)

    private fun arrowPoints(a: Point, b: Point, spacing: Double): List<Point> {
        val dx = (b.x - a.x).toDouble()
        val dy = (b.y - a.y).toDouble()
        val length = max(1.0, hypot(dx, dy))
        val ux = dx / length
        val uy = dy / length
        val head = min(18.0, length / 3.0)
        val wingX = -uy
        val wingY = ux

        val tipLeft = Point(
            (b.x - ux * head + wingX * head * 0.6).roundToInt(),
            (b.y - uy * head + wingY * head * 0.6).roundToInt()
        )

        val tipRight = Point(
            (b.x - ux * head - wingX * head * 0.6).roundToInt(),
            (b.y - uy * head - wingY * head * 0.6).roundToInt()
        )

        val shaftEnd = Point(
            (b.x - ux * (head * 0.35)).roundToInt(),
            (b.y - uy * (head * 0.35)).roundToInt()
        )

        return polyline(
            spacing,
            *interpolateLine(a, shaftEnd, spacing).toTypedArray(),
            *interpolateLine(tipLeft, b, spacing).toTypedArray(),
            *interpolateLine(b, tipRight, spacing).toTypedArray()
        )
    }

    private fun polyline(spacing: Double, vararg points: Point): List<Point> {
        if (points.isEmpty()) return emptyList()
        val result = mutableListOf<Point>()
        for (i in 0 until points.lastIndex) {
            val segment = interpolateLine(points[i], points[i + 1], spacing)
            if (result.isNotEmpty() && segment.isNotEmpty()) {
                result.removeAt(result.lastIndex)
            }
            result += segment
        }
        return result
    }

    private fun interpolateLine(a: Point, b: Point, spacing: Double): List<Point> {
        val distance = a.distance(b)
        val steps = max(1, ceil(distance / spacing).toInt())

        return (0..steps).map { i ->
            val t = i.toDouble() / steps
            Point(
                (a.x + (b.x - a.x) * t).roundToInt(),
                (a.y + (b.y - a.y) * t).roundToInt()
            )
        }
    }
}
