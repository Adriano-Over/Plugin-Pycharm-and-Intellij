package com.drawing

import com.intellij.openapi.editor.Document
import java.util.WeakHashMap

data class DrawingHistorySnapshot(
    val strokes: List<StrokePath>,
    val rasterFills: List<RasterFillPath>
)

class DrawingHistoryStore(
    private val maxUndoDepth: Int = 50
) {
    private val undoByDocument = WeakHashMap<Document, MutableList<DrawingHistorySnapshot>>()
    private val redoByDocument = WeakHashMap<Document, MutableList<DrawingHistorySnapshot>>()

    fun saveStateForUndo(
        document: Document,
        currentStrokes: List<StrokePath>,
        currentRasterFills: List<RasterFillPath> = emptyList()
    ) {
        val undo = undoByDocument.getOrPut(document) { mutableListOf() }
        undo += snapshot(currentStrokes, currentRasterFills)
        if (undo.size > maxUndoDepth) {
            undo.removeAt(0)
        }
        redoByDocument.getOrPut(document) { mutableListOf() }.clear()
    }

    fun restoreUndo(
        document: Document,
        currentStrokes: List<StrokePath>,
        currentRasterFills: List<RasterFillPath> = emptyList()
    ): DrawingHistorySnapshot? {
        val undo = undoByDocument.getOrPut(document) { mutableListOf() }
        if (undo.isEmpty()) return null

        val redo = redoByDocument.getOrPut(document) { mutableListOf() }
        redo += snapshot(currentStrokes, currentRasterFills)

        return undo.removeAt(undo.lastIndex).deepCopy()
    }

    fun restoreRedo(
        document: Document,
        currentStrokes: List<StrokePath>,
        currentRasterFills: List<RasterFillPath> = emptyList()
    ): DrawingHistorySnapshot? {
        val redo = redoByDocument.getOrPut(document) { mutableListOf() }
        if (redo.isEmpty()) return null

        val undo = undoByDocument.getOrPut(document) { mutableListOf() }
        undo += snapshot(currentStrokes, currentRasterFills)

        return redo.removeAt(redo.lastIndex).deepCopy()
    }

    fun discardLastUndo(document: Document?) {
        if (document == null) return
        val undo = undoByDocument[document] ?: return
        if (undo.isNotEmpty()) {
            undo.removeAt(undo.lastIndex)
        }
    }

    fun canUndo(document: Document?): Boolean {
        if (document == null) return false
        return undoByDocument[document]?.isNotEmpty() == true
    }

    fun canRedo(document: Document?): Boolean {
        if (document == null) return false
        return redoByDocument[document]?.isNotEmpty() == true
    }

    private fun snapshot(strokes: List<StrokePath>, rasterFills: List<RasterFillPath>): DrawingHistorySnapshot =
        DrawingHistorySnapshot(
            strokes = strokes.map { it.deepCopy() },
            rasterFills = rasterFills.map { it.deepCopy() }
        )

    private fun DrawingHistorySnapshot.deepCopy(): DrawingHistorySnapshot =
        DrawingHistorySnapshot(
            strokes = strokes.map { it.deepCopy() },
            rasterFills = rasterFills.map { it.deepCopy() }
        )
}
