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
                for (fill in strokeStore.currentRasterFills(document)) {
                    coordinateMapper.remapAnchorForDocumentChange(document, event, fill.anchor)
                }
                for (annotation in strokeStore.currentAnnotations(document)) {
                    coordinateMapper.remapAnchorForDocumentChange(document, event, annotation.anchor)
                }
                val fills = strokeStore.currentRasterFills(document)
                val annotations = strokeStore.currentAnnotations(document)
                shiftRigidStrokesOutOfCodeText(strokes)
                shiftRasterFillsOutOfCodeText(fills)
                shiftAnnotationsOutOfCodeText(annotations)
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
        val migratedText = migrateSemanticTextStrokesToAnnotations(document, loaded)
        val migrated = migrateFreehandStrokes(loaded)
        val shifted = shiftRigidStrokesOutOfCodeText(loaded)
        val shiftedRasterFills = shiftRasterFillsOutOfCodeText(strokeStore.currentRasterFills(document))
        val shiftedAnnotations = shiftAnnotationsOutOfCodeText(strokeStore.currentAnnotations(document))
        if (migratedText || migrated || shifted || shiftedRasterFills || shiftedAnnotations) {
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
        strokeStore.persistDrawing(currentFilePath(), currentStrokes(), currentRasterFills(), currentAnnotations())
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

    private fun shiftRasterFillsOutOfCodeText(fills: List<RasterFillPath>): Boolean {
        var shifted = false
        for (group in fills.groupBy(::rasterFillGroupKey).values) {
            val shiftX = group.maxOfOrNull { fill ->
                coordinateMapper.requiredShiftOutOfCodeText(fill)
            } ?: 0
            if (shiftX <= 0) continue

            for (fill in group) {
                coordinateMapper.shiftRasterFillHorizontally(fill, shiftX)
            }
            shifted = true
        }
        return shifted
    }

    private fun shiftAnnotationsOutOfCodeText(annotations: List<AnnotationPath>): Boolean {
        var shifted = false
        for (group in annotations.groupBy(::annotationGroupKey).values) {
            val shiftX = group.maxOfOrNull { annotation ->
                coordinateMapper.requiredShiftOutOfCodeText(annotation)
            } ?: 0
            if (shiftX <= 0) continue

            for (annotation in group) {
                coordinateMapper.shiftAnnotationHorizontally(annotation, shiftX)
            }
            shifted = true
        }
        return shifted
    }

    private fun migrateSemanticTextStrokesToAnnotations(document: Document, strokes: MutableList<StrokePath>): Boolean {
        val annotations = strokeStore.currentAnnotations(document)
        val existingGroups = annotations.map { if (it.objectGroupId != 0L) it.objectGroupId else it.id }.toSet()
        val groups = strokes
            .filter { it.annotationText?.isNotBlank() == true }
            .groupBy { stroke -> if (stroke.objectGroupId != 0L) stroke.objectGroupId else stroke.id }
        if (groups.isEmpty()) return false

        var migrated = false
        val migratedStrokeIds = linkedSetOf<Long>()
        for ((groupId, group) in groups) {
            if (groupId in existingGroups || group.isEmpty()) continue
            val representative = group.first()
            val text = representative.annotationText?.trim().orEmpty()
            if (text.isEmpty()) continue
            val style = representative.annotationTextStyle ?: BalloonTextStyle.SOLID
            val kind = if (group.any { it.kind == ShapeKind.BALLOON }) AnnotationKind.BALLOON else AnnotationKind.TEXT
            val geometryBounds = viewBoundsFor(group) ?: continue
            val sizeBounds = representative.annotationBounds?.takeIf { it.width > 0 && it.height > 0 }
            val width = (sizeBounds?.width ?: geometryBounds.width).coerceAtLeast(1)
            val height = (sizeBounds?.height ?: geometryBounds.height).coerceAtLeast(1)
            val topLeft = sizeBounds?.let { java.awt.Point(it.x, it.y) }
                ?: java.awt.Point(geometryBounds.x, geometryBounds.y)
            val anchor = coordinateMapper.viewPointToAnchor(topLeft, allowCodeArea = true) ?: continue

            annotations += AnnotationPath(
                id = groupId,
                text = text,
                color = representative.color,
                anchor = anchor,
                width = width,
                height = height,
                kind = kind,
                style = style,
                objectGroupId = groupId
            )
            migratedStrokeIds += group.map { it.id }
            migrated = true
        }

        if (migratedStrokeIds.isNotEmpty()) {
            strokes.removeAll { it.id in migratedStrokeIds }
        }
        return migrated
    }

    private fun viewBoundsFor(strokes: List<StrokePath>): java.awt.Rectangle? {
        var union: java.awt.Rectangle? = null
        for (stroke in strokes) {
            val bounds = DrawingViewportTools.computeStrokeViewBounds(
                stroke = stroke,
                toViewPoint = coordinateMapper::toViewPoint
            ) ?: continue
            union = if (union == null) java.awt.Rectangle(bounds) else union.apply { add(bounds) }
        }
        return union
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

