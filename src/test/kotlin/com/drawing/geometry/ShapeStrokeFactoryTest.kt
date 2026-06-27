package com.drawing.geometry

import com.drawing.AnchorPoint
import com.drawing.DrawingStrokeRenderer
import com.drawing.EraseGeometryEngine
import com.drawing.ShapeKind
import com.drawing.ShapeStrokeFactory
import java.awt.geom.PathIterator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.Point

class ShapeStrokeFactoryTest {
    private fun toAnchor(point: Point): AnchorPoint = AnchorPoint(
        line = 0,
        column = 0,
        dx = point.x,
        dy = point.y
    )

    private fun toContentPoint(anchor: AnchorPoint): Point = Point(anchor.dx, anchor.dy)

    private fun geometryMoveToCount(kind: ShapeKind): Int {
        val stroke = ShapeStrokeFactory.buildShapeStroke(
            start = Point(10, 10),
            end = Point(130, 90),
            kind = kind,
            constrain = false,
            color = Color.BLACK,
            width = 3f,
            shapeEdgeSpacing = 4.0,
            ellipseSegments = 36,
            toAnchor = ::toAnchor
        )
        val geometry = DrawingStrokeRenderer(canvasPadding = 0, gridExtendLeftPx = 0)
            .buildStrokeGeometryContent(stroke, ::toContentPoint)
        val iterator = geometry!!.path!!.getPathIterator(null)
        val coordinates = DoubleArray(6)
        var moveToCount = 0

        while (!iterator.isDone) {
            if (iterator.currentSegment(coordinates) == PathIterator.SEG_MOVETO) {
                moveToCount++
            }
            iterator.next()
        }

        return moveToCount
    }

    @Test
    fun `rectangle builds closed polyline`() {
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
        assertEquals(first.dx, last.dx)
        assertEquals(first.dy, last.dy)
        assertEquals(ShapeKind.RECTANGLE, stroke.kind)
    }

    @Test
    fun `constrained line snaps to nearest primary angle`() {
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
        assertEquals(0, last.dy, "A nearly horizontal constrained line should snap horizontally")
    }

    @Test
    fun `ellipse builds expected point count`() {
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

        assertEquals(segments + 1, stroke.points.size)
    }

    @Test
    fun `arrow builds thin line arrow with compact head at drag end`() {
        val stroke = ShapeStrokeFactory.buildShapeStroke(
            start = Point(120, 80),
            end = Point(20, 80),
            kind = ShapeKind.ARROW,
            constrain = false,
            color = Color.BLACK,
            width = 4f,
            shapeEdgeSpacing = 4.0,
            ellipseSegments = 36,
            toAnchor = ::toAnchor
        )

        val minX = stroke.points.minOf { it.dx }
        val maxX = stroke.points.maxOf { it.dx }
        val centerY = 80
        val linePoints = stroke.points.filter { it.dx > 45 }
        val arrowHeadPoints = stroke.points.filter { it.dx <= 45 }
        val lineHalfHeight = linePoints.maxOf { kotlin.math.abs(it.dy - centerY) }
        val arrowHeadHalfHeight = arrowHeadPoints.maxOf { kotlin.math.abs(it.dy - centerY) }

        assertEquals(false, stroke.filled)
        assertEquals(20, minX, "Arrow tip should be at the drag end for a left-pointing arrow")
        assertEquals(120, maxX, "Arrow tail should stay at the drag start")
        assertEquals(false, ShapeKind.ARROW.isClosedOutline(), "Arrow should erase like a normal open line")
        assertTrue(stroke.points.size > 20, "Arrow should sample the shaft and two head wings")
        assertTrue(lineHalfHeight <= 1, "Arrow shaft should stay line-thin")
        assertTrue(arrowHeadHalfHeight in 4..14, "Arrow head should be visible but compact")
    }

    @Test
    fun `arrow erases through the normal open stroke path`() {
        val stroke = ShapeStrokeFactory.buildShapeStroke(
            start = Point(10, 80),
            end = Point(120, 80),
            kind = ShapeKind.ARROW,
            constrain = false,
            color = Color.BLACK,
            width = 4f,
            shapeEdgeSpacing = 4.0,
            ellipseSegments = 36,
            toAnchor = ::toAnchor
        )

        val erased = EraseGeometryEngine.eraseAlongPath(
            strokes = listOf(stroke),
            localPoints = listOf(Point(58, 70), Point(58, 90)),
            radius = 8.0,
            toAnchor = ::toAnchor,
            toViewPoint = { anchor -> Point(anchor.dx, anchor.dy) }
        )

        assertTrue(erased.isNotEmpty(), "Erasing through an arrow should leave the untouched arrow pieces")
        assertTrue(erased.all { !it.filled }, "Arrow erase pieces should stay normal erasable strokes")
        assertTrue(erased.all { it.kind == ShapeKind.ARROW }, "Arrow erase pieces should preserve arrow kind")
        assertTrue(
            erased.flatMap { it.points }.none { it.dx in 54..62 && it.dy == 80 },
            "Eraser should remove the crossed shaft samples"
        )
    }

