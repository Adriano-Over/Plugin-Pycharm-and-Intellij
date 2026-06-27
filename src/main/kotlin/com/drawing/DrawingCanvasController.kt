package com.drawing

import com.intellij.openapi.editor.Editor
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Point
import java.awt.Rectangle
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
    private val balloonTextEditor: (Rectangle, (String?) -> Unit) -> Unit = { _, commit -> commit(null) }
) {
    private val drawStrokeWidth = 3.5f
    private val shapePreviewDirtyPaddingPx = dirtyPaddingPx + 120
    private val minShapeCommitSizePx = 8
    private val minFreehandCommitLengthPx = 6.0
    private val selectionHitPaddingPx = 8
    private val selectionRepaintPaddingPx = dirtyPaddingPx + 18
    private val selectedStrokeIds = linkedSetOf<Long>()
    private var selectionMoveUndoSaved = false

    fun clearCanvas() {
        val document = editorProvider()?.document ?: return
        val dirtyBounds = DrawingViewportTools.computeStrokesViewBounds(
            strokes = currentStrokesProvider(),
            toViewPoint = coordinateMapper::toViewPoint,
            extraPadding = dirtyPaddingPx
        )
        saveStateForUndo()
        strokeWorkspace.clearDocument(document)
        currentStrokeSetter(null)
        shapePreviewSetter(null)
        selectedStrokeIds.clear()
        selectionMoveUndoSaved = false
        documentSync.persistCurrentStrokes()
        refreshHistoryState()
        DrawingViewportTools.repaintRect(canvas, dirtyBounds)
    }

    fun undo() {
        val document = editorProvider()?.document ?: return
        val beforeBounds = DrawingViewportTools.computeStrokesViewBounds(
            strokes = currentStrokesProvider(),
            toViewPoint = coordinateMapper::toViewPoint,
            extraPadding = dirtyPaddingPx
        )
        val restored = historyStore.restoreUndo(document, currentStrokesProvider()) ?: return
        val afterBounds = DrawingViewportTools.computeStrokesViewBounds(
            strokes = restored,
            toViewPoint = coordinateMapper::toViewPoint,
            extraPadding = dirtyPaddingPx
        )
        strokeWorkspace.setStrokes(document, restored.toMutableList())
        strokeWorkspace.rebuildStrokeBounds(document)
        strokeWorkspace.resetStrokeGeometryCache(document)
        clearSelection(repaint = false)
        documentSync.persistCurrentStrokes()
        refreshHistoryState()
        DrawingViewportTools.repaintRect(canvas, DrawingViewportTools.unionRectangles(beforeBounds, afterBounds))
    }

    fun redo() {
        val document = editorProvider()?.document ?: return
        val beforeBounds = DrawingViewportTools.computeStrokesViewBounds(
            strokes = currentStrokesProvider(),
            toViewPoint = coordinateMapper::toViewPoint,
            extraPadding = dirtyPaddingPx
        )
        val restored = historyStore.restoreRedo(document, currentStrokesProvider()) ?: return
        val afterBounds = DrawingViewportTools.computeStrokesViewBounds(
            strokes = restored,
            toViewPoint = coordinateMapper::toViewPoint,
            extraPadding = dirtyPaddingPx
        )
        strokeWorkspace.setStrokes(document, restored.toMutableList())
        strokeWorkspace.rebuildStrokeBounds(document)
        strokeWorkspace.resetStrokeGeometryCache(document)
        clearSelection(repaint = false)
        documentSync.persistCurrentStrokes()
        refreshHistoryState()
        DrawingViewportTools.repaintRect(canvas, DrawingViewportTools.unionRectangles(beforeBounds, afterBounds))
    }

    fun selectedStrokeIdsSnapshot(): Set<Long> {
        return selectedStrokeIds.toSet()
    }

    fun clearSelection(repaint: Boolean = true) {
        if (selectedStrokeIds.isEmpty()) return
        val oldSelection = selectedStrokes()
        selectedStrokeIds.clear()
        selectionMoveUndoSaved = false
        if (repaint) {
            repaintSelection(oldSelection)
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
        refreshHistoryState()
    }

    fun handleSelectPressed(safePoint: Point) {
        val oldSelection = selectedStrokes()
        selectedStrokeIds.clear()
        selectionMoveUndoSaved = false

        val hitStroke = findTopmostStrokeAt(safePoint)
        if (hitStroke != null) {
            selectedStrokeIds += selectedGroupFor(hitStroke).map { it.id }
        }

        val newSelection = selectedStrokes()
        repaintSelection(oldSelection + newSelection)
    }

    fun handleSelectDragged(previous: Point?, safePoint: Point) {
        if (previous == null || selectedStrokeIds.isEmpty()) return

        val deltaX = safePoint.x - previous.x
        val deltaY = safePoint.y - previous.y
        if (deltaX == 0 && deltaY == 0) return

        val strokes = selectedStrokes()
        if (strokes.isEmpty()) {
            selectedStrokeIds.clear()
            return
        }

        val beforeBounds = selectionBounds(strokes)
        if (!selectionMoveUndoSaved) {
            saveStateForUndo()
            selectionMoveUndoSaved = true
        }

        if (!coordinateMapper.moveStrokesByViewDelta(strokes, deltaX, deltaY)) {
            return
        }
        shiftRigidObjectGroupOutOfCodeText(strokes)
        for (stroke in strokes) {
            strokeWorkspace.updateStrokeBounds(stroke)
            strokeWorkspace.invalidateStrokeGeometry(stroke)
        }
        val afterBounds = selectionBounds(strokes)
        DrawingViewportTools.repaintRect(canvas, DrawingViewportTools.unionRectangles(beforeBounds, afterBounds))
    }

    fun handleSelectReleased() {
        if (selectionMoveUndoSaved) {
            documentSync.schedulePersistCurrentStrokes()
            refreshHistoryState()
        }
        selectionMoveUndoSaved = false
    }

    fun handleFillPressed(safePoint: Point) {
        clearSelection()
        saveStateForUndo()
        val filledStrokes = PaintGeometryEngine.fillAt(
            strokes = currentStrokesProvider(),
            seedPoint = safePoint,
            fillColor = drawColorProvider(),
            panelBounds = Rectangle(
                -canvasPadding,
                -canvasPadding,
                canvas.width + canvasPadding * 2,
                canvas.height + canvasPadding * 2
            ),
            toViewPoint = coordinateMapper::toViewPoint
        )
        if (filledStrokes.isNotEmpty()) {
            val repaintPoints = filledStrokes.flatMap { stroke ->
                stroke.points.map { point -> Point(point.dx, point.dy) }
            }
            val objectGroupId = nextStrokeObjectGroupId()
            val convertedStrokes = filledStrokes.map { stroke ->
                convertViewStrokeToAnchors(
                    stroke = stroke,
                    objectGroupId = objectGroupId,
                    forceRigidObjectAnchor = true
                )
            }
            reanchorRigidStrokesToTopLine(convertedStrokes)
            shiftRigidObjectGroupOutOfCodeText(convertedStrokes)

            for (stroke in convertedStrokes) {
                strokeWorkspace.addStroke(stroke)
            }
            documentSync.schedulePersistCurrentStrokes()
            refreshHistoryState()
            val convertedViewPoints = convertedStrokes.flatMap { stroke ->
                stroke.points.mapNotNull(coordinateMapper::toViewPoint)
            }
            DrawingViewportTools.repaintAround(canvas, repaintPoints + convertedViewPoints, dirtyPaddingPx)
        }
    }

    fun handleErasePressed(safePoint: Point) {
        clearSelection()
        saveStateForUndo()
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
        DrawingViewportTools.repaintAround(canvas, samples, dirtyPaddingPx + eraseRadius.roundToInt())
    }

    fun handleEraseReleased() {
        documentSync.schedulePersistCurrentStrokes()
        refreshHistoryState()
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
        DrawingViewportTools.repaintAround(
            canvas = canvas,
            points = oldPreviewPoints + newPreviewPoints + listOf(start, safePoint),
            padding = shapePreviewDirtyPaddingPx
        )
    }

    fun handleShapeReleased() {
        val preview = shapePreviewGetter()
        val previewPoints = preview?.points?.mapNotNull(coordinateMapper::toViewPoint).orEmpty()
        if (preview != null && shouldCommitShapePreview(preview, previewPoints)) {
            when (preview.kind) {
                ShapeKind.TEXT -> startTextEditor(
                    previewPoints = previewPoints,
                    saveUndoBeforeText = true,
                    objectGroupId = nextStrokeObjectGroupId()
                )
                else -> {
                    saveStateForUndo()
                    val objectGroupId = nextStrokeObjectGroupId()
                    val committed = preview.deepCopy()
                    if (committed.kind == ShapeKind.BALLOON) {
                        committed.objectGroupId = objectGroupId
                    }
                    shiftRigidObjectGroupOutOfCodeText(listOf(committed))
                    strokeWorkspace.addStroke(committed)
                    documentSync.schedulePersistCurrentStrokes()
                    refreshHistoryState()
                    if (committed.kind == ShapeKind.BALLOON) {
                        startTextEditor(
                            previewPoints = previewPoints,
                            textBounds = computeBalloonTextBounds(previewPoints),
                            saveUndoBeforeText = false,
                            objectGroupId = objectGroupId,
                            removeObjectGroupOnCancel = true
                        )
                    }
                }
            }
        }
        shapePreviewSetter(null)
        DrawingViewportTools.repaintAround(canvas, previewPoints, shapePreviewDirtyPaddingPx)
    }


    private fun startTextEditor(
        previewPoints: List<Point>,
        textBounds: Rectangle = computeTextBounds(previewPoints),
        saveUndoBeforeText: Boolean,
        objectGroupId: Long,
        removeObjectGroupOnCancel: Boolean = false
    ) {
        if (previewPoints.isEmpty()) {
            return
        }

        balloonTextEditor(textBounds) { text ->
            if (text == null) {
                val removedStrokes = if (removeObjectGroupOnCancel) removeObjectGroup(objectGroupId) else emptyList()
                if (removedStrokes.isNotEmpty()) {
                    val document = editorProvider()?.document
                    if (document != null) {
                        historyStore.discardLastUndo(document)
                    }
                    documentSync.schedulePersistCurrentStrokes()
                    refreshHistoryState()
                    val removedViewPoints = removedStrokes
                        .flatMap { stroke -> stroke.points.mapNotNull(coordinateMapper::toViewPoint) }
                    DrawingViewportTools.repaintAround(
                        canvas,
                        previewPoints + removedViewPoints,
                        shapePreviewDirtyPaddingPx
                    )
                }
                return@balloonTextEditor
            }

            val textStrokes = buildBalloonTextStrokes(text, textBounds, objectGroupId)
            if (textStrokes.isEmpty()) {
                documentSync.schedulePersistCurrentStrokes()
                refreshHistoryState()
                return@balloonTextEditor
            }

            if (saveUndoBeforeText) {
                saveStateForUndo()
            }

            for (stroke in textStrokes) {
                strokeWorkspace.addStroke(stroke)
            }
            documentSync.schedulePersistCurrentStrokes()
            refreshHistoryState()

            val textViewPoints = textStrokes.flatMap { stroke ->
                stroke.points.mapNotNull(coordinateMapper::toViewPoint)
            }
            DrawingViewportTools.repaintAround(canvas, previewPoints + textViewPoints, shapePreviewDirtyPaddingPx)
        }
    }

    private fun removeObjectGroup(objectGroupId: Long): List<StrokePath> {
        if (objectGroupId == 0L) return emptyList()

        val strokes = currentStrokesProvider()
        val removedStrokes = strokes.filter { it.objectGroupId == objectGroupId }
        if (removedStrokes.isEmpty()) {
            return emptyList()
        }

        strokes.removeAll { it.objectGroupId == objectGroupId }
        val boundsMap = strokeWorkspace.currentStrokeBounds()
        val geometryMap = strokeWorkspace.currentStrokeGeometries()
        for (stroke in removedStrokes) {
            boundsMap.remove(stroke.id)
            geometryMap.remove(stroke.id)
        }
        return removedStrokes
    }

    private fun buildBalloonTextStrokes(text: String?, textBounds: Rectangle, objectGroupId: Long): List<StrokePath> {
        val cleanText = text?.trim().orEmpty()
        if (cleanText.isEmpty()) {
            return emptyList()
        }

        coordinateMapper.beginObjectAnchor(Point(textBounds.x, textBounds.y))
        val textStrokes = try {
            BalloonTextStrokeFactory
                .buildTextStrokes(cleanText, textBounds, drawColorProvider(), balloonTextStyleProvider())
                .mapNotNull { stroke ->
                    convertViewStrokeToAnchors(
                        stroke = stroke,
                        useObjectAnchor = true,
                        kindOverride = ShapeKind.TEXT,
                        objectGroupId = objectGroupId
                    ).takeIf { converted -> converted.points.size >= 2 }
                }
        } finally {
            coordinateMapper.endObjectAnchor()
        }
        annotateTextGroup(textStrokes, cleanText, textBounds, objectGroupId)
        reanchorRigidStrokesToTopLine(textStrokes)
        shiftRigidObjectGroupOutOfCodeText(textStrokes)
        return textStrokes
    }

    private fun annotateTextGroup(
        strokes: List<StrokePath>,
        text: String,
        bounds: Rectangle,
        objectGroupId: Long
    ) {
        for (stroke in strokes) {
            stroke.objectGroupId = objectGroupId
            stroke.annotationText = text
            stroke.annotationTextStyle = balloonTextStyleProvider()
            stroke.annotationBounds = Rectangle(bounds)
        }
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
                DrawingViewportTools.repaintAround(canvas, acceptedPoints + listOf(previous, safePoint), dirtyPaddingPx)
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

    private fun selectedStrokes(): List<StrokePath> {
        if (selectedStrokeIds.isEmpty()) return emptyList()
        return currentStrokesProvider().filter { it.id in selectedStrokeIds }
    }

    private fun repaintSelection(strokes: List<StrokePath>) {
        DrawingViewportTools.repaintRect(canvas, selectionBounds(strokes))
    }

    private fun selectionBounds(strokes: List<StrokePath>): Rectangle? {
        return DrawingViewportTools.computeStrokesViewBounds(
            strokes = strokes,
            toViewPoint = coordinateMapper::toViewPoint,
            extraPadding = selectionRepaintPaddingPx
        )
    }

    private fun saveStateForUndo() {
        val document = editorProvider()?.document ?: return
        historyStore.saveStateForUndo(document, currentStrokesProvider())
        refreshHistoryState()
    }

    private fun applyErasePath(points: List<Point>) {
        if (points.isEmpty()) return
        val document = editorProvider()?.document ?: return
        val currentEditor = editorProvider() ?: return

        val allStrokes = currentStrokesProvider()
        if (allStrokes.isEmpty()) return

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
