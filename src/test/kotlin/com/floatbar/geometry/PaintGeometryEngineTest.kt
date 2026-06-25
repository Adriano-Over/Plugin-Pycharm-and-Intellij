package com.floatbar.geometry

import com.floatbar.AnchorPoint
import com.floatbar.PaintGeometryEngine
import com.floatbar.ShapeKind
import com.floatbar.ShapeStrokeFactory
import com.floatbar.StrokePath
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.Point
import java.awt.Rectangle

class PaintGeometryEngineTest {
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
    private val toAnchor: (Point) -> AnchorPoint? = { point -> anchor(point.x, point.y) }

    @Test
    fun `fill returns empty when region is open`() {
        val result = PaintGeometryEngine.fillAt(
            strokes = mutableListOf(),
            seedPoint = Point(50, 50),
            fillColor = Color.BLUE,
            panelBounds = Rectangle(0, 0, 100, 100),
            toViewPoint = toViewPoint
        )

        assertTrue(result.isEmpty(), "Open regions should not produce fill strokes")
    }

    @Test
    fun `fill returns dense strokes inside closed region`() {
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

        assertFalse(result.isEmpty(), "Closed regions should produce fill strokes")
        assertTrue(
            result.all { it.points.size >= 2 },
            "Every generated fill stroke should contain at least two points"
        )
    }

    @Test
    fun `erase along path splits simple polyline into two segments`() {
        val stroke = polylineStroke(
            Point(10, 50),
            Point(50, 50),
            Point(90, 50)
        )

        val result = PaintGeometryEngine.eraseAlongPath(
            strokes = listOf(stroke),
            localPoints = listOf(Point(50, 50)),
            radius = 8.0,
            toAnchor = toAnchor,
            toViewPoint = toViewPoint
        )

        assertEquals(2, result.size, "Erasing through the middle of a simple line should split it into two kept segments")
        assertTrue(result.all { it.points.size >= 2 }, "Each kept segment should still be drawable")
    }

    @Test
    fun `erase by stroke returns entries in original stroke order`() {
        val first = polylineStroke(Point(10, 10), Point(30, 10), Point(50, 10))
        val second = polylineStroke(Point(10, 30), Point(30, 30), Point(50, 30))

        val rebuilt = PaintGeometryEngine.eraseAlongPathByStroke(
            strokes = listOf(first, second),
            localPoints = listOf(Point(30, 10)),
            radius = 6.0,
            toAnchor = toAnchor,
            toViewPoint = toViewPoint
        )

        assertEquals(
            listOf(first.id, second.id),
            rebuilt.keys.toList(),
            "Grouping should stay aligned with the original stroke order"
        )
        assertTrue(rebuilt.containsKey(first.id))
        assertTrue(rebuilt.containsKey(second.id))
    }

    @Test
    fun `erase leaves closed outline fragments open`() {
        val ellipse = ShapeStrokeFactory.buildShapeStroke(
            start = Point(20, 20),
            end = Point(80, 80),
            kind = ShapeKind.ELLIPSE,
            constrain = false,
            color = Color.MAGENTA,
            width = 2f,
            shapeEdgeSpacing = 6.0,
            ellipseSegments = 24,
            toAnchor = { point -> anchor(point.x, point.y) }
        )

        val rebuilt = PaintGeometryEngine.eraseAlongPathByStroke(
            strokes = listOf(ellipse),
            localPoints = listOf(Point(50, 26)),
            radius = 10.0,
            toAnchor = toAnchor,
            toViewPoint = toViewPoint
        )

        val fragments = rebuilt[ellipse.id].orEmpty()
        assertFalse(fragments.isEmpty(), "Cut ellipse should still produce one or more fragments")
        assertTrue(
            fragments.any { it.kind == null },
            "Fragments from a cut ellipse should become open strokes instead of rendering as closed ellipses again"
        )
    }
}
