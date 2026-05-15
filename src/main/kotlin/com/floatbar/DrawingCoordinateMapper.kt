package com.floatbar

import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.event.DocumentEvent
import java.awt.Point
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.math.max

class DrawingCoordinateMapper(
    private val canvas: JPanel,
    private val editorProvider: () -> Editor?,
    private val minCodeClearancePx: Int
) {
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
        val clampedEditorPoint = Point(
            max(editorPoint.x, lineInfo.lineEndX + minCodeClearancePx),
            editorPoint.y
        )
        return SwingUtilities.convertPoint(editor.contentComponent, clampedEditorPoint, canvas)
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
