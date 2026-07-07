package com.drawing

import com.intellij.openapi.editor.Editor
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Point
import java.awt.Rectangle
import java.awt.geom.Area
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class DrawingCanvasController(
    private val canvas: JPanel,
    private val editorProvider: () -> Editor?,
    private val currentStrokesProvider: () -> MutableList<StrokePath>,
    private val historyStore: DrawingHistoryStore,
    private val strokeWorkspace: DrawingStrokeWorkspace,
    private val documentSync: DrawingDocumentSync,
    private val coordinateMapper: DrawingCoordinateMapper,
    private val strokePathTools: DrawingStrokePathTools,
    private val drawColorProvider: () -> Color,
    private val selectedShapeKindProvider: () -> ShapeKind,
    private val currentStrokeGetter: () -> StrokePath?,
    private val currentStrokeSetter: (StrokePath?) -> Unit,
    private val shapePreviewGetter: () -> StrokePath?,
    private val shapePreviewSetter: (StrokePath?) -> Unit,
    private val refreshHistoryState: () -> Unit,
    private val canvasPadding: Int,
    private val dirtyPaddingPx: Int,
    private val eraseRadius: Double,
    private val freehandMinPointDistancePx: Double,
    private val eraseMinMovePx: Double,
    private val shapeEdgeSpacing: Double,
    private val ellipseSegments: Int,
    private val balloonTextStyleProvider: () -> BalloonTextStyle = { BalloonTextStyle.SOLID },
    private val balloonTextEditor: (Rectangle, (String?) -> Unit) -> Unit = { _, commit -> commit(null) },
    private val dirtyRepaintScheduler: DrawingDirtyRepaintScheduler? = null
) {
    private val drawStrokeWidth = 3.5f
    private val shapePreviewDirtyPaddingPx = dirtyPaddingPx + 120
    private val minShapeCommitSizePx = 8
    private val minFreehandCommitLengthPx = 6.0
    private val selectionHitPaddingPx = 8
    private val selectionRepaintPaddingPx = dirtyPaddingPx + 18
    private val selectedStrokeIds = linkedSetOf<Long>()
    private val selectedRasterFillIds = linkedSetOf<Long>()
    private val selectedAnnotationIds = linkedSetOf<Long>()
    private var selectionMoveUndoSaved = false
    private var eraseUndoSaved = false
    private var selectionMarqueeStart: Point? = null
    private var selectionMarqueeBounds: Rectangle? = null

    fun clearCanvas() {
        val document = editorProvider()?.document ?: return
        val dirtyBounds = drawingViewBounds(
            strokes = currentStrokesProvider(),
            rasterFills = strokeWorkspace.currentRasterFills(),
            annotations = strokeWorkspace.currentAnnotations(),
            extraPadding = dirtyPaddingPx
        )
        saveStateForUndo()
        strokeWorkspace.clearDocument(document)
        currentStrokeSetter(null)
        shapePreviewSetter(null)
        selectedStrokeIds.clear()
        selectedRasterFillIds.clear()
        selectedAnnotationIds.clear()
        selectionMoveUndoSaved = false
        eraseUndoSaved = false
        selectionMarqueeStart = null
        selectionMarqueeBounds = null
        documentSync.persistCurrentStrokes()
        refreshHistoryState()
        DrawingViewportTools.repaintRect(canvas, dirtyBounds)
        canvas.repaint()
    }

    fun undo() {
        val document = editorProvider()?.document ?: return
        val beforeBounds = drawingViewBounds(
            strokes = currentStrokesProvider(),
            rasterFills = strokeWorkspace.currentRasterFills(),
            annotations = strokeWorkspace.currentAnnotations(),
            extraPadding = dirtyPaddingPx
        )
        val restored = historyStore.restoreUndo(
            document = document,
            currentStrokes = currentStrokesProvider(),
            currentRasterFills = strokeWorkspace.currentRasterFills(),
            currentAnnotations = strokeWorkspace.currentAnnotations()
        ) ?: return
        val afterBounds = drawingViewBounds(restored.strokes, restored.rasterFills, restored.annotations, dirtyPaddingPx)
        strokeWorkspace.setStrokes(document, restored.strokes.toMutableList())
        strokeWorkspace.setRasterFills(document, restored.rasterFills.toMutableList())
        strokeWorkspace.setAnnotations(document, restored.annotations.toMutableList())
        strokeWorkspace.rebuildStrokeBounds(document)
        strokeWorkspace.resetStrokeGeometryCache(document)
        clearSelection(repaint = false)
        documentSync.persistCurrentStrokes()
        refreshHistoryState()
        DrawingViewportTools.repaintRect(canvas, DrawingViewportTools.unionRectangles(beforeBounds, afterBounds))
        canvas.repaint()
    }

    fun redo() {
        val document = editorProvider()?.document ?: return
        val beforeBounds = drawingViewBounds(
            strokes = currentStrokesProvider(),
            rasterFills = strokeWorkspace.currentRasterFills(),
            annotations = strokeWorkspace.currentAnnotations(),
            extraPadding = dirtyPaddingPx
        )
        val restored = historyStore.restoreRedo(
            document = document,
            currentStrokes = currentStrokesProvider(),
            currentRasterFills = strokeWorkspace.currentRasterFills(),
            currentAnnotations = strokeWorkspace.currentAnnotations()
        ) ?: return
        val afterBounds = drawingViewBounds(restored.strokes, restored.rasterFills, restored.annotations, dirtyPaddingPx)
        strokeWorkspace.setStrokes(document, restored.strokes.toMutableList())
        strokeWorkspace.setRasterFills(document, restored.rasterFills.toMutableList())
        strokeWorkspace.setAnnotations(document, restored.annotations.toMutableList())
        strokeWorkspace.rebuildStrokeBounds(document)
        strokeWorkspace.resetStrokeGeometryCache(document)
        clearSelection(repaint = false)
        documentSync.persistCurrentStrokes()
        refreshHistoryState()
        DrawingViewportTools.repaintRect(canvas, DrawingViewportTools.unionRectangles(beforeBounds, afterBounds))
        canvas.repaint()
    }

    fun selectedStrokeIdsSnapshot(): Set<Long> {
        return selectedStrokeIds.toSet()
    }

    fun selectedRasterFillIdsSnapshot(): Set<Long> {
        return selectedRasterFillIds.toSet()
    }

    fun selectedAnnotationIdsSnapshot(): Set<Long> {
        return selectedAnnotationIds.toSet()
    }

    fun selectionMarqueeSnapshot(): Rectangle? {
        return selectionMarqueeBounds?.let { Rectangle(it) }
    }

    fun isSelectionMoveInProgress(): Boolean {
        return selectionMoveUndoSaved
    }

    fun clearSelection(repaint: Boolean = true) {
        if (selectedStrokeIds.isEmpty() && selectedRasterFillIds.isEmpty() && selectedAnnotationIds.isEmpty() && selectionMarqueeBounds == null) return
        val oldSelection = selectedStrokes()
        val oldRasterSelection = selectedRasterFills()
        val oldAnnotationSelection = selectedAnnotations()
        val oldMarquee = selectionMarqueeBounds?.let { Rectangle(it) }
        selectedStrokeIds.clear()
        selectedRasterFillIds.clear()
        selectedAnnotationIds.clear()
        selectionMarqueeStart = null
        selectionMarqueeBounds = null
        selectionMoveUndoSaved = false
        eraseUndoSaved = false
        if (repaint) {
            repaintSelection(oldSelection, oldRasterSelection, oldAnnotationSelection)
            DrawingViewportTools.repaintRect(canvas, oldMarquee?.grown(selectionRepaintPaddingPx))
        }
    }

    fun cancelActiveInteractions() {
        clearSelection()

        val currentStroke = currentStrokeGetter()
        if (currentStroke != null) {
            val viewPoints = currentStroke.points.mapNotNull(coordinateMapper::toViewPoint)
            currentStrokesProvider().remove(currentStroke)
            strokeWorkspace.currentStrokeBounds().remove(currentStroke.id)
            strokeWorkspace.currentStrokeGeometries().remove(currentStroke.id)
            historyStore.discardLastUndo(editorProvider()?.document)
            currentStrokeSetter(null)
            if (viewPoints.isNotEmpty()) {
                DrawingViewportTools.repaintAround(canvas, viewPoints, dirtyPaddingPx)
            }
        }

        val preview = shapePreviewGetter()
        if (preview != null) {
            val previewPoints = preview.points.mapNotNull(coordinateMapper::toViewPoint)
            shapePreviewSetter(null)
            if (previewPoints.isNotEmpty()) {
                DrawingViewportTools.repaintAround(canvas, previewPoints, shapePreviewDirtyPaddingPx)
            }
        }

        selectionMoveUndoSaved = false
        eraseUndoSaved = false
        refreshHistoryState()
    }

    fun handleSelectPressed(safePoint: Point) {
        val oldSelection = selectedStrokes()
        val oldRasterSelection = selectedRasterFills()
        val oldAnnotationSelection = selectedAnnotations()
        val hit = findTopmostDrawingAt(safePoint)
        val hitAlreadySelected = when (hit) {
            is SelectionHit.StrokeHit -> hit.stroke.id in selectedStrokeIds
            is SelectionHit.RasterFillHit -> hit.fill.id in selectedRasterFillIds
            is SelectionHit.AnnotationHit -> hit.annotation.id in selectedAnnotationIds
            null -> false
        }

        selectionMarqueeStart = null
        selectionMarqueeBounds = null
        selectionMoveUndoSaved = false

        if (!hitAlreadySelected) {
            selectedStrokeIds.clear()
            selectedRasterFillIds.clear()
            selectedAnnotationIds.clear()
            when (hit) {
                is SelectionHit.StrokeHit -> selectedStrokeIds += selectedGroupFor(hit.stroke).map { it.id }
                is SelectionHit.RasterFillHit -> selectedRasterFillIds += selectedGroupFor(hit.fill).map { it.id }
                is SelectionHit.AnnotationHit -> selectedAnnotationIds += selectedGroupFor(hit.annotation).map { it.id }
                null -> {
                    selectionMarqueeStart = Point(safePoint)
                    selectionMarqueeBounds = Rectangle(safePoint.x, safePoint.y, 1, 1)
                }
            }
        }

        val newSelection = selectedStrokes()
        val newRasterSelection = selectedRasterFills()
        val newAnnotationSelection = selectedAnnotations()
        repaintSelection(
            oldSelection + newSelection,
            oldRasterSelection + newRasterSelection,
            oldAnnotationSelection + newAnnotationSelection
        )
        DrawingViewportTools.repaintRect(canvas, selectionMarqueeBounds)
    }

    fun handleSelectDragged(previous: Point?, safePoint: Point) {
        val marqueeStart = selectionMarqueeStart
        if (marqueeStart != null) {
            val oldBounds = selectionMarqueeBounds?.let { Rectangle(it) }
            selectionMarqueeBounds = rectangleFromPoints(marqueeStart, safePoint)
            repaintDirty(
                bounds = DrawingViewportTools.unionRectangles(oldBounds, selectionMarqueeBounds),
                padding = selectionRepaintPaddingPx,
                coalesce = true
            )
            return
        }

        if (previous == null || (selectedStrokeIds.isEmpty() && selectedRasterFillIds.isEmpty() && selectedAnnotationIds.isEmpty())) return

        val deltaX = safePoint.x - previous.x
        val deltaY = safePoint.y - previous.y
        if (deltaX == 0 && deltaY == 0) return

        val strokes = selectedStrokes()
        val rasterFills = selectedRasterFills()
        val annotations = selectedAnnotations()
        if (strokes.isEmpty() && rasterFills.isEmpty() && annotations.isEmpty()) {
            selectedStrokeIds.clear()
            selectedRasterFillIds.clear()
            selectedAnnotationIds.clear()
            return
        }

        val beforeBounds = selectionBounds(strokes, rasterFills, annotations)
        if (!selectionMoveUndoSaved) {
            saveStateForUndo()
            selectionMoveUndoSaved = true
        }

        if (strokes.isNotEmpty() && !coordinateMapper.moveStrokesByViewDelta(strokes, deltaX, deltaY)) {
            return
        }
        for (fill in rasterFills) {
            coordinateMapper.moveRasterFillByViewDelta(fill, deltaX, deltaY)
            strokeWorkspace.invalidateRasterFillBounds(fill.id)
        }
        for (annotation in annotations) {
            coordinateMapper.moveAnnotationByViewDelta(annotation, deltaX, deltaY)
            strokeWorkspace.invalidateAnnotation(annotation.id)
        }
        shiftRigidObjectGroupOutOfCodeText(strokes)
        shiftRasterFillGroupsOutOfCodeText(rasterFills)
        shiftAnnotationGroupsOutOfCodeText(annotations)
        for (stroke in strokes) {
            strokeWorkspace.updateStrokeBounds(stroke)
            strokeWorkspace.invalidateStrokeGeometry(stroke)
        }
        val afterBounds = selectionBounds(strokes, rasterFills, annotations)
        repaintDirty(DrawingViewportTools.unionRectangles(beforeBounds, afterBounds), coalesce = true)
    }

    fun handleSelectReleased() {
        val marqueeBounds = selectionMarqueeBounds
        if (selectionMarqueeStart != null && marqueeBounds != null) {
            selectObjectsInsideMarquee(marqueeBounds)
            selectionMarqueeStart = null
            selectionMarqueeBounds = null
            DrawingViewportTools.repaintRect(canvas, marqueeBounds.grown(selectionRepaintPaddingPx))
            repaintSelection(selectedStrokes(), selectedRasterFills(), selectedAnnotations())
            return
        }

        if (selectionMoveUndoSaved) {
            documentSync.schedulePersistCurrentStrokes()
            refreshHistoryState()
        }
        selectionMoveUndoSaved = false
    }

    fun handleFillPressed(safePoint: Point) {
        clearSelection()
        val rasterFill = PaintGeometryEngine.fillRasterAt(
            strokes = currentStrokesProvider(),
            existingRasterFills = strokeWorkspace.currentRasterFills(),
            seedPoint = safePoint,
            fillColor = drawColorProvider(),
            panelBounds = Rectangle(
                -canvasPadding,
                -canvasPadding,
                canvas.width + canvasPadding * 2,
                canvas.height + canvasPadding * 2
            ),
            toViewPoint = coordinateMapper::toViewPoint,
            toAnchor = { point -> coordinateMapper.viewPointToAnchor(point, allowCodeArea = true) }
        )
        if (rasterFill != null) {
            saveStateForUndo()
            strokeWorkspace.addRasterFill(rasterFill)
            documentSync.schedulePersistCurrentStrokes()
            refreshHistoryState()
            val topLeft = coordinateMapper.toViewPoint(rasterFill.anchor.copy())
            if (topLeft != null) {
                val repaintBounds = Rectangle(topLeft.x, topLeft.y, rasterFill.width, rasterFill.height)
                repaintBounds.grow(dirtyPaddingPx, dirtyPaddingPx)
                DrawingViewportTools.repaintRect(canvas, repaintBounds)
            } else {
                canvas.repaint()
            }
        }
    }

    fun handleErasePressed(safePoint: Point) {
        clearSelection()
        applyErasePath(listOf(safePoint))
        DrawingViewportTools.repaintAround(canvas, listOf(safePoint), dirtyPaddingPx + eraseRadius.roundToInt())
    }

    fun handleEraseDragged(previous: Point?, safePoint: Point) {
        if (previous == null) {
            applyErasePath(listOf(safePoint))
            DrawingViewportTools.repaintAround(canvas, listOf(safePoint), dirtyPaddingPx + eraseRadius.roundToInt())
            return
        }

        if (previous.distance(safePoint) < eraseMinMovePx) {
            return
        }

        val samples = strokePathTools.buildEraseSamples(previous, safePoint)
        applyErasePath(samples)
        repaintAround(samples, dirtyPaddingPx + eraseRadius.roundToInt(), coalesce = true)
    }

    fun handleEraseReleased() {
        if (eraseUndoSaved) {
            documentSync.schedulePersistCurrentStrokes()
            refreshHistoryState()
        }
        eraseUndoSaved = false
    }

    fun handleShapePressed() {
        clearSelection()
        shapePreviewSetter(null)
    }

    fun handleShapeDragged(start: Point, safePoint: Point, isShiftDown: Boolean) {
        val oldPreviewPoints = shapePreviewGetter()?.points?.mapNotNull(coordinateMapper::toViewPoint).orEmpty()
        shapePreviewSetter(buildShapeStroke(start, safePoint, selectedShapeKindProvider(), isShiftDown))
        val preview = shapePreviewGetter()
        val newPreviewPoints = preview?.points?.mapNotNull(coordinateMapper::toViewPoint).orEmpty()
        repaintAround(oldPreviewPoints + newPreviewPoints + listOf(start, safePoint), shapePreviewDirtyPaddingPx, coalesce = true)
    }

    fun handleShapeReleased() {
        val preview = shapePreviewGetter()
        val previewPoints = preview?.points?.mapNotNull(coordinateMapper::toViewPoint).orEmpty()
        if (preview != null && shouldCommitShapePreview(preview, previewPoints)) {
            when (preview.kind) {
                ShapeKind.TEXT -> startAnnotationEditor(
                    previewPoints = previewPoints,
                    annotationBounds = computeBounds(previewPoints),
                    editorBounds = computeTextBounds(previewPoints),
                    kind = AnnotationKind.TEXT,
                    saveUndoBeforeText = true,
                    objectGroupId = nextStrokeObjectGroupId()
                )
                ShapeKind.BALLOON -> startAnnotationEditor(
                    previewPoints = previewPoints,
                    annotationBounds = computeBounds(previewPoints),
                    editorBounds = computeBalloonTextBounds(previewPoints),
                    kind = AnnotationKind.BALLOON,
                    saveUndoBeforeText = true,
                    objectGroupId = nextStrokeObjectGroupId()
                )
                else -> {
                    saveStateForUndo()
                    val objectGroupId = nextStrokeObjectGroupId()
                    val committed = preview.deepCopy()
                    shiftRigidObjectGroupOutOfCodeText(listOf(committed))
                    strokeWorkspace.addStroke(committed)
                    documentSync.schedulePersistCurrentStrokes()
                    refreshHistoryState()
                }
            }
        }
        shapePreviewSetter(null)
        DrawingViewportTools.repaintAround(canvas, previewPoints, shapePreviewDirtyPaddingPx)
    }

    private fun startAnnotationEditor(
        previewPoints: List<Point>,
        annotationBounds: Rectangle,
        editorBounds: Rectangle,
        kind: AnnotationKind,
        saveUndoBeforeText: Boolean,
        objectGroupId: Long
    ) {
        if (previewPoints.isEmpty()) {
            return
        }

        balloonTextEditor(editorBounds) { text ->
            val annotation = buildAnnotationPath(text, annotationBounds, kind, objectGroupId)
            if (annotation == null) {
                documentSync.schedulePersistCurrentStrokes()
                refreshHistoryState()
                DrawingViewportTools.repaintAround(canvas, previewPoints, shapePreviewDirtyPaddingPx)
                return@balloonTextEditor
            }

            if (saveUndoBeforeText) {
                saveStateForUndo()
            }

            strokeWorkspace.addAnnotation(annotation)
            documentSync.schedulePersistCurrentStrokes()
            refreshHistoryState()
            DrawingViewportTools.repaintRect(canvas, annotationViewBounds(annotation, shapePreviewDirtyPaddingPx))
        }
    }

    private fun buildAnnotationPath(
        text: String?,
        bounds: Rectangle,
        kind: AnnotationKind,
        objectGroupId: Long
    ): AnnotationPath? {
        val cleanText = text?.trim().orEmpty()
        if (cleanText.isEmpty()) return null
        if (bounds.width <= 4 || bounds.height <= 4) return null
        val anchor = coordinateMapper.viewPointToAnchor(Point(bounds.x, bounds.y), allowCodeArea = true) ?: return null
        val annotation = AnnotationPath(
            id = objectGroupId,
            text = cleanText,
            color = drawColorProvider(),
            anchor = anchor,
            width = bounds.width,
            height = bounds.height,
            kind = kind,
            style = balloonTextStyleProvider(),
            objectGroupId = objectGroupId
        )
        shiftAnnotationGroupsOutOfCodeText(listOf(annotation))
        return annotation
    }


    private fun computeBalloonTextBounds(points: List<Point>): Rectangle {
        val bounds = computeBounds(points)
        val width = bounds.width.coerceAtLeast(1)
        val height = bounds.height.coerceAtLeast(1)
        val tailHeight = max(14, height / 4).coerceAtMost(max(14, height / 3))
        val padding = max(8, min(width, height) / 10)
        val textX = bounds.x + padding
        val textY = bounds.y + padding
        val textBottom = (bounds.y + bounds.height - tailHeight - padding / 2).coerceAtLeast(textY + 1)

        return Rectangle(
            textX,
            textY,
            (width - padding * 2).coerceAtLeast(1),
            (textBottom - textY).coerceAtLeast(1)
        )
    }

    private fun computeTextBounds(points: List<Point>): Rectangle {
        val bounds = computeBounds(points)
        val padding = max(4, min(bounds.width, bounds.height) / 16)
        return Rectangle(
            bounds.x + padding,
            bounds.y + padding,
            (bounds.width - padding * 2).coerceAtLeast(1),
            (bounds.height - padding * 2).coerceAtLeast(1)
        )
    }

    private fun computeBounds(points: List<Point>): Rectangle {
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var maxY = Int.MIN_VALUE
        for (point in points) {
            minX = minOf(minX, point.x)
            minY = minOf(minY, point.y)
            maxX = maxOf(maxX, point.x)
            maxY = maxOf(maxY, point.y)
        }

        return Rectangle(
            minX,
            minY,
            (maxX - minX).coerceAtLeast(1),
            (maxY - minY).coerceAtLeast(1)
        )
    }

    private fun shouldCommitShapePreview(preview: StrokePath, previewPoints: List<Point>): Boolean {
        if (preview.points.size < 2 || previewPoints.size < 2) return false

        val kind = preview.kind
        if (kind == ShapeKind.LINE) {
            val start = previewPoints.first()
            val end = previewPoints.last()
            return hypot((end.x - start.x).toDouble(), (end.y - start.y).toDouble()) >= minShapeCommitSizePx
        }

        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var maxY = Int.MIN_VALUE
        for (point in previewPoints) {
            minX = minOf(minX, point.x)
            minY = minOf(minY, point.y)
            maxX = maxOf(maxX, point.x)
            maxY = maxOf(maxY, point.y)
        }

        val width = maxX - minX
        val height = maxY - minY
        return if (kind == ShapeKind.TEXT || kind == ShapeKind.BALLOON) {
            width >= minShapeCommitSizePx && height >= minShapeCommitSizePx
        } else {
            width >= minShapeCommitSizePx || height >= minShapeCommitSizePx
        }
    }

    fun handleDrawPressed(safePoint: Point) {
        clearSelection()
        saveStateForUndo()
        val stroke = StrokePath(color = drawColorProvider(), width = drawStrokeWidth)
        currentStrokeSetter(stroke)
        strokeWorkspace.addStroke(stroke)
        if (addAnchorPoint(stroke, safePoint)) {
            strokeWorkspace.invalidateStrokeGeometry(stroke)
            DrawingViewportTools.repaintAround(canvas, listOf(safePoint), dirtyPaddingPx)
        }
    }

    fun handleDrawDragged(previous: Point?, safePoint: Point) {
        val stroke = currentStrokeGetter() ?: return
        if (previous == null) {
            if (addAnchorPoint(stroke, safePoint)) {
                strokeWorkspace.invalidateStrokeGeometry(stroke)
                DrawingViewportTools.repaintAround(canvas, listOf(safePoint), dirtyPaddingPx)
            }
        } else {
            val samples = strokePathTools.buildDrawSamples(previous, safePoint)
            val acceptedPoints = mutableListOf<Point>()
            for (p in samples) {
                if (addAnchorPoint(stroke, p)) {
                    acceptedPoints += p
                }
            }
            if (acceptedPoints.isNotEmpty()) {
                strokeWorkspace.invalidateStrokeGeometry(stroke)
                repaintAround(acceptedPoints + listOf(previous, safePoint), dirtyPaddingPx, coalesce = true)
            }
        }
    }

    fun handleDrawReleased() {
        val stroke = currentStrokeGetter()
        if (stroke == null) {
            currentStrokeSetter(null)
            refreshHistoryState()
            return
        }

        val originalPoints = stroke.points.mapNotNull(coordinateMapper::toViewPoint)
        if (!shouldCommitFreehandStroke(stroke, originalPoints)) {
            removeUncommittedFreehandStroke(stroke, originalPoints)
            currentStrokeSetter(null)
            refreshHistoryState()
            return
        }

        if (strokePathTools.simplifyFreehandStrokeInPlace(stroke)) {
            strokeWorkspace.updateStrokeBounds(stroke)
            strokeWorkspace.invalidateStrokeGeometry(stroke)
        }

        val simplifiedPoints = stroke.points.mapNotNull(coordinateMapper::toViewPoint)
        if (!shouldCommitFreehandStroke(stroke, simplifiedPoints)) {
            removeUncommittedFreehandStroke(stroke, originalPoints + simplifiedPoints)
            currentStrokeSetter(null)
            refreshHistoryState()
            return
        }

        if (shouldUseRigidObjectAnchorForFreehand(stroke) &&
            coordinateMapper.reanchorStrokeToObjectAnchor(stroke)
        ) {
            strokeWorkspace.updateStrokeBounds(stroke)
            strokeWorkspace.invalidateStrokeGeometry(stroke)
        }

        currentStrokeSetter(null)
        documentSync.schedulePersistCurrentStrokes()
        refreshHistoryState()
    }

    private fun shouldCommitFreehandStroke(stroke: StrokePath, viewPoints: List<Point>): Boolean {
        if (stroke.points.size < 2 || viewPoints.size < 2) return false

        var pathLength = 0.0
        var previous = viewPoints.first()
        for (index in 1 until viewPoints.size) {
            val current = viewPoints[index]
            pathLength += previous.distance(current)
            if (pathLength >= minFreehandCommitLengthPx) {
                return true
            }
            previous = current
        }

        return false
    }

    private fun shouldUseRigidObjectAnchorForFreehand(stroke: StrokePath): Boolean {
        return DrawingViewportTools.shouldUseRigidObjectAnchorForFreehand(stroke) { anchor ->
            coordinateMapper.toViewPoint(anchor)
        }
    }

    private fun removeUncommittedFreehandStroke(stroke: StrokePath, viewPoints: List<Point>) {
        val document = editorProvider()?.document
        currentStrokesProvider().remove(stroke)
        strokeWorkspace.currentStrokeBounds().remove(stroke.id)
        strokeWorkspace.currentStrokeGeometries().remove(stroke.id)
        historyStore.discardLastUndo(document)
        if (viewPoints.isNotEmpty()) {
            DrawingViewportTools.repaintAround(canvas, viewPoints, dirtyPaddingPx)
        }
    }

    private fun findTopmostDrawingAt(point: Point): SelectionHit? {
        val annotation = findTopmostAnnotationAt(point)
        if (annotation != null) {
            return SelectionHit.AnnotationHit(annotation)
        }
        val rasterFill = findTopmostRasterFillAt(point)
        if (rasterFill != null) {
            return SelectionHit.RasterFillHit(rasterFill)
        }
        val stroke = findTopmostStrokeAt(point)
        return stroke?.let(SelectionHit::StrokeHit)
    }

    private fun findTopmostAnnotationAt(point: Point): AnnotationPath? {
        val currentEditor = editorProvider() ?: return null
        val contentPoint = SwingUtilities.convertPoint(canvas, point, currentEditor.contentComponent)
        for (annotation in strokeWorkspace.currentAnnotations().asReversed()) {
            val bounds = strokeWorkspace.annotationContentBounds(annotation) ?: continue
            val hitBounds = Rectangle(bounds).apply {
                grow(selectionHitPaddingPx, selectionHitPaddingPx)
            }
            if (hitBounds.contains(contentPoint)) {
                return annotation
            }
        }
        return null
    }

    private fun findTopmostRasterFillAt(point: Point): RasterFillPath? {
        val currentEditor = editorProvider() ?: return null
        val contentPoint = SwingUtilities.convertPoint(canvas, point, currentEditor.contentComponent)
        for (fill in strokeWorkspace.currentRasterFills().asReversed()) {
            val bounds = strokeWorkspace.rasterFillContentBounds(fill) ?: continue
            val hitBounds = Rectangle(bounds).apply {
                grow(selectionHitPaddingPx, selectionHitPaddingPx)
            }
            if (!hitBounds.contains(contentPoint)) continue

            val image = runCatching { strokeWorkspace.rasterFillImage(fill) }.getOrNull()
            if (image == null) return fill

            val localX = contentPoint.x - bounds.x
            val localY = contentPoint.y - bounds.y
            if (localX !in 0 until image.width || localY !in 0 until image.height) {
                return fill
            }
            if ((image.getRGB(localX, localY) ushr 24) != 0) {
                return fill
            }
        }
        return null
    }

    private fun findTopmostStrokeAt(point: Point): StrokePath? {
        val currentEditor = editorProvider() ?: return null
        val contentPoint = SwingUtilities.convertPoint(canvas, point, currentEditor.contentComponent)
        for (stroke in currentStrokesProvider().asReversed()) {
            val geometry = strokeWorkspace.getOrBuildStrokeGeometryContent(stroke) ?: continue
            val hitBounds = Rectangle(geometry.bounds).apply {
                grow(selectionHitPaddingPx, selectionHitPaddingPx)
            }
            if (!hitBounds.contains(contentPoint)) continue

            val polygon = geometry.polygon
            if (polygon != null && polygon.contains(contentPoint)) {
                return stroke
            }

            val path = geometry.path ?: continue
            if (stroke.kind?.isClosedOutline() == true && path.contains(contentPoint)) {
                return stroke
            }

            val hitShape = BasicStroke(
                (stroke.width + selectionHitPaddingPx.toFloat() * 2f).coerceAtLeast(10f),
                BasicStroke.CAP_ROUND,
                BasicStroke.JOIN_ROUND
            ).createStrokedShape(path)
            if (hitShape.contains(contentPoint)) {
                return stroke
            }
        }
        return null
    }

    private fun selectedGroupFor(stroke: StrokePath): List<StrokePath> {
        val groupId = stroke.objectGroupId
        return if (groupId != 0L) {
            currentStrokesProvider().filter { it.objectGroupId == groupId }
        } else {
            listOf(stroke)
        }
    }

    private fun selectedGroupFor(fill: RasterFillPath): List<RasterFillPath> {
        val groupId = fill.objectGroupId
        return if (groupId != 0L) {
            strokeWorkspace.currentRasterFills().filter { it.objectGroupId == groupId }
        } else {
            listOf(fill)
        }
    }

    private fun selectedGroupFor(annotation: AnnotationPath): List<AnnotationPath> {
        val groupId = annotation.objectGroupId
        return if (groupId != 0L) {
            strokeWorkspace.currentAnnotations().filter { it.objectGroupId == groupId }
        } else {
            listOf(annotation)
        }
    }

    private fun selectedStrokes(): List<StrokePath> {
        if (selectedStrokeIds.isEmpty()) return emptyList()
        return currentStrokesProvider().filter { it.id in selectedStrokeIds }
    }

    private fun selectedRasterFills(): List<RasterFillPath> {
        if (selectedRasterFillIds.isEmpty()) return emptyList()
        return strokeWorkspace.currentRasterFills().filter { it.id in selectedRasterFillIds }
    }

    private fun selectedAnnotations(): List<AnnotationPath> {
        if (selectedAnnotationIds.isEmpty()) return emptyList()
        return strokeWorkspace.currentAnnotations().filter { it.id in selectedAnnotationIds }
    }

    private fun selectObjectsInsideMarquee(marqueeBounds: Rectangle) {
        val currentEditor = editorProvider() ?: return
        val contentMarquee = SwingUtilities.convertRectangle(canvas, marqueeBounds, currentEditor.contentComponent)

        selectedStrokeIds.clear()
        selectedRasterFillIds.clear()
        selectedAnnotationIds.clear()

        for (stroke in currentStrokesProvider()) {
            val geometry = strokeWorkspace.getOrBuildStrokeGeometryContent(stroke) ?: continue
            if (contentMarquee.contains(geometry.bounds)) {
                selectedStrokeIds += selectedGroupFor(stroke).map { it.id }
            }
        }

        for (fill in strokeWorkspace.currentRasterFills()) {
            val bounds = strokeWorkspace.rasterFillContentBounds(fill) ?: continue
            if (contentMarquee.contains(bounds)) {
                selectedRasterFillIds += selectedGroupFor(fill).map { it.id }
            }
        }

        for (annotation in strokeWorkspace.currentAnnotations()) {
            val bounds = strokeWorkspace.annotationContentBounds(annotation) ?: continue
            if (contentMarquee.contains(bounds)) {
                selectedAnnotationIds += selectedGroupFor(annotation).map { it.id }
            }
        }
    }

    private fun repaintSelection(
        strokes: List<StrokePath>,
        rasterFills: List<RasterFillPath>,
        annotations: List<AnnotationPath> = emptyList()
    ) {
        DrawingViewportTools.repaintRect(canvas, selectionBounds(strokes, rasterFills, annotations))
    }

    private fun selectionBounds(
        strokes: List<StrokePath>,
        rasterFills: List<RasterFillPath> = emptyList(),
        annotations: List<AnnotationPath> = emptyList()
    ): Rectangle? {
        val strokeBounds = drawingViewBounds(strokes, emptyList(), emptyList(), selectionRepaintPaddingPx)
        val rasterBounds = rasterFills
            .mapNotNull { fill -> rasterFillViewBounds(fill, selectionRepaintPaddingPx) }
            .fold(null as Rectangle?) { union, bounds ->
                if (union == null) Rectangle(bounds) else union.apply { add(bounds) }
            }
        val annotationBounds = DrawingViewportTools.computeAnnotationsViewBounds(
            annotations = annotations,
            toViewPoint = coordinateMapper::toViewPoint,
            extraPadding = selectionRepaintPaddingPx
        )
        return DrawingViewportTools.unionRectangles(
            DrawingViewportTools.unionRectangles(strokeBounds, rasterBounds),
            annotationBounds
        )
    }

    private fun drawingViewBounds(
        strokes: Iterable<StrokePath>,
        rasterFills: Iterable<RasterFillPath>,
        annotations: Iterable<AnnotationPath>,
        extraPadding: Int
    ): Rectangle? {
        val strokeBounds = DrawingViewportTools.computeStrokesViewBounds(
            strokes = strokes,
            toViewPoint = coordinateMapper::toViewPoint,
            extraPadding = extraPadding
        )
        val rasterBounds = rasterFills
            .mapNotNull { fill -> rasterFillViewBounds(fill, extraPadding) }
            .fold(null as Rectangle?) { union, bounds ->
                if (union == null) Rectangle(bounds) else union.apply { add(bounds) }
            }
        val annotationBounds = DrawingViewportTools.computeAnnotationsViewBounds(
            annotations = annotations,
            toViewPoint = coordinateMapper::toViewPoint,
            extraPadding = extraPadding
        )
        return DrawingViewportTools.unionRectangles(
            DrawingViewportTools.unionRectangles(strokeBounds, rasterBounds),
            annotationBounds
        )
    }

    private fun rasterFillViewBounds(fill: RasterFillPath, extraPadding: Int): Rectangle? {
        val topLeft = coordinateMapper.toViewPoint(fill.anchor.copy()) ?: return null
        val bounds = Rectangle(topLeft.x, topLeft.y, fill.width, fill.height)
        bounds.grow(extraPadding, extraPadding)
        return bounds
    }

    private fun annotationViewBounds(annotation: AnnotationPath, extraPadding: Int): Rectangle? {
        val topLeft = coordinateMapper.toViewPoint(annotation.anchor.copy()) ?: return null
        val bounds = Rectangle(topLeft.x, topLeft.y, annotation.width, annotation.height)
        bounds.grow(extraPadding, extraPadding)
        return bounds
    }

    private fun rectangleFromPoints(first: Point, second: Point): Rectangle {
        val x = min(first.x, second.x)
        val y = min(first.y, second.y)
        val width = abs(first.x - second.x).coerceAtLeast(1)
        val height = abs(first.y - second.y).coerceAtLeast(1)
        return Rectangle(x, y, width, height)
    }

    private fun Rectangle.grown(padding: Int): Rectangle {
        return Rectangle(this).apply { grow(padding, padding) }
    }

    private fun repaintDirty(bounds: Rectangle?, padding: Int = 0, coalesce: Boolean = false) {
        dirtyRepaintScheduler?.repaintDirty(bounds, padding, coalesce)
            ?: DrawingViewportTools.repaintRect(canvas, bounds?.grown(padding))
    }

    private fun repaintAround(points: List<Point>, padding: Int, coalesce: Boolean = false) {
        dirtyRepaintScheduler?.repaintAround(points, padding, coalesce)
            ?: DrawingViewportTools.repaintAround(canvas, points, padding)
    }

    private fun shiftRasterFillGroupsOutOfCodeText(fills: List<RasterFillPath>): Boolean {
        var shifted = false
        for (group in fills.groupBy { fill -> if (fill.objectGroupId != 0L) fill.objectGroupId else -fill.id }.values) {
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

    private fun shiftAnnotationGroupsOutOfCodeText(annotations: List<AnnotationPath>): Boolean {
        var shifted = false
        for (group in annotations.groupBy { annotation -> if (annotation.objectGroupId != 0L) annotation.objectGroupId else -annotation.id }.values) {
            val shiftX = group.maxOfOrNull { annotation ->
                coordinateMapper.requiredShiftOutOfCodeText(annotation)
            } ?: 0
            if (shiftX <= 0) continue

            for (annotation in group) {
                coordinateMapper.shiftAnnotationHorizontally(annotation, shiftX)
                strokeWorkspace.invalidateAnnotation(annotation.id)
            }
            shifted = true
        }
        return shifted
    }

    private sealed interface SelectionHit {
        data class StrokeHit(val stroke: StrokePath) : SelectionHit
        data class RasterFillHit(val fill: RasterFillPath) : SelectionHit
        data class AnnotationHit(val annotation: AnnotationPath) : SelectionHit
    }

    private fun saveStateForUndo() {
        val document = editorProvider()?.document ?: return
        historyStore.saveStateForUndo(
            document,
            currentStrokesProvider(),
            strokeWorkspace.currentRasterFills(),
            strokeWorkspace.currentAnnotations()
        )
        refreshHistoryState()
    }

    private fun saveStateForEraseUndo() {
        if (eraseUndoSaved) return
        saveStateForUndo()
        eraseUndoSaved = true
    }

    private fun applyErasePath(points: List<Point>) {
        if (points.isEmpty()) return
        val document = editorProvider()?.document ?: return
        val currentEditor = editorProvider() ?: return

        val allStrokes = currentStrokesProvider()
        val rasterFills = strokeWorkspace.currentRasterFills()
        val annotations = strokeWorkspace.currentAnnotations()
        if (allStrokes.isEmpty() && rasterFills.isEmpty() && annotations.isEmpty()) return

        val candidateRange = DrawingViewportTools.computeEraseCandidateLineRange(canvas, currentEditor, coordinateMapper, points)
        val boundsMap = strokeWorkspace.currentStrokeBounds()
        val geometryMap = strokeWorkspace.currentStrokeGeometries()

        val candidates = mutableListOf<StrokePath>()
        for (stroke in allStrokes) {
            val bounds = boundsMap[stroke.id]
                ?: DrawingViewportTools.computeStrokeLineBounds(stroke)?.also { boundsMap[stroke.id] = it }
            if (bounds == null) continue

            val intersectsLineRange =
                bounds.maxLine >= candidateRange.first && bounds.minLine <= candidateRange.second
            if (intersectsLineRange) {
                candidates += stroke
            }
        }

        val annotationEraserPoints = points.map { point ->
            SwingUtilities.convertPoint(canvas, point, currentEditor.contentComponent)
        }
        val annotationEraserAreaContent = buildEraserArea(annotationEraserPoints, eraseRadius)
        val changedAnnotationBounds = eraseAnnotationCharacters(
            annotations = annotations,
            contentEraserArea = annotationEraserAreaContent,
            onWillChange = ::saveStateForEraseUndo
        )
        if (changedAnnotationBounds.isNotEmpty()) {
            repaintContentBounds(currentEditor, changedAnnotationBounds)
        }

        val rebuiltRasterFills = RasterFillEraseEngine.eraseAlongPathByFill(
            fills = rasterFills,
            localPoints = points,
            radius = eraseRadius,
            toViewPoint = coordinateMapper::toViewPoint
        )
        if (rebuiltRasterFills.isNotEmpty()) {
            saveStateForEraseUndo()
            applyRasterFillEraseResults(rasterFills, rebuiltRasterFills)
        }

        if (candidates.isEmpty()) {
            return
        }

        val rebuiltByStroke = PaintGeometryEngine.eraseAlongPathByStroke(
            strokes = candidates,
            localPoints = points,
            radius = eraseRadius,
            toAnchor = { point -> coordinateMapper.viewPointToAnchor(point, allowCodeArea = true) },
            toViewPoint = coordinateMapper::toViewPoint
        )

        if (rebuiltByStroke.isEmpty()) {
            return
        }

        saveStateForEraseUndo()
        var replacementCount = 0
        for (replacements in rebuiltByStroke.values) {
            replacementCount += replacements.size
        }

        for (stroke in candidates) {
            boundsMap.remove(stroke.id)
            geometryMap.remove(stroke.id)
        }

        val merged = ArrayList<StrokePath>(allStrokes.size - candidates.size + replacementCount)
        for (stroke in allStrokes) {
            val replacements = rebuiltByStroke[stroke.id]
            if (replacements == null) {
                merged += stroke
                continue
            }

            for (replacement in replacements) {
                merged += replacement
                DrawingViewportTools.computeStrokeLineBounds(replacement)?.let { boundsMap[replacement.id] = it }
                geometryMap.remove(replacement.id)
            }
        }

        strokeWorkspace.setStrokes(document, merged)
    }

    private fun applyRasterFillEraseResults(
        rasterFills: MutableList<RasterFillPath>,
        rebuiltRasterFills: Map<Long, RasterFillPath?>
    ) {
        for (index in rasterFills.indices.reversed()) {
            val fill = rasterFills[index]
            if (!rebuiltRasterFills.containsKey(fill.id)) continue

            val replacement = rebuiltRasterFills[fill.id]
            if (replacement == null) {
                selectedRasterFillIds.remove(fill.id)
                rasterFills.removeAt(index)
            } else {
                rasterFills[index] = replacement
            }
            strokeWorkspace.invalidateRasterFill(fill.id)
        }
    }

    private fun eraseAnnotationCharacters(
        annotations: MutableList<AnnotationPath>,
        contentEraserArea: Area,
        onWillChange: () -> Unit
    ): List<Rectangle> {
        if (annotations.isEmpty()) return emptyList()
        val changedBounds = mutableListOf<Rectangle>()
        val iterator = annotations.iterator()
        while (iterator.hasNext()) {
            val annotation = iterator.next()
            val bounds = strokeWorkspace.annotationContentBounds(annotation) ?: continue
            if (!contentEraserArea.intersects(bounds)) continue
            val hitCharacterIndexes = annotationCharacterIndexesHit(annotation, bounds, contentEraserArea)
            if (hitCharacterIndexes.isEmpty()) continue

            onWillChange()
            changedBounds += Rectangle(bounds)
            strokeWorkspace.invalidateAnnotation(annotation.id)
            val updatedText = removeAnnotationCharacters(annotation.text, hitCharacterIndexes)
            if (updatedText.isBlank()) {
                selectedAnnotationIds.remove(annotation.id)
                iterator.remove()
            } else {
                annotation.text = updatedText
            }
        }
        return changedBounds
    }

    private fun annotationCharacterIndexesHit(
        annotation: AnnotationPath,
        annotationContentBounds: Rectangle,
        contentEraserArea: Area
    ): Set<Int> {
        return AnnotationTextLayout.characterBounds(annotation)
            .asSequence()
            .map { character ->
                character.index to Rectangle(
                    annotationContentBounds.x + character.bounds.x,
                    annotationContentBounds.y + character.bounds.y,
                    character.bounds.width,
                    character.bounds.height
                )
            }
            .filter { (_, bounds) -> contentEraserArea.contains(bounds.centerX, bounds.centerY) }
            .map { (index, _) -> index }
            .toSet()
    }

    private fun removeAnnotationCharacters(text: String, indexesToRemove: Set<Int>): String {
        if (indexesToRemove.isEmpty()) return text
        return buildString(text.length) {
            for (index in text.indices) {
                if (index !in indexesToRemove) {
                    append(text[index])
                }
            }
        }
    }

    private fun repaintContentBounds(editor: Editor, contentBounds: List<Rectangle>) {
        for (bounds in contentBounds) {
            val padded = Rectangle(bounds)
            padded.grow(dirtyPaddingPx + eraseRadius.roundToInt(), dirtyPaddingPx + eraseRadius.roundToInt())
            val canvasBounds = SwingUtilities.convertRectangle(editor.contentComponent, padded, canvas)
            DrawingViewportTools.repaintRect(canvas, canvasBounds)
        }
    }

    private fun buildEraserArea(points: List<Point>, radius: Double): Area {
        if (points.isEmpty()) return Area()

        val area = Area()

        if (points.size == 1) {
            val p = points.first()
            area.add(
                Area(
                    Ellipse2D.Double(
                        p.x - radius,
                        p.y - radius,
                        radius * 2,
                        radius * 2
                    )
                )
            )
            return area
        }

        val path = Path2D.Double()
        path.moveTo(points.first().x.toDouble(), points.first().y.toDouble())
        for (point in points.drop(1)) {
            path.lineTo(point.x.toDouble(), point.y.toDouble())
        }

        val strokeShape = BasicStroke(
            (radius * 2.0).toFloat(),
            BasicStroke.CAP_ROUND,
            BasicStroke.JOIN_ROUND
        ).createStrokedShape(path)

        area.add(Area(strokeShape))

        val first = points.first()
        val last = points.last()

        area.add(
            Area(
                Ellipse2D.Double(
                    first.x - radius,
                    first.y - radius,
                    radius * 2,
                    radius * 2
                )
            )
        )
        area.add(
            Area(
                Ellipse2D.Double(
                    last.x - radius,
                    last.y - radius,
                    radius * 2,
                    radius * 2
                )
            )
        )

        return area
    }

    private fun addAnchorPoint(stroke: StrokePath, point: Point): Boolean {
        val anchor = coordinateMapper.viewPointToAnchor(point) ?: return false
        val isFirstPoint = stroke.points.isEmpty()
        if (isFirstPoint) {
            coordinateMapper.lockStrokeFoldLayout(stroke, anchor)
        }

        if (!shouldKeepFreehandPoint(stroke, anchor, point)) {
            return false
        }

        stroke.points += anchor
        strokeWorkspace.expandStrokeBoundsWithAnchor(stroke, anchor)
        return true
    }

    private fun shouldKeepFreehandPoint(stroke: StrokePath, anchor: AnchorPoint, point: Point): Boolean {
        val last = stroke.points.lastOrNull() ?: return true
        val lastViewPoint = coordinateMapper.toViewPoint(last)
        if (lastViewPoint != null && lastViewPoint.distance(point) < freehandMinPointDistancePx) {
            return false
        }

        return !(last.line == anchor.line &&
            last.column == anchor.column &&
            abs(last.dx - anchor.dx) < 2 &&
            abs(last.dy - anchor.dy) < 2)
    }

    private fun convertViewStrokeToAnchors(
        stroke: StrokePath,
        useObjectAnchor: Boolean = false,
        kindOverride: ShapeKind? = stroke.kind,
        objectGroupId: Long = 0L,
        forceRigidObjectAnchor: Boolean = false
    ): StrokePath {
        val converted = stroke.points.mapNotNull { source ->
            val view = Point(source.dx, source.dy)
            if (useObjectAnchor) {
                coordinateMapper.viewPointToObjectAnchor(view)
            } else {
                coordinateMapper.viewPointToAnchor(view)
            }
        }.toMutableList()

        return StrokePath(
            color = stroke.color,
            width = stroke.width,
            points = converted,
            filled = stroke.filled,
            kind = kindOverride,
            objectGroupId = objectGroupId,
            rigidObjectAnchor = forceRigidObjectAnchor
        ).also { convertedStroke ->
            coordinateMapper.lockStrokeFoldLayout(convertedStroke)
        }
    }

    private fun buildShapeStroke(start: Point, end: Point, kind: ShapeKind, constrain: Boolean): StrokePath {
        val useRigidObjectAnchor = kind.usesRigidObjectAnchoring()
        if (useRigidObjectAnchor) {
            coordinateMapper.beginObjectAnchor(start)
        }

        return try {
            ShapeStrokeFactory.buildShapeStroke(
                start = start,
                end = end,
                kind = kind,
                constrain = constrain,
                color = drawColorProvider(),
                width = drawStrokeWidth,
                shapeEdgeSpacing = shapeEdgeSpacing,
                ellipseSegments = ellipseSegments,
                toAnchor = if (useRigidObjectAnchor) {
                    { point -> coordinateMapper.viewPointToObjectAnchor(point) }
                } else {
                    { point -> coordinateMapper.viewPointToAnchor(point) }
                }
            ).also { shapeStroke ->
                if (useRigidObjectAnchor) {
                    reanchorRigidStrokesToTopLine(listOf(shapeStroke))
                    shiftRigidObjectGroupOutOfCodeText(listOf(shapeStroke))
                }
                coordinateMapper.lockStrokeFoldLayout(shapeStroke)
            }
        } finally {
            if (useRigidObjectAnchor) {
                coordinateMapper.endObjectAnchor()
            }
        }
    }

    private fun reanchorRigidStrokesToTopLine(strokes: List<StrokePath>) {
        for (stroke in strokes) {
            if (stroke.usesRigidObjectAnchoring()) {
                coordinateMapper.reanchorStrokeToObjectAnchor(stroke)
            }
        }
    }

    private fun shiftRigidObjectGroupOutOfCodeText(strokes: List<StrokePath>): Boolean {
        val shiftX = strokes.maxOfOrNull { stroke ->
            coordinateMapper.requiredShiftOutOfCodeText(stroke)
        } ?: 0
        if (shiftX <= 0) return false

        for (stroke in strokes) {
            coordinateMapper.shiftStrokeHorizontally(stroke, shiftX)
        }
        return true
    }
}
