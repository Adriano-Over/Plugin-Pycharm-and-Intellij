package com.floatbar

import java.awt.Color
import java.awt.Point

/** Framework-agnostic regression checks for generated shapes. */
object ShapeStrokeFactoryRegressionChecks {

    private fun toAnchor(point: Point): AnchorPoint = AnchorPoint(
        line = 0,
        column = 0,
        dx = point.x,
        dy = point.y
    )

    fun runAll() {
        rectangleBuildsClosedPolyline()
        constrainedLineSnapsToNearestPrimaryAngle()
        ellipseBuildsExpectedPointCount()
        documentShapeGeneratesMultipleOutlinePoints()
    }

    fun rectangleBuildsClosedPolyline() {
        val stroke = ShapeStrokeFactory.buildShapeStroke(
            start = Point(10, 10),
            end = Point(60, 40),
            kind = ShapeKind.RECTANGLE,
            constrain = false,
            color = Color.RED,
            width = 2f,
            shapeEdgeSpacing = 6.0,
            ellipseSegments = 36,
            toAnchor = ::toAnchor
        )

        val first = stroke.points.first()
        val last = stroke.points.last()
        check(first.dx == last.dx)
        check(first.dy == last.dy)
        check(stroke.kind == ShapeKind.RECTANGLE)
    }

    fun constrainedLineSnapsToNearestPrimaryAngle() {
        val stroke = ShapeStrokeFactory.buildShapeStroke(
            start = Point(0, 0),
            end = Point(20, 3),
            kind = ShapeKind.LINE,
            constrain = true,
            color = Color.RED,
            width = 2f,
            shapeEdgeSpacing = 6.0,
            ellipseSegments = 36,
            toAnchor = ::toAnchor
        )

        val last = stroke.points.last()
        check(last.dy == 0) { "A nearly horizontal constrained line should snap horizontally" }
    }

    fun ellipseBuildsExpectedPointCount() {
        val segments = 24
        val stroke = ShapeStrokeFactory.buildShapeStroke(
            start = Point(10, 10),
            end = Point(50, 30),
            kind = ShapeKind.ELLIPSE,
            constrain = false,
            color = Color.BLUE,
            width = 2f,
            shapeEdgeSpacing = 6.0,
            ellipseSegments = segments,
            toAnchor = ::toAnchor
        )

        check(stroke.points.size == segments + 1)
    }

    fun documentShapeGeneratesMultipleOutlinePoints() {
        val stroke = ShapeStrokeFactory.buildShapeStroke(
            start = Point(10, 10),
            end = Point(80, 60),
            kind = ShapeKind.DOCUMENT,
            constrain = false,
            color = Color.BLACK,
            width = 2f,
            shapeEdgeSpacing = 6.0,
            ellipseSegments = 36,
            toAnchor = ::toAnchor
        )

        check(stroke.points.size > 8) { "Document shapes should produce a usable outline, not just a few corner points" }
    }
}
