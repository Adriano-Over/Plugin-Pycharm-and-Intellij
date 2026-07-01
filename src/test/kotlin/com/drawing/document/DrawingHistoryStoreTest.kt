package com.drawing.persistence

import com.drawing.AnchorPoint
import com.drawing.AnnotationKind
import com.drawing.AnnotationPath
import com.drawing.DrawingHistoryStore
import com.drawing.RasterFillPath
import com.drawing.StrokePath
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.awt.Color

class DrawingHistoryStoreTest {
    @Test
    fun `undo and redo snapshots include raster fills and annotations`() {
        val document = testDocument()
        val history = DrawingHistoryStore()
        val initialFill = rasterFill(id = 10L, dx = 4)
        val currentFill = rasterFill(id = 11L, dx = 20)
        val initialAnnotation = annotation(id = 20L, dx = 6)
        val currentAnnotation = annotation(id = 21L, dx = 30)
        val currentStroke = StrokePath(
            color = Color.RED,
            width = 3.5f,
            points = mutableListOf(AnchorPoint(line = 0, column = 0, dx = 1, dy = 2))
        )

        history.saveStateForUndo(document, emptyList(), listOf(initialFill), listOf(initialAnnotation))

        val restoredUndo = history.restoreUndo(document, listOf(currentStroke), listOf(currentFill), listOf(currentAnnotation))!!
        assertEquals(emptyList<StrokePath>(), restoredUndo.strokes)
        assertEquals(listOf(10L), restoredUndo.rasterFills.map { it.id })
        assertEquals(4, restoredUndo.rasterFills.single().anchor.dx)
        assertEquals(listOf(20L), restoredUndo.annotations.map { it.id })
        assertEquals(6, restoredUndo.annotations.single().anchor.dx)

        val restoredRedo = history.restoreRedo(document, restoredUndo.strokes, restoredUndo.rasterFills, restoredUndo.annotations)!!
        assertEquals(listOf(currentStroke.id), restoredRedo.strokes.map { it.id })
        assertEquals(listOf(11L), restoredRedo.rasterFills.map { it.id })
        assertEquals(20, restoredRedo.rasterFills.single().anchor.dx)
        assertEquals(listOf(21L), restoredRedo.annotations.map { it.id })
        assertEquals(30, restoredRedo.annotations.single().anchor.dx)
    }

    @Test
    fun `duplicate undo states are not saved`() {
        val document = testDocument()
        val history = DrawingHistoryStore()
        val stroke = StrokePath(
            color = Color.RED,
            width = 3.5f,
            points = mutableListOf(AnchorPoint(line = 0, column = 0, dx = 1, dy = 2))
        )

        history.saveStateForUndo(document, listOf(stroke))
        history.saveStateForUndo(document, listOf(stroke))

        assertEquals(true, history.canUndo(document))
        history.restoreUndo(document, emptyList())
        assertEquals(false, history.canUndo(document), "Identical consecutive states should only create one undo entry")
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

    private fun annotation(id: Long, dx: Int): AnnotationPath {
        return AnnotationPath(
            id = id,
            text = "Note",
            color = Color.BLUE,
            anchor = AnchorPoint(line = 0, column = 0, dx = dx, dy = 10),
            width = 80,
            height = 32,
            kind = AnnotationKind.TEXT,
            objectGroupId = id
        )
    }
}
