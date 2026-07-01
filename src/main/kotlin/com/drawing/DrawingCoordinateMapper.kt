package com.drawing

import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.ex.FoldingModelEx
import com.intellij.openapi.application.ApplicationManager
import java.awt.Point
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

class DrawingCoordinateMapper(
    private val canvas: JPanel,
    private val editorProvider: () -> Editor?,
    private val minCodeClearancePx: Int
) {
    private val straightWrapLineWindow = 18
    private val straightWrapActivationMarginPx = 24
    private var activeFreehandStraightWrapX: Int? = null
    private var activeObjectAnchorBase: ObjectAnchorBase? = null
    @Volatile private var foldLayoutSignature: Int = 0

    data class LineInfo(
        val line: Int,
        val lineStartOffset: Int,
        val lineEndColumn: Int,
        val lineEndOffset: Int,
        val lineBaseX: Int,
        val lineBaseY: Int,
        val lineEndX: Int,
        val firstTextColumn: Int,
        val firstTextX: Int,
        val hasCodeText: Boolean
    )

    private data class ObjectAnchorBase(
        val line: Int,
        val column: Int,
        val offset: Int,
        val lineEndX: Int,
        val lineBaseY: Int
    )

    fun resolveLineInfo(editorPoint: Point): LineInfo? {
        val editor = editorProvider() ?: return null
        val document = editor.document
        if (document.lineCount <= 0) return null

        val logicalAtPoint = editor.xyToLogicalPosition(editorPoint)
        val safeLine = logicalAtPoint.line.coerceIn(0, document.lineCount - 1)
        val lineStartOffset = document.getLineStartOffset(safeLine)
        val lineEndOffset = document.getLineEndOffset(safeLine)
        val lineEndLogical = editor.offsetToLogicalPosition(lineEndOffset)
        val lineBase = editor.logicalPositionToXY(LogicalPosition(safeLine, 0))
        val lineEndPoint = editor.logicalPositionToXY(lineEndLogical)
        val firstTextOffset = findFirstNonWhitespaceOffset(document, lineStartOffset, lineEndOffset)
        val firstTextLogical = editor.offsetToLogicalPosition(firstTextOffset)
        val firstTextPoint = editor.logicalPositionToXY(firstTextLogical)

        return LineInfo(
            line = safeLine,
            lineStartOffset = lineStartOffset,
            lineEndColumn = lineEndOffset - lineStartOffset,
            lineEndOffset = lineEndOffset,
            lineBaseX = lineBase.x,
            lineBaseY = lineBase.y,
            lineEndX = max(lineBase.x, lineEndPoint.x),
            firstTextColumn = firstTextOffset - lineStartOffset,
            firstTextX = firstTextPoint.x,
            hasCodeText = firstTextOffset < lineEndOffset
        )
    }

    fun clampPointToDrawableArea(
        point: Point,
        allowCodeArea: Boolean = false,
        rejectCodeArea: Boolean = false
    ): Point? {
        val editor = editorProvider() ?: return null
        val editorPoint = SwingUtilities.convertPoint(canvas, point, editor.contentComponent)
        val lineInfo = resolveLineInfo(editorPoint) ?: return null

        if (allowCodeArea || !isInsideProtectedCodeText(editor, editorPoint, lineInfo)) {
            return point
        }

        if (rejectCodeArea) return null

        val safeEditorX = activeFreehandStraightWrapX
            ?: (lineInfo.lineEndX + minCodeClearancePx)
        val visibleArea = editor.scrollingModel.visibleArea
        val rightVisibleEdge = visibleArea.x + visibleArea.width - minCodeClearancePx
        if (safeEditorX > rightVisibleEdge) {
            return null
        }

        return SwingUtilities.convertPoint(
            editor.contentComponent,
            Point(safeEditorX, editorPoint.y),
            canvas
        )
    }

    /**
     * Freehand drawings that start over/near code are pushed to the right side of the code.
     * Without a stroke-level straight edge, the clamp follows each individual line end and
     * a vertical wrapper stroke can curve around short/long code lines. While one freehand
     * stroke is being drawn, lock that clearance to a local max code edge so the wrapper
     * stays straighter.
     */
    fun beginFreehandStraightWrap(point: Point) {
        val editor = editorProvider() ?: run {
            activeFreehandStraightWrapX = null
            return
        }
        val editorPoint = SwingUtilities.convertPoint(canvas, point, editor.contentComponent)
        val lineInfo = resolveLineInfo(editorPoint) ?: run {
            activeFreehandStraightWrapX = null
            return
        }

        val lineRequiredX = lineInfo.lineEndX + minCodeClearancePx
        activeFreehandStraightWrapX = if (editorPoint.x <= lineRequiredX + straightWrapActivationMarginPx) {
            computeLocalMaxLineEndX(editor, lineInfo.line, straightWrapLineWindow) + minCodeClearancePx
        } else {
            null
        }
    }

    fun endFreehandStraightWrap() {
        activeFreehandStraightWrapX = null
    }

    private fun computeLocalMaxLineEndX(editor: Editor, centerLine: Int, radius: Int): Int {
        val document = editor.document
        if (document.lineCount <= 0) return 0

        val firstLine = max(0, centerLine - radius)
        val lastLine = min(document.lineCount - 1, centerLine + radius)
        var maxLineEndX = 0

        for (line in firstLine..lastLine) {
            val lineEndOffset = document.getLineEndOffset(line)
            val lineEndLogical = editor.offsetToLogicalPosition(lineEndOffset)
            val lineBase = editor.logicalPositionToXY(LogicalPosition(line, 0))
            val lineEndPoint = editor.logicalPositionToXY(lineEndLogical)
            maxLineEndX = max(maxLineEndX, max(lineBase.x, lineEndPoint.x))
        }

        return maxLineEndX
    }

    fun viewPointToAnchor(point: Point, allowCodeArea: Boolean = false): AnchorPoint? {
        val editor = editorProvider() ?: return null
        val safePoint = clampPointToDrawableArea(point, allowCodeArea = allowCodeArea) ?: return null
        val editorPoint = SwingUtilities.convertPoint(canvas, safePoint, editor.contentComponent)
        val lineInfo = resolveLineInfo(editorPoint) ?: return null

        return AnchorPoint(
            line = lineInfo.line,
            column = lineInfo.lineEndColumn,
            dx = editorPoint.x - lineInfo.lineEndX,
            dy = editorPoint.y - lineInfo.lineBaseY,
            offset = lineInfo.lineEndOffset
        )
    }

    fun beginObjectAnchor(point: Point, allowCodeArea: Boolean = false) {
        activeObjectAnchorBase = resolveObjectAnchorBase(point, allowCodeArea)
    }

    fun endObjectAnchor() {
        activeObjectAnchorBase = null
    }

    fun viewPointToObjectAnchor(point: Point, allowCodeArea: Boolean = false): AnchorPoint? {
        val base = activeObjectAnchorBase ?: resolveObjectAnchorBase(point, allowCodeArea) ?: return null
        val editor = editorProvider() ?: return null
        val safePoint = clampPointToDrawableArea(point, allowCodeArea = allowCodeArea) ?: return null
        val editorPoint = SwingUtilities.convertPoint(canvas, safePoint, editor.contentComponent)

        return AnchorPoint(
            line = base.line,
            column = base.column,
            dx = editorPoint.x - base.lineEndX,
            dy = editorPoint.y - base.lineBaseY,
            offset = base.offset
        )
    }

    fun reanchorStrokeToObjectAnchor(stroke: StrokePath, baseAnchor: AnchorPoint? = null): Boolean {
        if (stroke.points.size < 2) return false
        val anchoredContentPoints = stroke.points.map { anchor ->
            anchor to (toContentPoint(anchor.copy()) ?: return false)
        }
        val selectedBaseAnchor = baseAnchor
            ?: selectObjectReanchorBase(stroke, anchoredContentPoints)
            ?: return false
        val base = resolveObjectAnchorBase(selectedBaseAnchor) ?: return false

        stroke.points.clear()
        stroke.points.addAll(
            anchoredContentPoints.map { (_, point) ->
                AnchorPoint(
                    line = base.line,
                    column = base.column,
                    dx = point.x - base.lineEndX,
                    dy = point.y - base.lineBaseY,
                    offset = base.offset
                )
            }
        )
        stroke.rigidObjectAnchor = true
        return true
    }

    fun shiftRigidStrokeOutOfCodeText(stroke: StrokePath): Boolean {
        val shiftX = requiredShiftOutOfCodeText(stroke)
        if (shiftX <= 0) return false
        shiftStrokeHorizontally(stroke, shiftX)
        return true
    }

    fun requiredShiftOutOfCodeText(stroke: StrokePath): Int {
        if (!stroke.usesRigidObjectAnchoring() || stroke.points.size < 2) return 0
        val editor = editorProvider() ?: return 0
        val document = editor.document
        if (document.lineCount <= 0) return 0

        val contentPoints = stroke.points.map { anchor ->
            toContentPoint(stroke, anchor.copy()) ?: return 0
        }
        val minX = contentPoints.minOf { it.x }
        val verticalPadding = ceil(stroke.width.toDouble() / 2.0).toInt().coerceAtLeast(1)
        val topY = contentPoints.minOf { it.y } - verticalPadding
        val bottomY = contentPoints.maxOf { it.y } + verticalPadding
        val topLine = editor.xyToLogicalPosition(Point(0, topY)).line.coerceIn(0, document.lineCount - 1)
        val bottomLine = editor.xyToLogicalPosition(Point(0, bottomY)).line.coerceIn(0, document.lineCount - 1)
        if (topLine > bottomLine) return 0

        var requiredLeftX = Int.MIN_VALUE
        for (line in topLine..bottomLine) {
            val lineInfo = resolveLineInfo(editor.logicalPositionToXY(LogicalPosition(line, 0))) ?: continue
            if (!lineInfo.hasCodeText) continue
            requiredLeftX = max(requiredLeftX, lineInfo.lineEndX + minCodeClearancePx)
        }
        if (requiredLeftX == Int.MIN_VALUE || minX >= requiredLeftX) return 0

        return requiredLeftX - minX
    }

    fun requiredShiftOutOfCodeText(fill: RasterFillPath): Int {
        if (fill.width <= 0 || fill.height <= 0) return 0
        val editor = editorProvider() ?: return 0
        val document = editor.document
        if (document.lineCount <= 0) return 0

        val topLeft = toContentPoint(fill.anchor.copy()) ?: return 0
        val minX = topLeft.x
        val topY = topLeft.y
        val bottomY = topLeft.y + fill.height
        val topLine = editor.xyToLogicalPosition(Point(0, topY)).line.coerceIn(0, document.lineCount - 1)
        val bottomLine = editor.xyToLogicalPosition(Point(0, bottomY)).line.coerceIn(0, document.lineCount - 1)
        if (topLine > bottomLine) return 0

        var requiredLeftX = Int.MIN_VALUE
        for (line in topLine..bottomLine) {
            val lineInfo = resolveLineInfo(editor.logicalPositionToXY(LogicalPosition(line, 0))) ?: continue
            if (!lineInfo.hasCodeText) continue
            requiredLeftX = max(requiredLeftX, lineInfo.lineEndX + minCodeClearancePx)
        }
        if (requiredLeftX == Int.MIN_VALUE || minX >= requiredLeftX) return 0

        return requiredLeftX - minX
    }

    fun shiftStrokeHorizontally(stroke: StrokePath, shiftX: Int) {
        if (shiftX == 0) return
        for (point in stroke.points) {
            point.dx += shiftX
        }
    }

    fun shiftRasterFillHorizontally(fill: RasterFillPath, shiftX: Int) {
        if (shiftX == 0) return
        fill.anchor.dx += shiftX
    }

    fun moveRasterFillByViewDelta(fill: RasterFillPath, deltaX: Int, deltaY: Int): Boolean {
        if (deltaX == 0 && deltaY == 0) return false
        val topLeft = toViewPoint(fill.anchor.copy()) ?: return false
        val moved = Point(topLeft.x + deltaX, topLeft.y + deltaY)
        val movedAnchor = viewPointToAnchor(moved, allowCodeArea = true) ?: return false
        fill.anchor.line = movedAnchor.line
        fill.anchor.column = movedAnchor.column
        fill.anchor.dx = movedAnchor.dx
        fill.anchor.dy = movedAnchor.dy
        fill.anchor.offset = movedAnchor.offset
        fill.anchor.outsideCode = movedAnchor.outsideCode
        fill.anchor.afterLineEndPx = movedAnchor.afterLineEndPx
        fill.anchor.foldHiddenHeightAbove = movedAnchor.foldHiddenHeightAbove
        fill.anchor.foldLayoutBaseY = movedAnchor.foldLayoutBaseY
        return true
    }

    fun moveStrokesByViewDelta(strokes: List<StrokePath>, deltaX: Int, deltaY: Int): Boolean {
        if (strokes.isEmpty() || (deltaX == 0 && deltaY == 0)) return false

        val movedPointsByStroke = LinkedHashMap<StrokePath, List<Point>>()
        for (stroke in strokes) {
            val movedPoints = stroke.points.map { anchor ->
                val viewPoint = toViewPoint(stroke, anchor.copy()) ?: return false
                Point(viewPoint.x + deltaX, viewPoint.y + deltaY)
            }
            if (movedPoints.size != stroke.points.size) return false
            movedPointsByStroke[stroke] = movedPoints
        }

        val basePoint = movedPointsByStroke.values
            .flatten()
            .minWithOrNull(compareBy<Point> { it.y }.thenBy { it.x })
            ?: return false

        val convertedByStroke = LinkedHashMap<StrokePath, List<AnchorPoint>>()
        beginObjectAnchor(basePoint, allowCodeArea = true)
        try {
            for ((stroke, movedPoints) in movedPointsByStroke) {
                val converted = movedPoints.map { point ->
                    viewPointToObjectAnchor(point, allowCodeArea = true) ?: return false
                }
                convertedByStroke[stroke] = converted
            }
        } finally {
            endObjectAnchor()
        }

        for ((stroke, converted) in convertedByStroke) {
            stroke.points.clear()
            stroke.points.addAll(converted)
            stroke.rigidObjectAnchor = true
        }
        return true
    }

    private fun selectObjectReanchorBase(
        stroke: StrokePath,
        anchoredContentPoints: List<Pair<AnchorPoint, Point>>
    ): AnchorPoint? {
        val editor = editorProvider() ?: return stroke.points.firstOrNull()
        val document = editor.document
        if (document.lineCount <= 0 || anchoredContentPoints.isEmpty()) return stroke.points.firstOrNull()

        val topPoint = anchoredContentPoints.minWithOrNull(
            compareBy<Pair<AnchorPoint, Point>> { (_, point) -> point.y }
                .thenBy { (_, point) -> point.x }
        )?.second ?: return stroke.points.firstOrNull()
        val topLine = editor.xyToLogicalPosition(topPoint).line.coerceIn(0, document.lineCount - 1)
        val lineStartOffset = document.getLineStartOffset(topLine)
        val lineEndOffset = document.getLineEndOffset(topLine)

        return AnchorPoint(
            line = topLine,
            column = lineEndOffset - lineStartOffset,
            dx = 0,
            dy = 0,
            offset = lineEndOffset
        )
    }

    private fun resolveObjectAnchorBase(point: Point, allowCodeArea: Boolean): ObjectAnchorBase? {
        val editor = editorProvider() ?: return null
        val safePoint = clampPointToDrawableArea(point, allowCodeArea = allowCodeArea) ?: return null
        val editorPoint = SwingUtilities.convertPoint(canvas, safePoint, editor.contentComponent)
        val lineInfo = resolveLineInfo(editorPoint) ?: return null

        return ObjectAnchorBase(
            line = lineInfo.line,
            column = lineInfo.lineEndColumn,
            offset = lineInfo.lineEndOffset,
            lineEndX = lineInfo.lineEndX,
            lineBaseY = lineInfo.lineBaseY
        )
    }

    private fun resolveObjectAnchorBase(anchor: AnchorPoint): ObjectAnchorBase? {
        val editor = editorProvider() ?: return null
        val document = editor.document
        val normalized = anchor.copy()
        normalizeAnchor(document, normalized)
        val safeLine = normalized.line.coerceIn(0, document.lineCount.coerceAtLeast(1) - 1)
        val lineStartOffset = document.getLineStartOffset(safeLine)
        val lineEndOffset = document.getLineEndOffset(safeLine)
        val lineEndLogical = editor.offsetToLogicalPosition(lineEndOffset)
        val lineBase = editor.logicalPositionToXY(LogicalPosition(safeLine, 0))
        val lineEndPoint = editor.logicalPositionToXY(lineEndLogical)

        return ObjectAnchorBase(
            line = safeLine,
            column = lineEndOffset - lineStartOffset,
            offset = lineEndOffset,
            lineEndX = max(lineBase.x, lineEndPoint.x),
            lineBaseY = lineBase.y
        )
    }

    /**
     * Convert a document anchor to the editor content coordinate system.
     *
     * Important fold rule:
     * Unrelated folds above the drawing should be handled by IntelliJ's own line mapping.
     * Do not add an extra fold correction here, because that freezes the drawing on screen
     * and prevents it from following the code when a block above is collapsed/expanded.
     */
    fun toContentPoint(anchor: AnchorPoint): Point? {
        val editor = editorProvider() ?: return null
        val document = editor.document
        normalizeAnchor(document, anchor)

        val safeLine = anchor.line.coerceIn(0, document.lineCount.coerceAtLeast(1) - 1)
        val lineEndOffset = document.getLineEndOffset(safeLine)
        val lineEndLogical = editor.offsetToLogicalPosition(lineEndOffset)
        val lineBase = editor.logicalPositionToXY(LogicalPosition(safeLine, 0))
        val lineEndPoint = editor.logicalPositionToXY(lineEndLogical)

        return Point(
            max(lineEndPoint.x, lineBase.x) + anchor.dx,
            lineBase.y + anchor.dy
        )
    }

    /**
     * Stroke-aware overload kept for renderer/workspace compatibility.
     * It intentionally delegates to the normal anchor mapping. A whole-stroke fold
     * correction was tested, but it cancels IntelliJ's line movement and makes drawings
     * stop following the code when folds above change.
     */
    fun toContentPoint(stroke: StrokePath, anchor: AnchorPoint): Point? {
        return toContentPoint(anchor)
    }

    fun toViewPoint(anchor: AnchorPoint): Point? {
        val editor = editorProvider() ?: return null
        val contentPoint = toContentPoint(anchor) ?: return null
        return SwingUtilities.convertPoint(editor.contentComponent, contentPoint, canvas)
    }

    fun toViewPoint(stroke: StrokePath, anchor: AnchorPoint): Point? {
        val editor = editorProvider() ?: return null
        val contentPoint = toContentPoint(stroke, anchor) ?: return null
        return SwingUtilities.convertPoint(editor.contentComponent, contentPoint, canvas)
    }

    /**
     * Still used to invalidate cached geometry when fold regions change.
     * We do not use it to compensate coordinates; it only prevents stale cached paths.
     */
    fun currentFoldLayoutSignature(): Int {
        return foldLayoutSignature
    }

    fun refreshFoldLayoutSignature() {
        foldLayoutSignature = computeFoldLayoutSignature()
    }

    /**
     * Compatibility no-op for callers from the previous fold-stability attempt.
     * Stored anchors remain line/offset based; no screen-freezing baseline is captured.
     */
    fun lockStrokeFoldLayout(stroke: StrokePath, anchor: AnchorPoint? = stroke.points.firstOrNull()) {
        // Intentionally no-op.
    }

    /** Compatibility no-op. */
    fun ensureStrokeFoldLayoutBaseline(stroke: StrokePath) {
        // Intentionally no-op.
    }

    /** Compatibility no-op. */
    fun refreshStrokeFoldLayoutBaseline(stroke: StrokePath) {
        // Intentionally no-op.
    }

    fun remapAnchorsForDocumentChange(document: Document, event: DocumentEvent, strokes: List<StrokePath>) {
        val editStart = event.offset
        val replacedEnd = event.offset + event.oldLength
        val insertedLength = event.newLength
        val delta = insertedLength - event.oldLength

        for (stroke in strokes) {
            for (point in stroke.points) {
                remapAnchorForDocumentChange(document, event, point)
            }
        }
    }

    fun remapAnchorForDocumentChange(document: Document, event: DocumentEvent, anchor: AnchorPoint) {
        val editStart = event.offset
        val replacedEnd = event.offset + event.oldLength
        val insertedLength = event.newLength
        val delta = insertedLength - event.oldLength

        anchor.offset = remapOffset(anchor.offset, editStart, replacedEnd, insertedLength, delta)
        syncAnchorFromOffset(document, anchor)
    }

    fun normalizeAnchor(document: Document, anchor: AnchorPoint) {
        val maxOffset = document.textLength.coerceAtLeast(0)

        if (anchor.offset <= 0 && (anchor.line > 0 || anchor.column > 0)) {
            val safeLine = anchor.line.coerceIn(0, document.lineCount.coerceAtLeast(1) - 1)
            val lineEnd = document.getLineEndOffset(safeLine)
            anchor.offset = lineEnd.coerceIn(0, maxOffset)
        }

        anchor.offset = anchor.offset.coerceIn(0, maxOffset)
        syncAnchorFromOffset(document, anchor)
        // Keep the original horizontal offset. Drawings can be made over code, so dx can
        // be negative. Clamping it here moves saved/reloaded strokes away from the cursor.
    }

    private fun isInsideProtectedCodeText(editor: Editor, editorPoint: Point, lineInfo: LineInfo): Boolean {
        if (!lineInfo.hasCodeText) return false

        val logicalAtPoint = editor.xyToLogicalPosition(editorPoint)
        if (logicalAtPoint.line != lineInfo.line) return false
        if (logicalAtPoint.column < lineInfo.firstTextColumn) return false
        if (logicalAtPoint.column > lineInfo.lineEndColumn) return false

        val leftProtectedX = min(lineInfo.firstTextX, lineInfo.lineEndX) - minCodeClearancePx
        val rightProtectedX = max(lineInfo.firstTextX, lineInfo.lineEndX) + minCodeClearancePx
        return editorPoint.x in leftProtectedX..rightProtectedX
    }

    private fun findFirstNonWhitespaceOffset(document: Document, startOffset: Int, endOffset: Int): Int {
        val chars = document.charsSequence
        var offset = startOffset
        while (offset < endOffset && chars[offset].isWhitespace()) {
            offset++
        }
        return offset
    }

    private fun remapOffset(
        offset: Int,
        editStart: Int,
        replacedEnd: Int,
        insertedLength: Int,
        delta: Int
    ): Int {
        return when {
            replacedEnd == editStart && offset >= editStart -> offset + insertedLength
            offset > replacedEnd -> offset + delta
            offset >= editStart -> editStart + insertedLength
            else -> offset
        }.coerceAtLeast(0)
    }

    private fun syncAnchorFromOffset(document: Document, anchor: AnchorPoint) {
        val clampedOffset = anchor.offset.coerceIn(0, document.textLength.coerceAtLeast(0))
        anchor.offset = clampedOffset
        val line = document.getLineNumber(clampedOffset)
        val lineStart = document.getLineStartOffset(line)
        val lineEnd = document.getLineEndOffset(line)
        anchor.line = line
        anchor.column = lineEnd - lineStart
    }

    private fun computeFoldLayoutSignature(): Int {
        val editor = editorProvider() ?: return 0
        return ApplicationManager.getApplication().runReadAction<Int> {
            val foldingModel = editor.foldingModel as? FoldingModelEx ?: return@runReadAction 0
            var signature = 1
            foldingModel.allFoldRegions
                .asSequence()
                .sortedWith(
                    compareBy<com.intellij.openapi.editor.FoldRegion> { it.startOffset }
                        .thenBy { it.endOffset }
                        .thenBy { it.isExpanded() }
                        .thenBy { it.placeholderText ?: "" }
                )
                .forEach { region ->
                    signature = 31 * signature + region.startOffset
                    signature = 31 * signature + region.endOffset
                    signature = 31 * signature + if (region.isExpanded) 1 else 0
                    signature = 31 * signature + (region.placeholderText?.hashCode() ?: 0)
                }
            signature
        }
    }
}
