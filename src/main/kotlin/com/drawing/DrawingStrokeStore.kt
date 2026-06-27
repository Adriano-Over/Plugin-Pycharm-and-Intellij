package com.drawing

import com.intellij.openapi.editor.Document
import java.awt.Color
import java.awt.Rectangle
import java.util.WeakHashMap

data class StrokeLineBounds(
    val minLine: Int,
    val maxLine: Int
)

class DrawingStrokeStore(
    private val stateService: DrawingStateService
) {
    private val strokesByDocument = WeakHashMap<Document, MutableList<StrokePath>>()
    private val strokeBoundsByDocument = WeakHashMap<Document, MutableMap<Long, StrokeLineBounds>>()
    private val strokeGeometryByDocument = WeakHashMap<Document, MutableMap<Long, StrokeGeometryContent>>()

    private fun SavedPoint.usesModernAnchorStorage(): Boolean {
        if (anchorStorageVersion >= 1) return true

        val looksLikeLegacyXAnchor = x != 0 &&
            dx == 0 &&
            offset == 0 &&
            column == 0 &&
            !outsideCode &&
            afterLineEndPx == 0
        if (looksLikeLegacyXAnchor) return false

        return offset != 0 ||
            column != 0 ||
            dx != 0 ||
            dy != 0 ||
            outsideCode ||
            afterLineEndPx != 0
    }

    fun currentStrokes(document: Document?): MutableList<StrokePath> {
        if (document == null) return mutableListOf()
        return strokesByDocument.getOrPut(document) { mutableListOf() }
    }

    fun currentStrokeBounds(document: Document?): MutableMap<Long, StrokeLineBounds> {
        if (document == null) return mutableMapOf()
        return strokeBoundsByDocument.getOrPut(document) { mutableMapOf() }
    }

    fun currentStrokeGeometries(document: Document?): MutableMap<Long, StrokeGeometryContent> {
        if (document == null) return mutableMapOf()
        return strokeGeometryByDocument.getOrPut(document) { mutableMapOf() }
    }

    fun setStrokes(document: Document?, strokes: MutableList<StrokePath>) {
        if (document == null) return
        strokesByDocument[document] = strokes
    }

    fun clearDocument(document: Document?) {
        if (document == null) return
        strokesByDocument[document] = mutableListOf()
        strokeBoundsByDocument[document] = mutableMapOf()
        strokeGeometryByDocument[document] = mutableMapOf()
    }

    fun loadPersistedStrokes(
        filePath: String?,
        document: Document?,
        normalizeAnchor: (Document, AnchorPoint) -> Unit
    ): MutableList<StrokePath> {
        if (filePath.isNullOrEmpty() || document == null) return mutableListOf()

        val savedFromState = stateService.getStrokes(filePath)
        DrawingDiagnosticLog.info("STORE", "loadPersistedStrokes file=$filePath saved=${savedFromState.size}")
        val loaded = savedFromState.map { saved ->
            StrokePath(
                color = Color(saved.color, true),
                width = saved.width,
                points = saved.points.map { point ->
                    val modernAnchor = point.usesModernAnchorStorage()
                    val anchor = if (modernAnchor) {
                        AnchorPoint(
                            line = point.line,
                            column = point.column,
                            dx = point.dx,
                            dy = point.dy,
                            offset = point.offset,
                            outsideCode = point.outsideCode,
                            afterLineEndPx = point.afterLineEndPx,
                            foldHiddenHeightAbove = if (point.anchorStorageVersion >= 3) {
                                point.foldHiddenHeightAbove
                            } else {
                                UNSET_FOLD_HIDDEN_HEIGHT_ABOVE
                            }
                        )
                    } else {
                        AnchorPoint(
                            line = point.line,
                            column = 0,
                            dx = point.x,
                            dy = point.dy,
                            offset = 0,
                            outsideCode = point.outsideCode,
                            afterLineEndPx = point.afterLineEndPx,
                            foldHiddenHeightAbove = UNSET_FOLD_HIDDEN_HEIGHT_ABOVE
                        )
                    }
                    normalizeAnchor(document, anchor)
                    anchor
                }.toMutableList(),
                filled = saved.filled,
                kind = saved.kind?.let { runCatching { ShapeKind.valueOf(it) }.getOrNull() },
                objectGroupId = saved.objectGroupId,
                rigidObjectAnchor = saved.rigidObjectAnchor,
                annotationText = saved.annotationText,
                annotationTextStyle = saved.annotationTextStyle?.let { runCatching { BalloonTextStyle.valueOf(it) }.getOrNull() },
                annotationBounds = if (saved.annotationBoundsWidth > 0 && saved.annotationBoundsHeight > 0) {
                    Rectangle(saved.annotationBoundsX, saved.annotationBoundsY, saved.annotationBoundsWidth, saved.annotationBoundsHeight)
                } else {
                    null
                }
            )
        }.toMutableList()

        strokesByDocument[document] = loaded
        DrawingDiagnosticLog.info("STORE", "loadPersistedStrokes mapped=${loaded.size} file=$filePath")
        return loaded
    }

    fun persistStrokes(filePath: String?, strokes: List<StrokePath>) {
        if (filePath.isNullOrEmpty()) {
            DrawingDiagnosticLog.warn("STORE", "persistStrokes skipped empty filePath strokes=${strokes.size}")
            return
        }
        DrawingDiagnosticLog.info("STORE", "persistStrokes file=$filePath strokes=${strokes.size} first=${DrawingDiagnosticLog.strokeSummary(strokes.firstOrNull())}")

        val saved = strokes.map { stroke ->
            SavedStroke(
                color = stroke.color.rgb,
                width = stroke.width,
                points = stroke.points.map { point ->
                    SavedPoint(
                        anchorStorageVersion = 3,
                        line = point.line,
                        column = point.column,
                        dx = point.dx,
                        dy = point.dy,
                        offset = point.offset,
                        outsideCode = point.outsideCode,
                        afterLineEndPx = point.afterLineEndPx,
                        foldHiddenHeightAbove = point.foldHiddenHeightAbove,
                        x = 0
                    )
                }.toMutableList(),
                filled = stroke.filled,
                kind = stroke.kind?.name,
                objectGroupId = stroke.objectGroupId,
                rigidObjectAnchor = stroke.rigidObjectAnchor,
                annotationText = stroke.annotationText,
                annotationTextStyle = stroke.annotationTextStyle?.name,
                annotationBoundsX = stroke.annotationBounds?.x ?: 0,
                annotationBoundsY = stroke.annotationBounds?.y ?: 0,
                annotationBoundsWidth = stroke.annotationBounds?.width ?: 0,
                annotationBoundsHeight = stroke.annotationBounds?.height ?: 0
            )
        }
        DrawingDiagnosticLog.info("STORE", "persistStrokes savedPayload=${saved.size} pointCounts=${saved.joinToString(",") { it.points.size.toString() }}")
        stateService.setStrokes(filePath, saved)
    }
}
