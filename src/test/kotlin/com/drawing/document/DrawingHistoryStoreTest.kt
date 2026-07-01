package com.drawing.persistence

import com.drawing.AnchorPoint
import com.drawing.DrawingHistoryStore
import com.drawing.RasterFillPath
import com.drawing.StrokePath
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.awt.Color

class DrawingHistoryStoreTest {
    @Test
    fun `undo and redo snapshots include raster fills`() {
        val document = testDocument()
        val history = DrawingHistoryStore()
        val initialFill = rasterFill(id = 10L, dx = 4)
        val currentFill = rasterFill(id = 11L, dx = 20)
        val currentStroke = StrokePath(
            color = Color.RED,
            width = 3.5f,
            points = mutableListOf(AnchorPoint(line = 0, column = 0, dx = 1, dy = 2))
        )

        history.saveStateForUndo(document, emptyList(), listOf(initialFill))

        val restoredUndo = history.restoreUndo(document, listOf(currentStroke), listOf(currentFill))!!
        assertEquals(emptyList<StrokePath>(), restoredUndo.strokes)
        assertEquals(listOf(10L), restoredUndo.rasterFills.map { it.id })
        assertEquals(4, restoredUndo.rasterFills.single().anchor.dx)

        val restoredRedo = history.restoreRedo(document, restoredUndo.strokes, restoredUndo.rasterFills)!!
        assertEquals(listOf(currentStroke.id), restoredRedo.strokes.map { it.id })
        assertEquals(listOf(11L), restoredRedo.rasterFills.map { it.id })
        assertEquals(20, restoredRedo.rasterFills.single().anchor.dx)
    }

    private fun rasterFill(id: Long, dx: Int): RasterFillPath {
        return RasterFillPath(
            id = id,
            color = Color.GREEN,
            anchor = AnchorPoint(line = 0, column = 0, dx = dx, dy = 8),
            width = 2,
            height = 2,
            pngBase64 = "unused-in-history-test"
        )
    }
}
