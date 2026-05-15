package com.floatbar

import com.intellij.openapi.editor.Editor
import java.awt.Color
import java.awt.Point
import java.awt.Rectangle
import javax.swing.JPanel
import kotlin.math.abs
import kotlin.math.hypot
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
    private val eraseMinMovePx: Double,
    private val shapeEdgeSpacing: Double,
    private val ellipseSegments: Int
) {
    private val drawStrokeWidth = 3.5f
    private val shapePreviewDirtyPaddingPx = dirtyPaddingPx + 120
    private val minShapeCommitSizePx = 8

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
        documentSync.persistCurrentStrokes()
        refreshHistoryState()
        DrawingViewportTools.repaintRect(canvas, DrawingViewportTools.unionRectangles(beforeBounds, afterBounds))
    }

    fun handleFillPressed(safePoint: Point) {
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
            for (stroke in filledStrokes.map { convertViewStrokeToAnchors(it) }) {
                strokeWorkspace.addStroke(stroke)
            }
            documentSync.schedulePersistCurrentStrokes()
            refreshHistoryState()
            DrawingViewportTools.repaintAround(canvas, repaintPoints, dirtyPaddingPx)
        }
    }

    fun handleErasePressed(safePoint: Point) {
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
        shapePreviewSetter(null)
    }

    fun handleShapeDragged(start: Point, safePoint: Point, isShiftDown: Boolean) {
        val oldPreviewPoints = shapePreviewGetter()?.points?.mapNotNull(coordinateMapper::toViewPoint).orEmpty()
        shapePreviewSetter(buildShapeStroke(start, safePoint, selectedShapeKindProvider(), isShiftDown))
        val newPreviewPoints = shapePreviewGetter()?.points?.mapNotNull(coordinateMapper::toViewPoint).orEmpty()
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
            saveStateForUndo()
            val committed = preview.deepCopy()
            strokeWorkspace.addStroke(committed)
            documentSync.schedulePersistCurrentStrokes()
            refreshHistoryState()
        }
        shapePreviewSetter(null)
        DrawingViewportTools.repaintAround(canvas, previewPoints, shapePreviewDirtyPaddingPx)
    }


    private fun shouldCommitShapePreview(preview: StrokePath, previewPoints: List<Point>): Boolean {
        if (preview.points.size < 2 || previewPoints.size < 2) return false

        val kind = preview.kind
        if (kind == ShapeKind.LINE || kind == ShapeKind.ARROW) {
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
        return width >= minShapeCommitSizePx || height >= minShapeCommitSizePx
    }

    fun handleDrawPressed(safePoint: Point) {
        saveStateForUndo()
        val stroke = StrokePath(color = drawColorProvider(), width = drawStrokeWidth)
        currentStrokeSetter(stroke)
        strokeWorkspace.addStroke(stroke)
        addAnchorPoint(stroke, safePoint)
        DrawingViewportTools.repaintAround(canvas, listOf(safePoint), dirtyPaddingPx)
    }

    fun handleDrawDragged(previous: Point?, safePoint: Point) {
        val stroke = currentStrokeGetter() ?: return
        if (previous == null) {
            addAnchorPoint(stroke, safePoint)
            DrawingViewportTools.repaintAround(canvas, listOf(safePoint), dirtyPaddingPx)
        } else {
            val samples = strokePathTools.buildDrawSamples(previous, safePoint)
            for (p in samples) {
                addAnchorPoint(stroke, p)
            }
            DrawingViewportTools.repaintAround(canvas, samples + listOf(previous, safePoint), dirtyPaddingPx)
        }
    }

    fun handleDrawReleased() {
        currentStrokeGetter()?.let { stroke ->
            if (strokePathTools.simplifyFreehandStrokeInPlace(stroke)) {
                strokeWorkspace.updateStrokeBounds(stroke)
                strokeWorkspace.invalidateStrokeGeometry(stroke)
            }
        }
        currentStrokeSetter(null)
        documentSync.schedulePersistCurrentStrokes()
        refreshHistoryState()
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

        if (candidates.isEmpty()) return

        val rebuiltByStroke = PaintGeometryEngine.eraseAlongPathByStroke(
            strokes = candidates,
            localPoints = points,
            radius = eraseRadius,
            toViewPoint = coordinateMapper::toViewPoint
        )

        if (rebuiltByStroke.isEmpty()) return

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

    private fun addAnchorPoint(stroke: StrokePath, point: Point) {
        val anchor = coordinateMapper.viewPointToAnchor(point) ?: return
        val isFirstPoint = stroke.points.isEmpty()
        if (isFirstPoint) {
            coordinateMapper.lockStrokeFoldLayout(stroke, anchor)
        }
        val last = stroke.points.lastOrNull()
        if (last != null &&
            last.line == anchor.line &&
            last.column == anchor.column &&
            abs(last.dx - anchor.dx) < 2 &&
            abs(last.dy - anchor.dy) < 2
        ) {
            return
        }
        stroke.points += anchor
        strokeWorkspace.expandStrokeBoundsWithAnchor(stroke, anchor)
        strokeWorkspace.invalidateStrokeGeometry(stroke)
    }

    private fun convertViewStrokeToAnchors(stroke: StrokePath): StrokePath {
        val converted = stroke.points.mapNotNull { source ->
            val view = Point(source.dx, source.dy)
            coordinateMapper.viewPointToAnchor(view)
        }.toMutableList()

        return StrokePath(
            color = stroke.color,
            width = stroke.width,
            points = converted,
            filled = stroke.filled,
            kind = stroke.kind
        ).also { convertedStroke ->
            coordinateMapper.lockStrokeFoldLayout(convertedStroke)
        }
    }

    private fun buildShapeStroke(start: Point, end: Point, kind: ShapeKind, constrain: Boolean): StrokePath {
        return ShapeStrokeFactory.buildShapeStroke(
            start = start,
            end = end,
            kind = kind,
            constrain = constrain,
            color = drawColorProvider(),
            width = drawStrokeWidth,
            shapeEdgeSpacing = shapeEdgeSpacing,
            ellipseSegments = ellipseSegments,
            toAnchor = coordinateMapper::viewPointToAnchor
        ).also { shapeStroke ->
            coordinateMapper.lockStrokeFoldLayout(shapeStroke)
        }
    }
}
