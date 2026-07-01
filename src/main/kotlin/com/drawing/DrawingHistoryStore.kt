package com.drawing

import com.intellij.openapi.editor.Document
import java.util.WeakHashMap

data class DrawingHistorySnapshot(
    val strokes: List<StrokePath>,
    val rasterFills: List<RasterFillPath>,
    val annotations: List<AnnotationPath> = emptyList()
)

class DrawingHistoryStore(
    private val maxUndoDepth: Int = 50
) {
    private val undoByDocument = WeakHashMap<Document, MutableList<DrawingHistorySnapshot>>()
    private val redoByDocument = WeakHashMap<Document, MutableList<DrawingHistorySnapshot>>()

    fun saveStateForUndo(
        document: Document,
        currentStrokes: List<StrokePath>,
        currentRasterFills: List<RasterFillPath> = emptyList(),
        currentAnnotations: List<AnnotationPath> = emptyList()
    ) {
        val undo = undoByDocument.getOrPut(document) { mutableListOf() }
        undo += snapshot(currentStrokes, currentRasterFills, currentAnnotations)
        if (undo.size > maxUndoDepth) {
            undo.removeAt(0)
        }
        redoByDocument.getOrPut(document) { mutableListOf() }.clear()
    }

    fun restoreUndo(
        document: Document,
        currentStrokes: List<StrokePath>,
        currentRasterFills: List<RasterFillPath> = emptyList(),
        currentAnnotations: List<AnnotationPath> = emptyList()
    ): DrawingHistorySnapshot? {
        val undo = undoByDocument.getOrPut(document) { mutableListOf() }
        if (undo.isEmpty()) return null

        val redo = redoByDocument.getOrPut(document) { mutableListOf() }
        redo += snapshot(currentStrokes, currentRasterFills, currentAnnotations)

        return undo.removeAt(undo.lastIndex).deepCopy()
    }

    fun restoreRedo(
        document: Document,
        currentStrokes: List<StrokePath>,
        currentRasterFills: List<RasterFillPath> = emptyList(),
        currentAnnotations: List<AnnotationPath> = emptyList()
    ): DrawingHistorySnapshot? {
        val redo = redoByDocument.getOrPut(document) { mutableListOf() }
        if (redo.isEmpty()) return null

        val undo = undoByDocument.getOrPut(document) { mutableListOf() }
        undo += snapshot(currentStrokes, currentRasterFills, currentAnnotations)

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

    private fun snapshot(
        strokes: List<StrokePath>,
        rasterFills: List<RasterFillPath>,
        annotations: List<AnnotationPath>
    ): DrawingHistorySnapshot =
        DrawingHistorySnapshot(
            strokes = strokes.map { it.deepCopy() },
            rasterFills = rasterFills.map { it.deepCopy() },
            annotations = annotations.map { it.deepCopy() }
        )

    private fun DrawingHistorySnapshot.deepCopy(): DrawingHistorySnapshot =
        DrawingHistorySnapshot(
            strokes = strokes.map { it.deepCopy() },
            rasterFills = rasterFills.map { it.deepCopy() },
            annotations = annotations.map { it.deepCopy() }
        )
}
