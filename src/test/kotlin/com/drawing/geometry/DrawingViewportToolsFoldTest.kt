package com.drawing.geometry

import com.drawing.AnchorPoint
import com.drawing.CollapsedFoldRegionSnapshot
import com.drawing.DrawingViewportTools
import com.drawing.StrokePath
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.Point

class DrawingViewportToolsFoldTest {
    @Test
    fun `stroke fully inside a collapsed fold is hidden`() {
        val stroke = strokeWithOffsets(40, 42, 45)
        val regions = listOf(
            CollapsedFoldRegionSnapshot(
                startOffset = 30,
                endOffset = 60,
                placeholderPoint = Point(120, 80),
                placeholderWidth = 18
            )
        )

        assertTrue(DrawingViewportTools.isStrokeHiddenByCollapsedFold(stroke, regions))
    }

    @Test
    fun `stroke outside a collapsed fold remains visible`() {
        val stroke = strokeWithOffsets(10, 12, 15)
        val regions = listOf(
            CollapsedFoldRegionSnapshot(
                startOffset = 30,
                endOffset = 60,
                placeholderPoint = Point(120, 80),
                placeholderWidth = 18
            )
        )

        assertFalse(DrawingViewportTools.isStrokeHiddenByCollapsedFold(stroke, regions))
    }

    @Test
    fun `collapsed fold markers are emitted only for folds that hide drawings`() {
        val hiddenStroke = strokeWithOffsets(40, 42, 45)
        val visibleStroke = strokeWithOffsets(10, 12, 15)
        val regions = listOf(
            CollapsedFoldRegionSnapshot(30, 60, Point(10, 10), 18),
            CollapsedFoldRegionSnapshot(100, 140, Point(20, 20), 18)
        )

        val markers = DrawingViewportTools.collapsedFoldMarkersFor(listOf(hiddenStroke, visibleStroke), regions)

        assertEquals(1, markers.size)
        assertEquals(30, markers.single().startOffset)
    }

    private fun strokeWithOffsets(vararg offsets: Int): StrokePath {
        return StrokePath(
            color = Color.RED,
            width = 3.5f,
            points = offsets.map { offset ->
                AnchorPoint(
                    line = 1,
                    column = 1,
                    dx = 10,
                    dy = 10,
                    offset = offset
                )
            }.toMutableList()
        )
    }
}
