package com.floatbar

import com.intellij.openapi.editor.Document
import java.awt.Color
import java.util.WeakHashMap

data class StrokeLineBounds(
    val minLine: Int,
    val maxLine: Int
)

class DrawingStrokeStore(
    private val stateService: FloatBarDrawingStateService
) {
    private val strokesByDocument = WeakHashMap<Document, MutableList<StrokePath>>()
    private val strokeBoundsByDocument = WeakHashMap<Document, MutableMap<Long, StrokeLineBounds>>()
    private val strokeGeometryByDocument = WeakHashMap<Document, MutableMap<Long, StrokeGeometryContent>>()

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

        val loaded = stateService.getStrokes(filePath).map { saved ->
            StrokePath(
                color = Color(saved.color, true),
                width = saved.width,
                points = saved.points.map { point ->
                    val hasOffset = point.offset > 0 || point.line > 0 || point.column > 0
                    val anchor = if (hasOffset) {
                        AnchorPoint(
                            line = point.line,
                            column = point.column,
                            dx = point.dx,
                            dy = point.dy,
                            offset = point.offset,
                            outsideCode = point.outsideCode,
                            afterLineEndPx = point.afterLineEndPx
                        )
                    } else {
                        AnchorPoint(
                            line = point.line,
                            column = 0,
                            dx = point.x,
                            dy = point.dy,
                            offset = 0,
                            outsideCode = point.outsideCode,
                            afterLineEndPx = point.afterLineEndPx
                        )
                    }
                    normalizeAnchor(document, anchor)
                    anchor
                }.toMutableList(),
                filled = saved.filled,
                kind = saved.kind?.let { runCatching { ShapeKind.valueOf(it) }.getOrNull() }
            )
        }.toMutableList()

        strokesByDocument[document] = loaded
        return loaded
    }

    fun persistStrokes(filePath: String?, strokes: List<StrokePath>) {
        if (filePath.isNullOrEmpty()) return

        val saved = strokes.map { stroke ->
            SavedStroke(
                color = stroke.color.rgb,
                width = stroke.width,
                points = stroke.points.map { point ->
                    SavedPoint(
                        line = point.line,
                        column = point.column,
                        dx = point.dx,
                        dy = point.dy,
                        offset = point.offset,
                        outsideCode = point.outsideCode,
                        afterLineEndPx = point.afterLineEndPx,
                        x = 0
                    )
                }.toMutableList(),
                filled = stroke.filled,
                kind = stroke.kind?.name
            )
        }
        stateService.setStrokes(filePath, saved)
    }
}
