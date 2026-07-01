package com.drawing

import com.intellij.openapi.editor.Document
import java.awt.Rectangle

class DrawingStrokeWorkspace(
    private val currentDocument: () -> Document?,
    private val strokeStore: DrawingStrokeStore,
    private val coordinateMapper: DrawingCoordinateMapper,
    private val strokeRenderer: DrawingStrokeRenderer
) {
    private val rasterFillImageCache = RasterFillImageCache()
    private val annotationImageCache = AnnotationImageCache()

    fun currentStrokes(): MutableList<StrokePath> {
        return strokeStore.currentStrokes(currentDocument())
    }

    fun currentRasterFills(): MutableList<RasterFillPath> {
        return strokeStore.currentRasterFills(currentDocument())
    }

    fun currentAnnotations(): MutableList<AnnotationPath> {
        return strokeStore.currentAnnotations(currentDocument())
    }

    fun currentStrokeBounds(): MutableMap<Long, StrokeLineBounds> {
        return strokeStore.currentStrokeBounds(currentDocument())
    }

    fun currentStrokeGeometries(): MutableMap<Long, StrokeGeometryContent> {
        return strokeStore.currentStrokeGeometries(currentDocument())
    }

    fun setStrokes(document: Document, strokes: MutableList<StrokePath>) {
        strokeStore.setStrokes(document, strokes)
    }

    fun setRasterFills(document: Document, rasterFills: MutableList<RasterFillPath>) {
        strokeStore.setRasterFills(document, rasterFills)
    }

    fun setAnnotations(document: Document, annotations: MutableList<AnnotationPath>) {
        strokeStore.setAnnotations(document, annotations)
        annotationImageCache.clear()
    }

    fun clearDocument(document: Document) {
        strokeStore.clearDocument(document)
        rasterFillImageCache.clear()
        annotationImageCache.clear()
    }

    fun addStroke(stroke: StrokePath) {
        currentStrokes().add(stroke)
        updateStrokeBounds(stroke)
        invalidateStrokeGeometry(stroke)
    }

    fun addRasterFill(fill: RasterFillPath) {
        currentRasterFills().add(fill)
    }

    fun addAnnotation(annotation: AnnotationPath) {
        currentAnnotations().add(annotation)
        annotationImageCache.invalidate(annotation.id)
    }

    fun rasterFillContentBounds(fill: RasterFillPath): Rectangle? {
        if (fill.width <= 0 || fill.height <= 0) return null
        val topLeft = coordinateMapper.toContentPoint(fill.anchor.copy()) ?: return null
        return Rectangle(topLeft.x, topLeft.y, fill.width, fill.height)
    }

    fun rasterFillImage(fill: RasterFillPath) = rasterFillImageCache.get(fill)

    fun annotationContentBounds(annotation: AnnotationPath): Rectangle? {
        if (annotation.width <= 0 || annotation.height <= 0) return null
        val topLeft = coordinateMapper.toContentPoint(annotation.anchor.copy()) ?: return null
        return Rectangle(topLeft.x, topLeft.y, annotation.width, annotation.height)
    }

    fun annotationImage(annotation: AnnotationPath) = annotationImageCache.getOrRender(annotation)

    fun invalidateAnnotation(annotationId: Long) {
        annotationImageCache.invalidate(annotationId)
    }

    fun rebuildStrokeBounds(document: Document) {
        val rebuilt = mutableMapOf<Long, StrokeLineBounds>()
        for (stroke in strokeStore.currentStrokes(document)) {
            DrawingViewportTools.computeStrokeLineBounds(stroke)?.let { rebuilt[stroke.id] = it }
        }
        strokeStore.currentStrokeBounds(document).apply {
            clear()
            putAll(rebuilt)
        }
    }

    fun updateStrokeBounds(stroke: StrokePath) {
        val bounds = DrawingViewportTools.computeStrokeLineBounds(stroke) ?: return
        currentStrokeBounds()[stroke.id] = bounds
    }

    fun expandStrokeBoundsWithAnchor(stroke: StrokePath, anchor: AnchorPoint) {
        val boundsMap = currentStrokeBounds()
        val existing = boundsMap[stroke.id]
        boundsMap[stroke.id] = if (existing == null) {
            StrokeLineBounds(anchor.line, anchor.line)
        } else {
            StrokeLineBounds(
                minOf(existing.minLine, anchor.line),
                maxOf(existing.maxLine, anchor.line)
            )
        }
    }

    fun invalidateStrokeGeometry(stroke: StrokePath) {
        currentStrokeGeometries().remove(stroke.id)
    }

    fun resetStrokeGeometryCache(document: Document) {
        strokeStore.currentStrokeGeometries(document).clear()
    }

    fun buildStrokeGeometryContent(stroke: StrokePath): StrokeGeometryContent? {
        coordinateMapper.ensureStrokeFoldLayoutBaseline(stroke)
        return strokeRenderer.buildStrokeGeometryContent(
            stroke = stroke,
            toContentPoint = { anchor -> coordinateMapper.toContentPoint(stroke, anchor) }
        )?.copy(foldLayoutSignature = coordinateMapper.currentFoldLayoutSignature())
    }

    fun getOrBuildStrokeGeometryContent(stroke: StrokePath): StrokeGeometryContent? {
        val cache = currentStrokeGeometries()
        val currentFoldLayoutSignature = coordinateMapper.currentFoldLayoutSignature()
        val cached = cache[stroke.id]
        if (cached != null && cached.foldLayoutSignature == currentFoldLayoutSignature) {
            return cached
        }

        val built = buildStrokeGeometryContent(stroke) ?: return null
        cache[stroke.id] = built
        return built
    }
}

