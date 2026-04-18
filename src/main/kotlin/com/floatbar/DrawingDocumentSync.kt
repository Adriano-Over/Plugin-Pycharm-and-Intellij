package com.floatbar

import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import javax.swing.Timer

class DrawingDocumentSync(
    private val coordinateMapper: DrawingCoordinateMapper,
    private val strokeStore: DrawingStrokeStore,
    private val persistenceDebounceMs: Int,
    private val currentEditor: () -> Editor?,
    private val currentFilePath: () -> String?,
    private val currentStrokes: () -> List<StrokePath>,
    private val onDocumentStrokesRemapped: (Document) -> Unit,
    private val repaintCanvas: () -> Unit
) {
    private var boundDocument: Document? = null
    private var documentListener: DocumentListener? = null
    private var persistenceTimer: Timer? = null

    fun bindDocumentListener(document: Document) {
        unbindDocumentListener()

        val listener = object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                coordinateMapper.remapAnchorsForDocumentChange(
                    document = document,
                    event = event,
                    strokes = strokeStore.currentStrokes(document)
                )
                onDocumentStrokesRemapped(document)
                schedulePersistCurrentStrokes()
                repaintCanvas()
            }
        }

        document.addDocumentListener(listener)
        boundDocument = document
        documentListener = listener
    }

    fun unbindDocumentListener() {
        val document = boundDocument
        val listener = documentListener
        if (document != null && listener != null) {
            document.removeDocumentListener(listener)
        }
        boundDocument = null
        documentListener = null
    }

    fun cancelPendingPersistence() {
        persistenceTimer?.stop()
    }

    fun loadPersistedStrokes() {
        val editor = currentEditor() ?: return
        val filePath = currentFilePath() ?: return
        val document = editor.document
        strokeStore.loadPersistedStrokes(filePath, document) { doc, anchor ->
            coordinateMapper.normalizeAnchor(doc, anchor)
        }
        onDocumentStrokesRemapped(document)
    }

    fun schedulePersistCurrentStrokes() {
        val filePath = currentFilePath() ?: return
        if (filePath.isEmpty()) return

        val timer = persistenceTimer ?: Timer(persistenceDebounceMs) {
            persistCurrentStrokes()
        }.also {
            it.isRepeats = false
            persistenceTimer = it
        }

        timer.restart()
    }

    fun persistCurrentStrokes() {
        cancelPendingPersistence()
        strokeStore.persistStrokes(currentFilePath(), currentStrokes())
    }
}
