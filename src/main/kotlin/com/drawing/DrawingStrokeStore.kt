package com.drawing

import com.intellij.openapi.editor.Document
import java.awt.Color
import java.util.WeakHashMap

data class StrokeLineBounds(
    val minLine: Int,
    val maxLine: Int
)

class DrawingStrokeStore(
    private val stateService: DrawingStateService
) {
    private val strokesByDocument = WeakHashMap<Document, MutableList<StrokePath>>()
    private val rasterFillsByDocument = WeakHashMap<Document, MutableList<RasterFillPath>>()
    private val annotationsByDocument = WeakHashMap<Document, MutableList<AnnotationPath>>()
    private val strokeBoundsByDocument = WeakHashMap<Document, MutableMap<Long, StrokeLineBounds>>()
    private val strokeGeometryByDocument = WeakHashMap<Document, MutableMap<Long, StrokeGeometryContent>>()

    fun currentStrokes(document: Document?): MutableList<StrokePath> {
        if (document == null) return mutableListOf()
        return strokesByDocument.getOrPut(document) { mutableListOf() }
    }

    fun currentRasterFills(document: Document?): MutableList<RasterFillPath> {
        if (document == null) return mutableListOf()
        return rasterFillsByDocument.getOrPut(document) { mutableListOf() }
    }

    fun currentAnnotations(document: Document?): MutableList<AnnotationPath> {
        if (document == null) return mutableListOf()
        return annotationsByDocument.getOrPut(document) { mutableListOf() }
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

    fun setRasterFills(document: Document?, rasterFills: MutableList<RasterFillPath>) {
        if (document == null) return
        rasterFillsByDocument[document] = rasterFills
    }

    fun setAnnotations(document: Document?, annotations: MutableList<AnnotationPath>) {
        if (document == null) return
        annotationsByDocument[document] = annotations
    }

    fun clearDocument(document: Document?) {
        if (document == null) return
        strokesByDocument[document] = mutableListOf()
        rasterFillsByDocument[document] = mutableListOf()
        annotationsByDocument[document] = mutableListOf()
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
        val savedRasterFills = stateService.getRasterFills(filePath)
        val savedAnnotations = stateService.getAnnotations(filePath)
        DrawingDiagnosticLog.info("STORE", "loadPersistedStrokes file=$filePath saved=${savedFromState.size}")
        val loaded = savedFromState.map { saved ->
            StrokePath(
                color = Color(saved.color, true),
                width = saved.width,
                points = saved.points.map { point ->
                    val anchor = point.toAnchor()
                    normalizeAnchor(document, anchor)
                    anchor
                }.toMutableList(),
                filled = saved.filled,
                kind = saved.kind?.let { runCatching { ShapeKind.valueOf(it) }.getOrNull() },
                objectGroupId = saved.objectGroupId,
                rigidObjectAnchor = saved.rigidObjectAnchor
            )
        }.toMutableList()

        val loadedAnnotations = savedAnnotations.mapNotNull { saved ->
            val anchor = saved.anchor.toAnchor()
            normalizeAnchor(document, anchor)
            val kind = runCatching { AnnotationKind.valueOf(saved.kind) }.getOrNull() ?: AnnotationKind.TEXT
            val style = runCatching { BalloonTextStyle.valueOf(saved.style) }.getOrNull() ?: BalloonTextStyle.SOLID
            if (saved.text.isBlank() || saved.width <= 0 || saved.height <= 0) {
                null
            } else {
                AnnotationPath(
                    id = if (saved.id != 0L) saved.id else nextStrokeObjectGroupId(),
                    text = saved.text,
                    color = Color(saved.color, true),
                    anchor = anchor,
                    width = saved.width,
                    height = saved.height,
                    kind = kind,
                    style = style,
                    objectGroupId = saved.objectGroupId
                )
            }
        }.toMutableList()

        val supportedRasterFills = savedRasterFills.filter { saved ->
            RasterFillCodec.isSupportedPersistedRasterFill(saved.width, saved.height, saved.pngBase64)
        }
        val skippedRasterFills = savedRasterFills.size - supportedRasterFills.size
        if (skippedRasterFills > 0) {
            DrawingDiagnosticLog.warn(
                "STORE",
                "loadPersistedStrokes skipped unsupported rasterFills=$skippedRasterFills file=$filePath"
            )
        }
        val loadedRasterFills = supportedRasterFills.map { saved ->
            val anchor = saved.anchor.toAnchor()
            normalizeAnchor(document, anchor)
            RasterFillPath(
                id = if (saved.id != 0L) saved.id else nextStrokeObjectGroupId(),
                color = Color(saved.color, true),
                anchor = anchor,
                width = saved.width,
                height = saved.height,
                pngBase64 = saved.pngBase64,
                objectGroupId = saved.objectGroupId
            )
        }.toMutableList()

        strokesByDocument[document] = loaded
        rasterFillsByDocument[document] = loadedRasterFills
        annotationsByDocument[document] = loadedAnnotations
        DrawingDiagnosticLog.info("STORE", "loadPersistedStrokes mapped=${loaded.size} rasterFills=${loadedRasterFills.size} annotations=${loadedAnnotations.size} file=$filePath")
        return loaded
    }

    fun persistStrokes(filePath: String?, strokes: List<StrokePath>) {
        persistDrawing(filePath, strokes, emptyList(), emptyList())
    }

    fun persistDrawing(
        filePath: String?,
        strokes: List<StrokePath>,
        rasterFills: List<RasterFillPath>,
        annotations: List<AnnotationPath> = emptyList()
    ) {
        if (filePath.isNullOrEmpty()) {
            DrawingDiagnosticLog.warn("STORE", "persistDrawing skipped empty filePath strokes=${strokes.size} rasterFills=${rasterFills.size} annotations=${annotations.size}")
            return
        }
        DrawingDiagnosticLog.info("STORE", "persistDrawing file=$filePath strokes=${strokes.size} rasterFills=${rasterFills.size} annotations=${annotations.size} first=${DrawingDiagnosticLog.strokeSummary(strokes.firstOrNull())}")

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
                        foldHiddenHeightAbove = point.foldHiddenHeightAbove
                    )
                }.toMutableList(),
                filled = stroke.filled,
                kind = stroke.kind?.name,
                objectGroupId = stroke.objectGroupId,
                rigidObjectAnchor = stroke.rigidObjectAnchor
            )
        }
        val savedRasterFills = rasterFills.map { fill ->
            SavedRasterFill(
                id = fill.id,
                color = fill.color.rgb,
                anchor = fill.anchor.toSavedPoint(),
                width = fill.width,
                height = fill.height,
                pngBase64 = fill.pngBase64,
                objectGroupId = fill.objectGroupId
            )
        }
        val savedAnnotations = annotations.map { annotation ->
            SavedAnnotation(
                id = annotation.id,
                text = annotation.text,
                color = annotation.color.rgb,
                anchor = annotation.anchor.toSavedPoint(),
                width = annotation.width,
                height = annotation.height,
                kind = annotation.kind.name,
                style = annotation.style.name,
                objectGroupId = annotation.objectGroupId
            )
        }
        DrawingDiagnosticLog.info("STORE", "persistDrawing savedPayload=${saved.size} rasterPayload=${savedRasterFills.size} annotationPayload=${savedAnnotations.size} pointCounts=${saved.joinToString(",") { it.points.size.toString() }}")
        stateService.setDrawing(filePath, saved, savedRasterFills, savedAnnotations)
    }

    private fun SavedPoint.toAnchor(): AnchorPoint {
        return AnchorPoint(
            line = line,
            column = column,
            dx = dx,
            dy = dy,
            offset = offset,
            outsideCode = outsideCode,
            afterLineEndPx = afterLineEndPx,
            foldHiddenHeightAbove = foldHiddenHeightAbove
        )
    }

    private fun AnchorPoint.toSavedPoint(): SavedPoint {
        return SavedPoint(
            line = line,
            column = column,
            dx = dx,
            dy = dy,
            offset = offset,
            outsideCode = outsideCode,
            afterLineEndPx = afterLineEndPx,
            foldHiddenHeightAbove = foldHiddenHeightAbove
        )
    }
}
