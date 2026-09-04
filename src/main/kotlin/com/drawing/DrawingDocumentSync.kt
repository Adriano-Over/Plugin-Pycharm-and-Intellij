package com.drawing

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
    private val currentRasterFills: () -> List<RasterFillPath> = { emptyList() },
    private val currentAnnotations: () -> List<AnnotationPath> = { emptyList() },
    private val onDocumentStrokesRemapped: (Document) -> Unit,
    private val repaintCanvas: () -> Unit,
    private val documentChangeUiDebounceMs: Int = 0
) {
    private var boundDocument: Document? = null
    private var documentListener: DocumentListener? = null
    private var persistenceTimer: Timer? = null
    private var documentChangeUiTimer: Timer? = null
    private var pendingUiDocument: Document? = null
    private var loadedFilePath: String? = null
    private val pendingDocumentEdits = mutableListOf<DocumentAnchorEdit>()

    fun bindDocumentListener(document: Document) {
        unbindDocumentListener()

        val listener = object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                pendingDocumentEdits += DocumentAnchorEdit.from(event)
                scheduleDocumentChangeFlush(document)
            }
        }

        document.addDocumentListener(listener)
        boundDocument = document
        documentListener = listener
    }

    fun unbindDocumentListener() {
        documentChangeUiTimer?.stop()
        flushPendingDocumentChanges()
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
        loadedFilePath = filePath
        val loaded = strokeStore.loadPersistedStrokes(filePath, document) { doc, anchor ->
            coordinateMapper.normalizeAnchor(doc, anchor)
        }
        val lineClearanceCache = mutableMapOf<Int, Int?>()
        val shifted = shiftRigidStrokesOutOfCodeText(loaded, lineClearanceCache)
        val shiftedRasterFills = shiftRasterFillsOutOfCodeText(strokeStore.currentRasterFills(document), lineClearanceCache)
        val shiftedAnnotations = shiftAnnotationsOutOfCodeText(strokeStore.currentAnnotations(document), lineClearanceCache)
        if (shifted || shiftedRasterFills || shiftedAnnotations) {
            schedulePersistCurrentStrokes()
        }
        onDocumentStrokesRemapped(document)
        repaintCanvas()
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
        val filePath = currentFilePath() ?: return
        val previousPath = loadedFilePath
        if (previousPath != null && previousPath != filePath) {
            strokeStore.movePersistedDrawing(previousPath, filePath)
        }
        strokeStore.persistDrawing(filePath, currentStrokes(), currentRasterFills(), currentAnnotations())
        loadedFilePath = filePath
    }

    private fun scheduleDocumentChangeFlush(document: Document) {
        pendingUiDocument = document
        if (documentChangeUiDebounceMs <= 0) {
            flushPendingDocumentChanges()
            return
        }

        val timer = documentChangeUiTimer ?: Timer(documentChangeUiDebounceMs) {
            flushPendingDocumentChanges()
        }.also {
            it.isRepeats = false
            documentChangeUiTimer = it
        }

        timer.restart()
    }

    private fun flushPendingDocumentChanges() {
        val document = pendingUiDocument ?: return
        val edits = pendingDocumentEdits.toList()
        pendingDocumentEdits.clear()
        pendingUiDocument = null
        if (edits.isEmpty()) return

        val strokes = strokeStore.currentStrokes(document)
        val fills = strokeStore.currentRasterFills(document)
        val annotations = strokeStore.currentAnnotations(document)
        coordinateMapper.remapDrawingAnchorsForDocumentChanges(
            document = document,
            edits = edits,
            strokes = strokes,
            fills = fills,
            annotations = annotations
        )

        val lineClearanceCache = mutableMapOf<Int, Int?>()
        shiftRigidStrokesOutOfCodeText(strokes, lineClearanceCache)
        shiftRasterFillsOutOfCodeText(fills, lineClearanceCache)
        shiftAnnotationsOutOfCodeText(annotations, lineClearanceCache)
        onDocumentStrokesRemapped(document)
        repaintCanvas()
        schedulePersistCurrentStrokes()
    }

    private fun shiftRigidStrokesOutOfCodeText(
        strokes: List<StrokePath>,
        lineClearanceCache: MutableMap<Int, Int?>
    ): Boolean {
        var shifted = false
        for (group in strokes.groupBy(::objectGroupKey).values) {
            val shiftX = group.maxOfOrNull { stroke ->
                coordinateMapper.requiredShiftOutOfCodeText(stroke, lineClearanceCache)
            } ?: 0
            if (shiftX <= 0) continue

            for (stroke in group) {
                coordinateMapper.shiftStrokeHorizontally(stroke, shiftX)
            }
            shifted = true
        }
        return shifted
    }

    private fun shiftRasterFillsOutOfCodeText(
        fills: List<RasterFillPath>,
        lineClearanceCache: MutableMap<Int, Int?>
    ): Boolean {
        var shifted = false
        for (group in fills.groupBy(::rasterFillGroupKey).values) {
            val shiftX = group.maxOfOrNull { fill ->
                coordinateMapper.requiredShiftOutOfCodeText(fill, lineClearanceCache)
            } ?: 0
            if (shiftX <= 0) continue

            for (fill in group) {
                coordinateMapper.shiftRasterFillHorizontally(fill, shiftX)
            }
            shifted = true
        }
        return shifted
    }

    private fun shiftAnnotationsOutOfCodeText(
        annotations: List<AnnotationPath>,
        lineClearanceCache: MutableMap<Int, Int?>
    ): Boolean {
        var shifted = false
        for (group in annotations.groupBy(::annotationGroupKey).values) {
            val shiftX = group.maxOfOrNull { annotation ->
                coordinateMapper.requiredShiftOutOfCodeText(annotation, lineClearanceCache)
            } ?: 0
            if (shiftX <= 0) continue

            for (annotation in group) {
                coordinateMapper.shiftAnnotationHorizontally(annotation, shiftX)
            }
            shifted = true
        }
        return shifted
    }

    private fun objectGroupKey(stroke: StrokePath): Long {
        return if (stroke.objectGroupId != 0L) stroke.objectGroupId else -stroke.id
    }

    private fun rasterFillGroupKey(fill: RasterFillPath): Long {
        return if (fill.objectGroupId != 0L) fill.objectGroupId else -fill.id
    }

    private fun annotationGroupKey(annotation: AnnotationPath): Long {
        return if (annotation.objectGroupId != 0L) annotation.objectGroupId else -annotation.id
    }
}

