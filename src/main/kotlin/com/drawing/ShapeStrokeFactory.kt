package com.drawing

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
            ShapeKind.RECTANGLE -> rectanglePoints(start, adjusted, shapeEdgeSpacing)
            ShapeKind.PROCESS -> processPoints(start, adjusted, shapeEdgeSpacing)
            ShapeKind.PREDEFINED_PROCESS -> predefinedProcessPoints(start, adjusted, shapeEdgeSpacing)
            ShapeKind.ELLIPSE, ShapeKind.CONNECTOR -> ellipsePoints(start, adjusted, ellipseSegments)
            ShapeKind.LINE -> linePoints(start, adjusted, shapeEdgeSpacing)
            ShapeKind.ARROW -> arrowPoints(start, adjusted, shapeEdgeSpacing)
            ShapeKind.DECISION -> roundedDiamondPoints(start, adjusted, shapeEdgeSpacing)
            ShapeKind.START_END -> terminatorPoints(start, adjusted, shapeEdgeSpacing)
            ShapeKind.INPUT_OUTPUT -> parallelogramPoints(start, adjusted, shapeEdgeSpacing)
            ShapeKind.DOCUMENT -> documentPoints(start, adjusted, shapeEdgeSpacing)
            ShapeKind.MULTIPLE_DOCUMENTS -> multipleDocumentPoints(start, adjusted, shapeEdgeSpacing)
            ShapeKind.STORED_DATA -> storedDataPoints(start, adjusted, shapeEdgeSpacing)
            ShapeKind.MANUAL_OPERATION -> manualOperationPoints(start, adjusted, shapeEdgeSpacing)
            ShapeKind.BALLOON -> balloonPoints(start, adjusted, shapeEdgeSpacing)
            ShapeKind.TEXT -> rectanglePoints(start, adjusted, shapeEdgeSpacing)
            ShapeKind.RIGHT_BRACE -> rightBracePoints(start, adjusted, shapeEdgeSpacing)
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
            ShapeKind.PREDEFINED_PROCESS,
            ShapeKind.CONNECTOR,
            ShapeKind.DECISION,
            ShapeKind.START_END,
            ShapeKind.MULTIPLE_DOCUMENTS,
            ShapeKind.STORED_DATA,
            ShapeKind.MANUAL_OPERATION,
            ShapeKind.BALLOON,
            ShapeKind.TEXT -> {
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

    private fun processPoints(a: Point, b: Point, spacing: Double): List<Point> {
        val left = min(a.x, b.x)
        val right = max(a.x, b.x)
        val top = min(a.y, b.y)
        val bottom = max(a.y, b.y)
        val radius = min(20, max(8, min(right - left, bottom - top) / 10))
        return roundedRectPointsForBounds(left, right, top, bottom, radius, spacing)
    }

    private fun predefinedProcessPoints(a: Point, b: Point, spacing: Double): List<Point> {
        val left = min(a.x, b.x)
        val right = max(a.x, b.x)
        val top = min(a.y, b.y)
        val bottom = max(a.y, b.y)
        val width = (right - left).coerceAtLeast(1)
        val height = (bottom - top).coerceAtLeast(1)
        val radius = min(18, max(6, min(width, height) / 12))
        val barInset = max(radius + 4, width / 11).coerceAtMost(width / 3)
        val leftBarX = left + barInset
        val rightBarX = right - barInset
        val topBarY = top + radius / 2
        val bottomBarY = bottom - radius / 2

        return polyline(
            spacing,
            *roundedRectPointsForBounds(left, right, top, bottom, radius, spacing).toTypedArray(),
            Point(leftBarX, top),
            Point(leftBarX, bottom),
            Point(leftBarX, topBarY),
            Point(rightBarX, topBarY),
            Point(rightBarX, bottomBarY),
            Point(rightBarX, top)
        )
    }

    private fun roundedDiamondPoints(a: Point, b: Point, spacing: Double): List<Point> {
        val left = min(a.x, b.x)
        val right = max(a.x, b.x)
        val top = min(a.y, b.y)
        val bottom = max(a.y, b.y)
        val cx = (left + right) / 2
        val cy = (top + bottom) / 2
        val cornerRatio = 0.14
        val topPoint = Point(cx, top)
        val rightPoint = Point(right, cy)
        val bottomPoint = Point(cx, bottom)
        val leftPoint = Point(left, cy)
        return roundedPolygonPoints(
            listOf(topPoint, rightPoint, bottomPoint, leftPoint),
            cornerRatio,
            spacing
        )
    }

    private fun parallelogramPoints(a: Point, b: Point, spacing: Double): List<Point> {
        val left = min(a.x, b.x)
        val right = max(a.x, b.x)
        val top = min(a.y, b.y)
        val bottom = max(a.y, b.y)
        val width = (right - left).coerceAtLeast(1)
        val slant = max(6, width / 6).coerceAtMost(max(6, width / 3))
        return roundedPolygonPoints(
            listOf(
                Point(left + slant, top),
                Point(right, top),
                Point(right - slant, bottom),
                Point(left, bottom)
            ),
            cornerRatio = 0.08,
            spacing = spacing
        )
    }

    private fun documentPoints(a: Point, b: Point, spacing: Double): List<Point> {
        val left = min(a.x, b.x)
        val right = max(a.x, b.x)
        val top = min(a.y, b.y)
        val bottom = max(a.y, b.y)
        return documentOutlinePoints(left, right, top, bottom, spacing)
    }

    private fun documentOutlinePoints(left: Int, right: Int, top: Int, bottom: Int, spacing: Double): List<Point> {
        val width = (right - left).coerceAtLeast(1)
        val height = (bottom - top).coerceAtLeast(1)
        val wave = max(8, height / 6)
        val waveTopY = bottom - wave
        val centerX = (left + right) / 2
        return polyline(
            spacing,
            Point(left, top),
            Point(right, top),
            Point(right, waveTopY),
            *cubicBezierPoints(
                start = Point(right, waveTopY),
                control1 = Point(right - width / 4, bottom),
                control2 = Point(centerX + width / 6, bottom),
                end = Point(centerX, bottom),
                spacing = spacing
            ).toTypedArray(),
            *cubicBezierPoints(
                start = Point(centerX, bottom),
                control1 = Point(centerX - width / 6, bottom),
                control2 = Point(left + width / 4, waveTopY - wave / 2),
                end = Point(left, waveTopY),
                spacing = spacing
            ).toTypedArray(),
            Point(left, top)
        )
    }

    private fun multipleDocumentPoints(a: Point, b: Point, spacing: Double): List<Point> {
        val left = min(a.x, b.x)
        val right = max(a.x, b.x)
        val top = min(a.y, b.y)
        val bottom = max(a.y, b.y)
        val width = (right - left).coerceAtLeast(1)
        val height = (bottom - top).coerceAtLeast(1)
        val maxOffset = (min(width, height) / 4).coerceAtLeast(1)
        val offset = max(6, min(width, height) / 10).coerceAtMost(maxOffset)
        val frontLeft = left + offset * 2
        val frontTop = top + offset * 2

        return polyline(
            spacing,
            Point(left, top),
            Point(right - offset * 2, top),
            Point(right - offset * 2, bottom - offset * 3),
            Point(right - offset, bottom - offset * 2),
            Point(right - offset, top + offset),
            Point(left + offset, top + offset),
            Point(left + offset, bottom - offset * 2),
            Point(frontLeft, bottom - offset),
            *documentOutlinePoints(frontLeft, right, frontTop, bottom, spacing).toTypedArray()
        )
    }

    private fun terminatorPoints(a: Point, b: Point, spacing: Double): List<Point> {
        val left = min(a.x, b.x)
        val right = max(a.x, b.x)
        val top = min(a.y, b.y)
        val bottom = max(a.y, b.y)
        val width = (right - left).coerceAtLeast(1)
        val height = (bottom - top).coerceAtLeast(1)
        val radius = min(width / 2, height / 2).coerceAtLeast(1)
        return roundedRectPointsForBounds(left, right, top, bottom, radius, spacing)
    }

    private fun roundedRectPointsForBounds(
        left: Int,
        right: Int,
        top: Int,
        bottom: Int,
        radius: Int,
        spacing: Double
    ): List<Point> {
        val safeRadius = radius.coerceAtLeast(1)
        return polyline(
            spacing,
            Point(left + safeRadius, top),
            Point(right - safeRadius, top),
            *arcPoints(right - safeRadius, top + safeRadius, safeRadius, -PI / 2.0, 0.0, spacing).toTypedArray(),
            Point(right, bottom - safeRadius),
            *arcPoints(right - safeRadius, bottom - safeRadius, safeRadius, 0.0, PI / 2.0, spacing).toTypedArray(),
            Point(left + safeRadius, bottom),
            *arcPoints(left + safeRadius, bottom - safeRadius, safeRadius, PI / 2.0, PI, spacing).toTypedArray(),
            Point(left, top + safeRadius),
            *arcPoints(left + safeRadius, top + safeRadius, safeRadius, PI, PI * 1.5, spacing).toTypedArray(),
            Point(left + safeRadius, top)
        )
    }

    private fun storedDataPoints(a: Point, b: Point, spacing: Double): List<Point> {
        val left = min(a.x, b.x)
        val right = max(a.x, b.x)
        val top = min(a.y, b.y)
        val bottom = max(a.y, b.y)
        val width = (right - left).coerceAtLeast(1)
        val curve = max(12, width / 5)
        val topLeft = Point(left + curve, top)
        val topRight = Point(right, top)
        val bottomRight = Point(right, bottom)
        val bottomLeft = Point(left + curve, bottom)

        return polyline(
            spacing,
            topLeft,
            topRight,
            *cubicBezierPoints(
                start = topRight,
                control1 = Point(right - curve, top + (bottom - top) / 3),
                control2 = Point(right - curve, bottom - (bottom - top) / 3),
                end = bottomRight,
                spacing = spacing
            ).toTypedArray(),
            bottomLeft,
            *cubicBezierPoints(
                start = bottomLeft,
                control1 = Point(left - curve / 2, bottom - (bottom - top) / 3),
                control2 = Point(left - curve / 2, top + (bottom - top) / 3),
                end = topLeft,
                spacing = spacing
            ).toTypedArray()
        )
    }

    private fun manualOperationPoints(a: Point, b: Point, spacing: Double): List<Point> {
        val left = min(a.x, b.x)
        val right = max(a.x, b.x)
        val top = min(a.y, b.y)
        val bottom = max(a.y, b.y)
        val width = (right - left).coerceAtLeast(1)
        val bottomInset = max(8, width / 7)
        return roundedPolygonPoints(
            listOf(
                Point(left, top),
                Point(right, top),
                Point(right - bottomInset, bottom),
                Point(left + bottomInset, bottom)
            ),
            cornerRatio = 0.06,
            spacing = spacing
        )
    }

    private fun balloonPoints(a: Point, b: Point, spacing: Double): List<Point> {
        val left = min(a.x, b.x)
        val right = max(a.x, b.x)
        val top = min(a.y, b.y)
        val bottom = max(a.y, b.y)
        val width = (right - left).coerceAtLeast(1)
        val height = (bottom - top).coerceAtLeast(1)
        val tailHeight = max(14, height / 4).coerceAtMost(max(14, height / 3))
        val bubbleBottom = (bottom - tailHeight).coerceAtLeast(top + 8)
        val bubbleHeight = (bubbleBottom - top).coerceAtLeast(1)
        val radius = min(min(width / 5, bubbleHeight / 3), 18)
            .coerceAtLeast(4)
            .coerceAtMost(min(width / 2, bubbleHeight / 2).coerceAtLeast(1))
        val tailCenterX = left + (width * 0.34).roundToInt()
        val tailHalf = max(7, width / 12).coerceAtMost(max(7, width / 4))
        val tailTip = Point(left + (width * 0.20).roundToInt(), bottom)

        return polyline(
            spacing,
            Point(left + radius, top),
            Point(right - radius, top),
            *arcPoints(right - radius, top + radius, radius, -PI / 2.0, 0.0, spacing).toTypedArray(),
            Point(right, bubbleBottom - radius),
            *arcPoints(right - radius, bubbleBottom - radius, radius, 0.0, PI / 2.0, spacing).toTypedArray(),
            Point(tailCenterX + tailHalf, bubbleBottom),
            tailTip,
            Point(tailCenterX - tailHalf, bubbleBottom),
            Point(left + radius, bubbleBottom),
            *arcPoints(left + radius, bubbleBottom - radius, radius, PI / 2.0, PI, spacing).toTypedArray(),
            Point(left, top + radius),
            *arcPoints(left + radius, top + radius, radius, PI, PI * 1.5, spacing).toTypedArray(),
            Point(left + radius, top)
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
        val headLength = min(max(10.0, length * 0.18), min(24.0, length * 0.45))
        val headHalfHeight = min(max(5.0, headLength * 0.42), 12.0)
        val perpendicularX = -uy
        val perpendicularY = ux
        val tip = Point(b.x, b.y)
        val headBaseX = b.x - ux * headLength
        val headBaseY = b.y - uy * headLength
        val headTop = Point(
            (headBaseX + perpendicularX * headHalfHeight).roundToInt(),
            (headBaseY + perpendicularY * headHalfHeight).roundToInt()
        )
        val headBottom = Point(
            (headBaseX - perpendicularX * headHalfHeight).roundToInt(),
            (headBaseY - perpendicularY * headHalfHeight).roundToInt()
        )

        val points = mutableListOf<Point>()
        fun appendSegment(from: Point, to: Point) {
            val segment = interpolateLine(from, to, spacing)
            if (points.isNotEmpty() && segment.isNotEmpty()) {
                points.removeAt(points.lastIndex)
            }
            points += segment
        }

        appendSegment(a, tip)
        appendSegment(tip, headTop)
        appendSegment(headTop, tip)
        appendSegment(tip, headBottom)
        return points
    }

    private fun rightBracePoints(a: Point, b: Point, spacing: Double): List<Point> {
        val left = min(a.x, b.x)
        val right = max(a.x, b.x)
        val top = min(a.y, b.y)
        val bottom = max(a.y, b.y)
        val width = (right - left).coerceAtLeast(1)
        val height = (bottom - top).coerceAtLeast(1)

        val tipX = left + (width * 0.14).roundToInt()
        val bodyX = left + (width * 0.76).roundToInt()
        val waistX = right
        val centerY = top + (height * 0.50).roundToInt()

        val start = Point(tipX, top)
        val upperShoulder = Point(bodyX, top + (height * 0.14).roundToInt())
        val upperStemEnd = Point(bodyX, top + (height * 0.35).roundToInt())
        val waist = Point(waistX, centerY)
        val lowerStemStart = Point(bodyX, top + (height * 0.65).roundToInt())
        val lowerShoulder = Point(bodyX, top + (height * 0.86).roundToInt())
        val end = Point(tipX, bottom)

        val points = mutableListOf<Point>()

        fun appendCurve(
            segmentStart: Point,
            control1: Point,
            control2: Point,
            segmentEnd: Point
        ) {
            val segment = cubicBezierPoints(segmentStart, control1, control2, segmentEnd, spacing)
            points += if (points.isEmpty()) segment else segment.drop(1)
        }

        appendCurve(
            segmentStart = start,
            control1 = Point(left + (width * 0.48).roundToInt(), top),
            control2 = Point(bodyX, top + (height * 0.02).roundToInt()),
            segmentEnd = upperShoulder
        )
        appendCurve(
            segmentStart = upperShoulder,
            control1 = Point(bodyX, top + (height * 0.20).roundToInt()),
            control2 = Point(bodyX, top + (height * 0.29).roundToInt()),
            segmentEnd = upperStemEnd
        )
        appendCurve(
            segmentStart = upperStemEnd,
            control1 = Point(bodyX, top + (height * 0.42).roundToInt()),
            control2 = Point(right - (width * 0.10).roundToInt(), top + (height * 0.45).roundToInt()),
            segmentEnd = waist
        )
        appendCurve(
            segmentStart = waist,
            control1 = Point(right - (width * 0.10).roundToInt(), top + (height * 0.55).roundToInt()),
            control2 = Point(bodyX, top + (height * 0.58).roundToInt()),
            segmentEnd = lowerStemStart
        )
        appendCurve(
            segmentStart = lowerStemStart,
            control1 = Point(bodyX, top + (height * 0.71).roundToInt()),
            control2 = Point(bodyX, top + (height * 0.80).roundToInt()),
            segmentEnd = lowerShoulder
        )
        appendCurve(
            segmentStart = lowerShoulder,
            control1 = Point(bodyX, bottom - (height * 0.02).roundToInt()),
            control2 = Point(left + (width * 0.48).roundToInt(), bottom),
            segmentEnd = end
        )

        return points
    }

    private fun roundedPolygonPoints(vertices: List<Point>, cornerRatio: Double, spacing: Double): List<Point> {
        if (vertices.size < 3) return vertices

        val safeRatio = cornerRatio.coerceIn(0.02, 0.40)
        val entries = vertices.mapIndexed { index, vertex ->
            pointBetween(vertex, vertices[(index - 1 + vertices.size) % vertices.size], safeRatio)
        }
        val exits = vertices.mapIndexed { index, vertex ->
            pointBetween(vertex, vertices[(index + 1) % vertices.size], safeRatio)
        }

        val points = mutableListOf<Point>()

        fun appendSegment(segment: List<Point>) {
            points += if (points.isEmpty()) segment else segment.drop(1)
        }

        points += entries.first()
        appendSegment(cubicBezierPoints(entries[0], vertices[0], vertices[0], exits[0], spacing))
        for (index in 1 until vertices.size) {
            appendSegment(interpolateLine(exits[index - 1], entries[index], spacing))
            appendSegment(cubicBezierPoints(entries[index], vertices[index], vertices[index], exits[index], spacing))
        }
        appendSegment(interpolateLine(exits.last(), entries.first(), spacing))

        return points
    }

    private fun pointBetween(from: Point, to: Point, ratio: Double): Point {
        return Point(
            (from.x + (to.x - from.x) * ratio).roundToInt(),
            (from.y + (to.y - from.y) * ratio).roundToInt()
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

    private fun arcPoints(
        centerX: Int,
        centerY: Int,
        radius: Int,
        startAngle: Double,
        endAngle: Double,
        spacing: Double
    ): List<Point> {
        val arcLength = abs(endAngle - startAngle) * radius
        val steps = max(4, ceil(arcLength / spacing).toInt())
        return (0..steps).map { i ->
            val t = i.toDouble() / steps
            val angle = startAngle + (endAngle - startAngle) * t
            Point(
                (centerX + cos(angle) * radius).roundToInt(),
                (centerY + sin(angle) * radius).roundToInt()
            )
        }
    }

    private fun cubicBezierPoints(
        start: Point,
        control1: Point,
        control2: Point,
        end: Point,
        spacing: Double
    ): List<Point> {
        val roughLength = start.distance(control1) + control1.distance(control2) + control2.distance(end)
        val steps = max(6, ceil(roughLength / spacing).toInt())
        return (0..steps).map { i ->
            val t = i.toDouble() / steps
            cubicBezierPoint(start, control1, control2, end, t)
        }
    }

    private fun cubicBezierPoint(start: Point, control1: Point, control2: Point, end: Point, t: Double): Point {
        val inverse = 1.0 - t
        val x = inverse * inverse * inverse * start.x +
            3.0 * inverse * inverse * t * control1.x +
            3.0 * inverse * t * t * control2.x +
            t * t * t * end.x
        val y = inverse * inverse * inverse * start.y +
            3.0 * inverse * inverse * t * control1.y +
            3.0 * inverse * t * t * control2.y +
            t * t * t * end.y
        return Point(x.roundToInt(), y.roundToInt())
    }
}
