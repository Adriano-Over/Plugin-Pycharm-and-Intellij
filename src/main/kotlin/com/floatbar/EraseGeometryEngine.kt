package com.floatbar

import java.awt.BasicStroke
import java.awt.Point
import java.awt.geom.Area
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D

internal object EraseGeometryEngine {

    fun eraseAlongPath(
        strokes: List<StrokePath>,
        localPoints: List<Point>,
        radius: Double,
        toViewPoint: (AnchorPoint) -> Point?
    ): MutableList<StrokePath> {
        if (localPoints.isEmpty()) return strokes.map { it.deepCopy() }.toMutableList()

        val rebuilt = mutableListOf<StrokePath>()
        for (replacements in eraseAlongPathByStroke(strokes, localPoints, radius, toViewPoint).values) {
            rebuilt += replacements
        }
        return rebuilt
    }

    fun eraseAlongPathByStroke(
        strokes: List<StrokePath>,
        localPoints: List<Point>,
        radius: Double,
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
            } else {
                cutStrokeByEraserArea(stroke, eraserArea, toViewPoint)
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

    private fun cutStrokeByEraserArea(
        stroke: StrokePath,
        eraserArea: Area,
        toViewPoint: (AnchorPoint) -> Point?
    ): MutableList<StrokePath> {
        val viewPoints = stroke.points.mapNotNull { anchor ->
            toViewPoint(anchor)?.let { anchor to it }
        }
        if (viewPoints.size < 2) {
            return mutableListOf(stroke.deepCopy())
        }

        val keptSegments = mutableListOf<MutableList<AnchorPoint>>()
        var current = mutableListOf<AnchorPoint>()

        fun flushCurrent() {
            if (current.size >= 2) {
                keptSegments += current
            }
            current = mutableListOf()
        }

        for (i in 0 until viewPoints.lastIndex) {
            val (aAnchor, aPoint) = viewPoints[i]
            val (bAnchor, bPoint) = viewPoints[i + 1]
            val hits = segmentIntersectsArea(eraserArea, aPoint, bPoint)

            if (!hits) {
                if (current.isEmpty()) current += aAnchor.copy()
                current += bAnchor.copy()
            } else {
                flushCurrent()
            }
        }

        flushCurrent()

        if (keptSegments.isEmpty()) return mutableListOf()

        return keptSegments.mapTo(mutableListOf()) { segment ->
            StrokePath(
                color = stroke.color,
                width = stroke.width,
                points = segment,
                filled = false,
                kind = stroke.kind
            )
        }
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
