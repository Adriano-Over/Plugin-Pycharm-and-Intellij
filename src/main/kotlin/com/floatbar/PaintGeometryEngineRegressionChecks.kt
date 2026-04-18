package com.floatbar

import java.awt.Color
import java.awt.Point
import java.awt.Rectangle

/**
 * Framework-agnostic regression checks for geometry behavior.
 *
 * These can live under src/test/kotlin/com/floatbar/ and be wrapped by whatever
 * test runner you already use, or called manually from a temporary harness.
 */
object PaintGeometryEngineRegressionChecks {

    private fun anchor(x: Int, y: Int): AnchorPoint = AnchorPoint(
        line = 0,
        column = 0,
        dx = x,
        dy = y
    )

    private fun closedStroke(vararg points: Point, color: Color = Color.RED): StrokePath {
        val anchors = points.map { anchor(it.x, it.y) }.toMutableList()
        return StrokePath(color = color, width = 2f, points = anchors, filled = false, kind = ShapeKind.RECTANGLE)
    }

    private fun polylineStroke(vararg points: Point, color: Color = Color.RED): StrokePath {
        val anchors = points.map { anchor(it.x, it.y) }.toMutableList()
        return StrokePath(color = color, width = 2f, points = anchors, filled = false, kind = ShapeKind.LINE)
    }

    private val toViewPoint: (AnchorPoint) -> Point? = { Point(it.dx, it.dy) }

    fun runAll() {
        fillAtReturnsEmptyWhenRegionIsOpen()
        fillAtReturnsDenseStrokesInsideClosedRegion()
        eraseAlongPathSplitsSimplePolylineIntoTwoSegments()
        eraseAlongPathByStrokeReturnsEntriesInOriginalStrokeOrder()
    }

    fun fillAtReturnsEmptyWhenRegionIsOpen() {
        val result = PaintGeometryEngine.fillAt(
            strokes = mutableListOf(),
            seedPoint = Point(50, 50),
            fillColor = Color.BLUE,
            panelBounds = Rectangle(0, 0, 100, 100),
            toViewPoint = toViewPoint
        )

        check(result.isEmpty()) { "Open regions should not produce fill strokes" }
    }

    fun fillAtReturnsDenseStrokesInsideClosedRegion() {
        val box = closedStroke(
            Point(20, 20),
            Point(80, 20),
            Point(80, 80),
            Point(20, 80),
            Point(20, 20)
        )

        val result = PaintGeometryEngine.fillAt(
            strokes = listOf(box),
            seedPoint = Point(50, 50),
            fillColor = Color.GREEN,
            panelBounds = Rectangle(0, 0, 100, 100),
            toViewPoint = toViewPoint
        )

        check(result.isNotEmpty()) { "Closed regions should produce fill strokes" }
        check(result.all { it.points.size >= 2 }) { "Every generated fill stroke should contain at least two points" }
    }

    fun eraseAlongPathSplitsSimplePolylineIntoTwoSegments() {
        val stroke = polylineStroke(
            Point(10, 50),
            Point(50, 50),
            Point(90, 50)
        )

        val result = PaintGeometryEngine.eraseAlongPath(
            strokes = listOf(stroke),
            localPoints = listOf(Point(50, 50)),
            radius = 8.0,
            toViewPoint = toViewPoint
        )

        check(result.size == 2) { "Erasing through the middle of a simple line should split it into two kept segments" }
        check(result.all { it.points.size >= 2 }) { "Each kept segment should still be drawable" }
    }

    fun eraseAlongPathByStrokeReturnsEntriesInOriginalStrokeOrder() {
        val first = polylineStroke(Point(10, 10), Point(30, 10), Point(50, 10))
        val second = polylineStroke(Point(10, 30), Point(30, 30), Point(50, 30))

        val rebuilt = PaintGeometryEngine.eraseAlongPathByStroke(
            strokes = listOf(first, second),
            localPoints = listOf(Point(30, 10)),
            radius = 6.0,
            toViewPoint = toViewPoint
        )

        check(rebuilt.keys.toList() == listOf(first.id, second.id)) {
            "Grouping should stay aligned with the original stroke order"
        }
        check(rebuilt.containsKey(first.id))
        check(rebuilt.containsKey(second.id))
    }
}