    @Test
    fun `flowchart image shapes are available and build geometry`() {
        val imageShapes = listOf(
            ShapeKind.START_END,
            ShapeKind.PREDEFINED_PROCESS,
            ShapeKind.PROCESS,
            ShapeKind.DOCUMENT,
            ShapeKind.DECISION,
            ShapeKind.MULTIPLE_DOCUMENTS,
            ShapeKind.CONNECTOR,
            ShapeKind.STORED_DATA,
            ShapeKind.MANUAL_OPERATION,
            ShapeKind.BALLOON
        )

        for (shapeKind in imageShapes) {
            val stroke = ShapeStrokeFactory.buildShapeStroke(
                start = Point(10, 10),
                end = Point(120, 80),
                kind = shapeKind,
                constrain = false,
                color = Color.BLACK,
                width = 3f,
                shapeEdgeSpacing = 4.0,
                ellipseSegments = 36,
                toAnchor = ::toAnchor
            )

            assertTrue(stroke.points.size > 8, "${shapeKind.displayName} should produce drawable geometry")
            assertEquals(shapeKind, stroke.kind)
        }
    }

    @Test
    fun `balloon shape builds rounded bubble with a tail`() {
        val stroke = ShapeStrokeFactory.buildShapeStroke(
            start = Point(10, 10),
            end = Point(130, 90),
            kind = ShapeKind.BALLOON,
            constrain = false,
            color = Color.BLACK,
            width = 3f,
            shapeEdgeSpacing = 4.0,
            ellipseSegments = 36,
            toAnchor = ::toAnchor
        )

        val minX = stroke.points.minOf { it.dx }
        val maxX = stroke.points.maxOf { it.dx }
        val maxY = stroke.points.maxOf { it.dy }
        val bottomPoint = stroke.points.first { it.dy == maxY }
        val first = stroke.points.first()
        val last = stroke.points.last()

        assertEquals(ShapeKind.BALLOON, stroke.kind)
        assertEquals(first.dx, last.dx, "Balloon outline should close cleanly")
        assertEquals(first.dy, last.dy, "Balloon outline should close cleanly")
        assertTrue(stroke.points.size > 30, "Balloon should be sampled smoothly")
        assertTrue(
            bottomPoint.dx in (minX + 10)..(minX + (maxX - minX) / 2),
            "Balloon tail should sit on the lower-left side"
        )
        assertTrue(ShapeKind.BALLOON.isClosedOutline(), "Balloon should be treated as a closed shape")
    }

    @Test
    fun `text shape builds placement rectangle and is not a closed saved outline`() {
        val stroke = ShapeStrokeFactory.buildShapeStroke(
            start = Point(10, 10),
            end = Point(130, 80),
            kind = ShapeKind.TEXT,
            constrain = false,
            color = Color.BLACK,
            width = 3f,
            shapeEdgeSpacing = 4.0,
            ellipseSegments = 36,
            toAnchor = ::toAnchor
        )

        assertEquals(ShapeKind.TEXT, stroke.kind)
        assertTrue(stroke.points.size > 8, "Text shape should show a useful placement preview")
        assertEquals(false, ShapeKind.TEXT.isClosedOutline(), "Text should commit only generated letters, not a box")
    }

    @Test
    fun `predefined process includes both vertical side bars`() {
        val stroke = ShapeStrokeFactory.buildShapeStroke(
            start = Point(10, 10),
            end = Point(110, 70),
            kind = ShapeKind.PREDEFINED_PROCESS,
            constrain = false,
            color = Color.BLACK,
            width = 3f,
            shapeEdgeSpacing = 4.0,
            ellipseSegments = 36,
            toAnchor = ::toAnchor
        )

        val leftBarSamples = stroke.points.count { it.dx == 20 && it.dy in 14..66 }
        val rightBarSamples = stroke.points.count { it.dx == 100 && it.dy in 14..66 }

        assertTrue(leftBarSamples > 6, "Predefined process should draw the left divider")
        assertTrue(rightBarSamples > 6, "Predefined process should draw the right divider")
    }

