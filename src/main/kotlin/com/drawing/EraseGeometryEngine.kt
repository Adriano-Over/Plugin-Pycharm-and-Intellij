package com.drawing

import java.awt.BasicStroke
import java.awt.Point
import java.awt.geom.Area
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D

internal object EraseGeometryEngine {

    private const val ERASE_SEGMENT_SAMPLE_STEP_PX = 3.0

    fun eraseAlongPath(
        strokes: List<StrokePath>,
        localPoints: List<Point>,
        radius: Double,
        toAnchor: (Point) -> AnchorPoint?,
        toViewPoint: (AnchorPoint) -> Point?
    ): MutableList<StrokePath> {
        if (localPoints.isEmpty()) return strokes.map { it.deepCopy() }.toMutableList()

        val rebuilt = mutableListOf<StrokePath>()
        for (replacements in eraseAlongPathByStroke(strokes, localPoints, radius, toAnchor, toViewPoint).values) {
            rebuilt += replacements
        }
        return rebuilt
    }

    fun eraseAlongPathByStroke(
        strokes: List<StrokePath>,
        localPoints: List<Point>,
        radius: Double,
        toAnchor: (Point) -> AnchorPoint?,
        toViewPoint: (AnchorPoint) -> Point?
    ): LinkedHashMap<Long, MutableList<StrokePath>> {
        val rebuiltByStroke = LinkedHashMap<Long, MutableList<StrokePath>>()
        if (localPoints.isEmpty()) {
            for (stroke in strokes) {
                rebuiltByStroke[stroke.id] = mutableListOf(stroke.deepCopy())
            }
            return rebuiltByStroke
        }

        val eraserArea = buildEraserArea(localPoints, radius)

        for (stroke in strokes) {
            if (stroke.points.isEmpty()) {
                rebuiltByStroke[stroke.id] = mutableListOf()
                continue
            }

            val rebuilt = if (stroke.filled) {
                val area = GeometryAreaUtils.buildArea(stroke, toViewPoint)
                if (area == null) {
                    mutableListOf(stroke.deepCopy())
                } else {
                    area.subtract(eraserArea)
                    GeometryAreaUtils.areaToFilledStrokes(area, stroke.color, stroke.width)
                }
            } else if (stroke.kind?.isClosedOutline() == true) {
                cutClosedOutlineStrokeByEraserArea(stroke, eraserArea, toAnchor, toViewPoint)
            } else {
                cutStrokeByEraserArea(stroke, eraserArea, toAnchor, toViewPoint)
            }

            rebuiltByStroke[stroke.id] = rebuilt
        }

        return rebuiltByStroke
    }

    private fun buildEraserArea(points: List<Point>, radius: Double): Area {
        if (points.isEmpty()) return Area()

        val area = Area()

        if (points.size == 1) {
            val p = points.first()
            area.add(
                Area(
                    Ellipse2D.Double(
                        p.x - radius,
                        p.y - radius,
                        radius * 2,
                        radius * 2
                    )
                )
            )
            return area
        }

        val path = Path2D.Double()
        path.moveTo(points.first().x.toDouble(), points.first().y.toDouble())
        for (point in points.drop(1)) {
            path.lineTo(point.x.toDouble(), point.y.toDouble())
        }

        val strokeShape = BasicStroke(
            (radius * 2.0).toFloat(),
            BasicStroke.CAP_ROUND,
            BasicStroke.JOIN_ROUND
        ).createStrokedShape(path)

        area.add(Area(strokeShape))

        val first = points.first()
        val last = points.last()

        area.add(
            Area(
                Ellipse2D.Double(
                    first.x - radius,
                    first.y - radius,
                    radius * 2,
                    radius * 2
                )
            )
        )
        area.add(
            Area(
                Ellipse2D.Double(
                    last.x - radius,
                    last.y - radius,
                    radius * 2,
                    radius * 2
                )
            )
        )

        return area
    }

    private fun cutClosedOutlineStrokeByEraserArea(
        stroke: StrokePath,
        eraserArea: Area,
        toAnchor: (Point) -> AnchorPoint?,
        toViewPoint: (AnchorPoint) -> Point?
    ): MutableList<StrokePath> {
        val normalizedPoints = normalizeClosedOutlinePoints(stroke.points)
        if (normalizedPoints.size < 2) {
            return mutableListOf(stroke.deepCopy())
        }

        val normalizedStroke = stroke.deepCopy().also { copy ->
            copy.points.clear()
            copy.points.addAll(normalizedPoints.map { it.copy() })
        }
        return cutStrokeByEraserArea(normalizedStroke, eraserArea, toAnchor, toViewPoint, preserveKind = false)
    }

    private fun normalizeClosedOutlinePoints(points: List<AnchorPoint>): List<AnchorPoint> {
        if (points.size < 2) return points
        return if (sameAnchor(points.first(), points.last())) {
            points.dropLast(1)
        } else {
            points
        }
    }

