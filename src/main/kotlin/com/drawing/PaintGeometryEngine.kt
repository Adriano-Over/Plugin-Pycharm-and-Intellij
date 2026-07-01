package com.drawing

import java.awt.Color
import java.awt.Point
import java.awt.Rectangle
import java.awt.Polygon
import java.awt.geom.Area

object PaintGeometryEngine {

    fun eraseAt(
        strokes: List<StrokePath>,
        localPoint: Point,
        radius: Double,
        toAnchor: (Point) -> AnchorPoint?,
        toViewPoint: (AnchorPoint) -> Point?
    ): MutableList<StrokePath> {
        return EraseGeometryEngine.eraseAlongPath(
            strokes = strokes,
            localPoints = listOf(localPoint),
            radius = radius,
            toAnchor = toAnchor,
            toViewPoint = toViewPoint
        )
    }

    fun eraseAlongPath(
        strokes: List<StrokePath>,
        localPoints: List<Point>,
        radius: Double,
        toAnchor: (Point) -> AnchorPoint?,
        toViewPoint: (AnchorPoint) -> Point?
    ): MutableList<StrokePath> {
        return EraseGeometryEngine.eraseAlongPath(
            strokes = strokes,
            localPoints = localPoints,
            radius = radius,
            toAnchor = toAnchor,
            toViewPoint = toViewPoint
        )
    }

    fun eraseAlongPathByStroke(
        strokes: List<StrokePath>,
        localPoints: List<Point>,
        radius: Double,
        toAnchor: (Point) -> AnchorPoint?,
        toViewPoint: (AnchorPoint) -> Point?
    ): LinkedHashMap<Long, MutableList<StrokePath>> {
        return EraseGeometryEngine.eraseAlongPathByStroke(
            strokes = strokes,
            localPoints = localPoints,
            radius = radius,
            toAnchor = toAnchor,
            toViewPoint = toViewPoint
        )
    }

    fun fillAt(
        strokes: List<StrokePath>,
        seedPoint: Point,
        fillColor: Color,
        panelBounds: Rectangle,
        toViewPoint: (AnchorPoint) -> Point?
    ): MutableList<StrokePath> {
        return FillGeometryEngine.fillAt(
            strokes = strokes,
            seedPoint = seedPoint,
            fillColor = fillColor,
            panelBounds = panelBounds,
            toViewPoint = toViewPoint
        )
    }

    fun fillRasterAt(
        strokes: List<StrokePath>,
        existingRasterFills: List<RasterFillPath>,
        seedPoint: Point,
        fillColor: Color,
        panelBounds: Rectangle,
        toViewPoint: (AnchorPoint) -> Point?,
        toAnchor: (Point) -> AnchorPoint?
    ): RasterFillPath? {
        return FillGeometryEngine.fillRasterAt(
            strokes = strokes,
            existingRasterFills = existingRasterFills,
            seedPoint = seedPoint,
            fillColor = fillColor,
            panelBounds = panelBounds,
            toViewPoint = toViewPoint,
            toAnchor = toAnchor
        )
    }

    fun areaToFilledStrokes(area: Area, color: Color, width: Float): MutableList<StrokePath> {
        return GeometryAreaUtils.areaToFilledStrokes(area, color, width)
    }

    fun buildPolygon(stroke: StrokePath, toViewPoint: (AnchorPoint) -> Point?): Polygon? {
        return GeometryAreaUtils.buildPolygon(stroke, toViewPoint)
    }

    fun buildArea(stroke: StrokePath, toViewPoint: (AnchorPoint) -> Point?): Area? {
        return GeometryAreaUtils.buildArea(stroke, toViewPoint)
    }
}