    @Test
    fun `stored data uses curved sides and manual operation uses trapezoid`() {
        val storedData = ShapeStrokeFactory.buildShapeStroke(
            start = Point(10, 10),
            end = Point(110, 70),
            kind = ShapeKind.STORED_DATA,
            constrain = false,
            color = Color.BLACK,
            width = 3f,
            shapeEdgeSpacing = 4.0,
            ellipseSegments = 36,
            toAnchor = ::toAnchor
        )
        val manualOperation = ShapeStrokeFactory.buildShapeStroke(
            start = Point(10, 10),
            end = Point(110, 70),
            kind = ShapeKind.MANUAL_OPERATION,
            constrain = false,
            color = Color.BLACK,
            width = 3f,
            shapeEdgeSpacing = 4.0,
            ellipseSegments = 36,
            toAnchor = ::toAnchor
        )

        val storedMinX = storedData.points.minOf { it.dx }
        val manualTop = manualOperation.points.filter { it.dy == 10 }
        val manualBottom = manualOperation.points.filter { it.dy == 70 }

        assertTrue(storedMinX < 10, "Stored data should bow outward on the left side")
        assertTrue(manualTop.minOf { it.dx } < manualBottom.minOf { it.dx }, "Manual operation bottom should be inset")
        assertTrue(manualTop.maxOf { it.dx } > manualBottom.maxOf { it.dx }, "Manual operation top should be wider")
    }

    @Test
    fun `document shape generates multiple outline points`() {
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

        assertTrue(
            stroke.points.size > 8,
            "Document shapes should produce a usable outline, not just a few corner points"
        )
    }

    @Test
    fun `curly bracket shape uses clear label and remains open`() {
        val stroke = ShapeStrokeFactory.buildShapeStroke(
            start = Point(20, 10),
            end = Point(80, 110),
            kind = ShapeKind.RIGHT_BRACE,
            constrain = false,
            color = Color.BLACK,
            width = 2f,
            shapeEdgeSpacing = 5.0,
            ellipseSegments = 36,
            toAnchor = ::toAnchor
        )

        val first = stroke.points.first()
        val last = stroke.points.last()
        val middle = stroke.points.minBy { kotlin.math.abs(it.dy - 60) }
        val upperStem = stroke.points.minBy { kotlin.math.abs(it.dy - 35) }
        val lowerStem = stroke.points.minBy { kotlin.math.abs(it.dy - 85) }

        assertEquals("Curly bracket", ShapeKind.RIGHT_BRACE.displayName)
        assertEquals(false, ShapeKind.RIGHT_BRACE.isClosedOutline())
        assertTrue(stroke.points.size > 20, "Brace should be sampled from curves, not built from a jagged few points")
        assertTrue(first.dx < 35 && first.dy == 10, "Top of } should start near the left side of its bounds")
        assertTrue(last.dx < 35 && last.dy == 110, "Bottom of } should end near the left side of its bounds")
        assertTrue(middle.dx >= 77, "Middle point of } should form the sharp right-side waist")
        assertTrue(upperStem.dx in 60..72, "Upper stem of } should stay nearly vertical before the waist")
        assertTrue(lowerStem.dx in 60..72, "Lower stem of } should stay nearly vertical after the waist")
    }

    @Test
    fun `predefined process renders dividers as independent subpaths`() {
        assertTrue(
            geometryMoveToCount(ShapeKind.PREDEFINED_PROCESS) >= 3,
            "Predefined process should render outer border plus two divider bars without connector diagonals"
        )
    }

    @Test
    fun `multiple documents renders stacked pages as independent subpaths`() {
        assertTrue(
            geometryMoveToCount(ShapeKind.MULTIPLE_DOCUMENTS) >= 3,
            "Multiple documents should render stacked pages without unwanted connecting lines"
        )
    }

    @Test
    fun `start end shape uses rounded outline samples`() {
        val stroke = ShapeStrokeFactory.buildShapeStroke(
            start = Point(10, 10),
            end = Point(100, 50),
            kind = ShapeKind.START_END,
            constrain = false,
            color = Color.BLACK,
            width = 2f,
            shapeEdgeSpacing = 4.0,
            ellipseSegments = 36,
            toAnchor = ::toAnchor
        )

        val uniqueYValuesOnRightHalf = stroke.points
            .filter { it.dx > 70 }
            .map { it.dy }
            .distinct()

        assertTrue(stroke.points.size > 30, "Start/end shape should use rounded corners instead of a low-point octagon")
        assertTrue(uniqueYValuesOnRightHalf.size > 6, "Rounded end should include curved samples with multiple y values")
    }

    @Test
    fun `document bottom edge uses smooth wave samples`() {
        val stroke = ShapeStrokeFactory.buildShapeStroke(
            start = Point(10, 10),
            end = Point(110, 80),
            kind = ShapeKind.DOCUMENT,
            constrain = false,
            color = Color.BLACK,
            width = 2f,
            shapeEdgeSpacing = 4.0,
            ellipseSegments = 36,
            toAnchor = ::toAnchor
        )

        val bottomWavePoints = stroke.points.filter { it.dy > 60 }

        assertTrue(bottomWavePoints.size > 8, "Document wave should be sampled smoothly")
        assertTrue(bottomWavePoints.any { it.dy >= 78 }, "Document wave should dip near the bottom of the bounds")
    }
}