    private fun cutStrokeByEraserArea(
        stroke: StrokePath,
        eraserArea: Area,
        toAnchor: (Point) -> AnchorPoint?,
        toViewPoint: (AnchorPoint) -> Point?,
        preserveKind: Boolean = true
    ): MutableList<StrokePath> {
        val viewPoints = stroke.points.mapNotNull { anchor ->
            toViewPoint(anchor)?.let { anchor to it }
        }
        if (viewPoints.size < 2) {
            return mutableListOf(stroke.deepCopy())
        }

        if (viewPoints.none { (_, point) -> eraserArea.contains(point.x.toDouble(), point.y.toDouble()) } &&
            viewPoints.zipWithNext().none { (a, b) -> segmentIntersectsArea(eraserArea, a.second, b.second) }
        ) {
            return mutableListOf(stroke.deepCopy())
        }

        val keptSegments = mutableListOf<MutableList<AnchorPoint>>()
        var current = mutableListOf<AnchorPoint>()

        fun appendKept(anchor: AnchorPoint) {
            val previous = current.lastOrNull()
            if (previous == null || !sameAnchor(previous, anchor)) {
                current += anchor
            }
        }

        fun flushCurrent() {
            if (current.size >= 2) {
                keptSegments += current
            }
            current = mutableListOf()
        }

        for (i in 0 until viewPoints.lastIndex) {
            val (aAnchor, aPoint) = viewPoints[i]
            val (bAnchor, bPoint) = viewPoints[i + 1]

            val segmentLength = aPoint.distance(bPoint).coerceAtLeast(1.0)
            val steps = maxOf(1, kotlin.math.ceil(segmentLength / ERASE_SEGMENT_SAMPLE_STEP_PX).toInt())

            for (step in 0..steps) {
                if (i > 0 && step == 0) continue

                val t = step.toDouble() / steps.toDouble()
                val point = interpolatePoint(aPoint, bPoint, t)
                val anchor = toAnchor(point)?.copy() ?: if (step == 0) {
                    aAnchor.copy()
                } else if (step == steps) {
                    bAnchor.copy()
                } else {
                    interpolateAnchor(aAnchor, bAnchor, t)
                }

                if (eraserArea.contains(point.x.toDouble(), point.y.toDouble())) {
                    flushCurrent()
                } else {
                    appendKept(anchor)
                }
            }
        }

        flushCurrent()

        if (keptSegments.isEmpty()) return mutableListOf()

        val resultKind = if (preserveKind) stroke.kind else null
        return keptSegments.mapTo(mutableListOf()) { segment ->
            StrokePath(
                color = stroke.color,
                width = stroke.width,
                points = segment,
                filled = false,
                kind = resultKind
            )
        }
    }

    private fun interpolatePoint(a: Point, b: Point, t: Double): Point {
        return Point(
            (a.x + (b.x - a.x) * t).toInt(),
            (a.y + (b.y - a.y) * t).toInt()
        )
    }

    private fun interpolateAnchor(a: AnchorPoint, b: AnchorPoint, t: Double): AnchorPoint {
        fun lerpInt(start: Int, end: Int): Int = (start + (end - start) * t).toInt()

        return AnchorPoint(
            line = lerpInt(a.line, b.line),
            column = lerpInt(a.column, b.column),
            dx = lerpInt(a.dx, b.dx),
            dy = lerpInt(a.dy, b.dy),
            offset = lerpInt(a.offset, b.offset),
            outsideCode = a.outsideCode && b.outsideCode,
            afterLineEndPx = lerpInt(a.afterLineEndPx, b.afterLineEndPx),
            foldHiddenHeightAbove = if (a.foldHiddenHeightAbove == b.foldHiddenHeightAbove) {
                a.foldHiddenHeightAbove
            } else {
                UNSET_FOLD_HIDDEN_HEIGHT_ABOVE
            },
            foldLayoutBaseY = if (a.foldLayoutBaseY == b.foldLayoutBaseY) {
                a.foldLayoutBaseY
            } else {
                UNSET_FOLD_LAYOUT_BASE_Y
            }
        )
    }

    private fun sameAnchor(a: AnchorPoint, b: AnchorPoint): Boolean {
        return a.line == b.line &&
            a.column == b.column &&
            a.dx == b.dx &&
            a.dy == b.dy &&
            a.offset == b.offset &&
            a.outsideCode == b.outsideCode &&
            a.afterLineEndPx == b.afterLineEndPx
    }

    private fun segmentIntersectsArea(area: Area, a: Point, b: Point): Boolean {
        val minX = minOf(a.x, b.x)
        val minY = minOf(a.y, b.y)
        val maxX = maxOf(a.x, b.x)
        val maxY = maxOf(a.y, b.y)

        if (!area.intersects(
                minX.toDouble() - 2.0,
                minY.toDouble() - 2.0,
                (maxX - minX).toDouble() + 4.0,
                (maxY - minY).toDouble() + 4.0
            )
        ) {
            return false
        }

        val segmentLength = a.distance(b)
        val stepSize = 10.0
        val steps = maxOf(1, kotlin.math.ceil(segmentLength / stepSize).toInt())

        for (i in 0..steps) {
            val t = i.toDouble() / steps
            val x = a.x + (b.x - a.x) * t
            val y = a.y + (b.y - a.y) * t
            if (area.contains(x, y)) return true
        }

        return false
    }
}
