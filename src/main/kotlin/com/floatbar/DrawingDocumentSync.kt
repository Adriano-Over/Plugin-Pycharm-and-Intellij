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
            override fun beforeDocumentChange(event: DocumentEvent) {
                migrateFreehandStrokes(strokeStore.currentStrokes(document))
            }

            override fun documentChanged(event: DocumentEvent) {
                val strokes = strokeStore.currentStrokes(document)
                coordinateMapper.remapAnchorsForDocumentChange(
                    document = document,
                    event = event,
                    strokes = strokes
                )
                shiftRigidStrokesOutOfCodeText(strokes)
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
        val loaded = strokeStore.loadPersistedStrokes(filePath, document) { doc, anchor ->
            coordinateMapper.normalizeAnchor(doc, anchor)
        }
        val migrated = migrateFreehandStrokes(loaded)
        val shifted = shiftRigidStrokesOutOfCodeText(loaded)
        if (migrated || shifted) {
            schedulePersistCurrentStrokes()
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

    private fun migrateFreehandStrokes(strokes: List<StrokePath>): Boolean {
        var migrated = false
        for (stroke in strokes) {
            if (!DrawingViewportTools.shouldUseRigidObjectAnchorForFreehand(stroke, coordinateMapper::toViewPoint)) {
                continue
            }
            if (coordinateMapper.reanchorStrokeToObjectAnchor(stroke)) {
                migrated = true
            }
        }
        return migrated
    }

    private fun shiftRigidStrokesOutOfCodeText(strokes: List<StrokePath>): Boolean {
        var shifted = false
        for (group in strokes.groupBy(::objectGroupKey).values) {
            val shiftX = group.maxOfOrNull { stroke ->
                coordinateMapper.requiredShiftOutOfCodeText(stroke)
            } ?: 0
            if (shiftX <= 0) continue

            for (stroke in group) {
                coordinateMapper.shiftStrokeHorizontally(stroke, shiftX)
            }
            shifted = true
        }
        return shifted
    }

    private fun objectGroupKey(stroke: StrokePath): Long {
        return if (stroke.objectGroupId != 0L) stroke.objectGroupId else -stroke.id
    }
}

