package com.floatbar

import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.event.DocumentEvent
import java.awt.Point
import javax.swing.JPanel
import javax.swing.SwingUtilities
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

    data class LineInfo(
        val line: Int,
        val lineEndColumn: Int,
        val lineEndOffset: Int,
        val lineBaseX: Int,
        val lineBaseY: Int,
        val lineEndX: Int
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

        return LineInfo(
            line = safeLine,
            lineEndColumn = lineEndOffset - lineStartOffset,
            lineEndOffset = lineEndOffset,
            lineBaseX = lineBase.x,
            lineBaseY = lineBase.y,
            lineEndX = max(lineBase.x, lineEndPoint.x)
        )
    }

    fun clampPointToDrawableArea(point: Point): Point? {
        val editor = editorProvider() ?: return null
        val editorPoint = SwingUtilities.convertPoint(canvas, point, editor.contentComponent)
        val lineInfo = resolveLineInfo(editorPoint) ?: return null
        val lineRequiredX = lineInfo.lineEndX + minCodeClearancePx
        val straightWrapX = activeFreehandStraightWrapX
        val requiredX = if (straightWrapX != null) {
            max(straightWrapX, lineRequiredX)
        } else {
            lineRequiredX
        }
        val clampedEditorPoint = Point(
            max(editorPoint.x, requiredX),
            editorPoint.y
        )
        return SwingUtilities.convertPoint(editor.contentComponent, clampedEditorPoint, canvas)
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

    fun viewPointToAnchor(point: Point): AnchorPoint? {
        val editor = editorProvider() ?: return null
        val safePoint = clampPointToDrawableArea(point) ?: return null
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
            max(lineEndPoint.x, lineBase.x) + max(anchor.dx, minCodeClearancePx),
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
        val editor = editorProvider() ?: return 0
        var result = 17
        val collapsedRegions = editor.foldingModel.allFoldRegions
            .asSequence()
            .filter { !it.isExpanded }
            .sortedWith(compareBy({ it.startOffset }, { it.endOffset }))
        for (region in collapsedRegions) {
            result = 31 * result + region.startOffset
            result = 31 * result + region.endOffset
        }
        return result
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
                point.offset = remapOffset(point.offset, editStart, replacedEnd, insertedLength, delta)
                syncAnchorFromOffset(document, point)
            }
        }
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
        anchor.dx = max(anchor.dx, minCodeClearancePx)
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
}
