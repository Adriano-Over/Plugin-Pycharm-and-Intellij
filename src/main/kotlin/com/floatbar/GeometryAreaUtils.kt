package com.floatbar

import java.awt.Color
import java.awt.Point
import java.awt.Polygon
import java.awt.geom.Area
import java.awt.geom.PathIterator

internal object GeometryAreaUtils {

    fun areaToFilledStrokes(area: Area, color: Color, width: Float): MutableList<StrokePath> {
        val results = mutableListOf<StrokePath>()
        val path = area.getPathIterator(null, 1.0)
        val coords = DoubleArray(6)
        var current = mutableListOf<AnchorPoint>()

        while (!path.isDone) {
            when (path.currentSegment(coords)) {
                PathIterator.SEG_MOVETO -> {
                    if (current.size >= 3) {
                        results += StrokePath(color = color, width = width, points = current, filled = true)
                    }
                    current = mutableListOf(AnchorPoint(0, 0, coords[0].toInt(), coords[1].toInt()))
                }
                PathIterator.SEG_LINETO -> {
                    current += AnchorPoint(0, 0, coords[0].toInt(), coords[1].toInt())
                }
                PathIterator.SEG_CLOSE -> {
                    if (current.size >= 3) {
                        results += StrokePath(color = color, width = width, points = current, filled = true)
                    }
                    current = mutableListOf()
                }
            }
            path.next()
        }

        if (current.size >= 3) {
            results += StrokePath(color = color, width = width, points = current, filled = true)
        }

        return results
    }

    fun buildPolygon(stroke: StrokePath, toViewPoint: (AnchorPoint) -> Point?): Polygon? {
        val points = stroke.points.mapNotNull(toViewPoint)
        if (points.size < 2) return null
        val polygon = Polygon()
        points.forEach { polygon.addPoint(it.x, it.y) }
        return polygon
    }

    fun buildArea(stroke: StrokePath, toViewPoint: (AnchorPoint) -> Point?): Area? {
        val polygon = buildPolygon(stroke, toViewPoint) ?: return null
        if (polygon.npoints < 3) return null
        return Area(polygon)
    }
}
