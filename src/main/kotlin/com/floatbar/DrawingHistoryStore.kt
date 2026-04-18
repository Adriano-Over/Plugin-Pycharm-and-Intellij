package com.floatbar

import com.intellij.openapi.editor.Document

class DrawingHistoryStore(
    private val maxUndoDepth: Int = 50
) {
    private val undoByDocument = mutableMapOf<Document, MutableList<List<StrokePath>>>()
    private val redoByDocument = mutableMapOf<Document, MutableList<List<StrokePath>>>()

    fun saveStateForUndo(document: Document, currentStrokes: List<StrokePath>) {
        val undo = undoByDocument.getOrPut(document) { mutableListOf() }
        undo += snapshot(currentStrokes)
        if (undo.size > maxUndoDepth) {
            undo.removeAt(0)
        }
        redoByDocument.getOrPut(document) { mutableListOf() }.clear()
    }

    fun restoreUndo(document: Document, currentStrokes: List<StrokePath>): List<StrokePath>? {
        val undo = undoByDocument.getOrPut(document) { mutableListOf() }
        if (undo.isEmpty()) return null

        val redo = redoByDocument.getOrPut(document) { mutableListOf() }
        redo += snapshot(currentStrokes)

        return snapshot(undo.removeAt(undo.lastIndex))
    }

    fun restoreRedo(document: Document, currentStrokes: List<StrokePath>): List<StrokePath>? {
        val redo = redoByDocument.getOrPut(document) { mutableListOf() }
        if (redo.isEmpty()) return null

        val undo = undoByDocument.getOrPut(document) { mutableListOf() }
        undo += snapshot(currentStrokes)

        return snapshot(redo.removeAt(redo.lastIndex))
    }

    fun canUndo(document: Document?): Boolean {
        if (document == null) return false
        return undoByDocument[document]?.isNotEmpty() == true
    }

    fun canRedo(document: Document?): Boolean {
        if (document == null) return false
        return redoByDocument[document]?.isNotEmpty() == true
    }

    fun clear(document: Document) {
        undoByDocument.remove(document)
        redoByDocument.remove(document)
    }

    private fun snapshot(strokes: List<StrokePath>): List<StrokePath> =
        strokes.map { it.deepCopy() }
}
