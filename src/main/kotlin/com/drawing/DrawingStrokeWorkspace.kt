package com.drawing

import com.intellij.openapi.editor.Document
import java.awt.Rectangle

private data class ObjectBoundsCacheKey(
    val id: Long,
    val anchorLine: Int,
    val anchorColumn: Int,
    val anchorDx: Int,
    val anchorDy: Int,
    val anchorOffset: Int,
    val width: Int,
    val height: Int,
    val foldLayoutSignature: Int
)

class DrawingStrokeWorkspace(
    private val currentDocument: () -> Document?,
    private val strokeStore: DrawingStrokeStore,
    private val coordinateMapper: DrawingCoordinateMapper,
    private val strokeRenderer: DrawingStrokeRenderer
) {
    private val rasterFillImageCache = RasterFillImageCache()
    private val annotationImageCache = AnnotationImageCache()
    private val rasterFillBoundsCache = mutableMapOf<Long, Pair<ObjectBoundsCacheKey, Rectangle>>()
    private val annotationBoundsCache = mutableMapOf<Long, Pair<ObjectBoundsCacheKey, Rectangle>>()
    private var collapsedFoldMarkersCache: Pair<CollapsedFoldMarkersCacheKey, List<CollapsedFoldRegionSnapshot>>? = null

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
        invalidateStrokeCaches()
    }

    fun setRasterFills(document: Document, rasterFills: MutableList<RasterFillPath>) {
        strokeStore.setRasterFills(document, rasterFills)
        rasterFillImageCache.clear()
        rasterFillBoundsCache.clear()
    }

    fun setAnnotations(document: Document, annotations: MutableList<AnnotationPath>) {
        strokeStore.setAnnotations(document, annotations)
        annotationImageCache.clear()
        annotationBoundsCache.clear()
    }

    fun clearDocument(document: Document) {
        strokeStore.clearDocument(document)
        rasterFillImageCache.clear()
        annotationImageCache.clear()
        rasterFillBoundsCache.clear()
        annotationBoundsCache.clear()
    }

    fun addStroke(stroke: StrokePath) {
        currentStrokes().add(stroke)
        updateStrokeBounds(stroke)
        invalidateStrokeGeometry(stroke)
        invalidateFoldMarkerCache()
    }

    fun addRasterFill(fill: RasterFillPath) {
        currentRasterFills().add(fill)
        invalidateRasterFill(fill.id)
    }

    fun addAnnotation(annotation: AnnotationPath) {
        currentAnnotations().add(annotation)
        invalidateAnnotation(annotation.id)
    }

    fun strokeById(id: Long): StrokePath? {
        return currentStrokes().firstOrNull { it.id == id }
    }

    fun rasterFillById(id: Long): RasterFillPath? {
        return currentRasterFills().firstOrNull { it.id == id }
    }

    fun annotationById(id: Long): AnnotationPath? {
        return currentAnnotations().firstOrNull { it.id == id }
    }

    fun rasterFillContentBounds(fill: RasterFillPath): Rectangle? {
        if (fill.width <= 0 || fill.height <= 0) return null
        val key = boundsCacheKey(fill.id, fill.anchor, fill.width, fill.height)
        rasterFillBoundsCache[fill.id]?.let { (cachedKey, cachedBounds) ->
            if (cachedKey == key) {
                DrawingPerformanceDiagnostics.recordBoundsCacheHit()
                return Rectangle(cachedBounds)
            }
        }
        DrawingPerformanceDiagnostics.recordBoundsCacheMiss()
        val topLeft = coordinateMapper.toContentPoint(fill.anchor.copy()) ?: return null
        val bounds = Rectangle(topLeft.x, topLeft.y, fill.width, fill.height)
        rasterFillBoundsCache[fill.id] = key to Rectangle(bounds)
        return bounds
    }

    fun rasterFillImage(fill: RasterFillPath) = rasterFillImageCache.get(fill)

    fun annotationContentBounds(annotation: AnnotationPath): Rectangle? {
        if (annotation.width <= 0 || annotation.height <= 0) return null
        val key = boundsCacheKey(annotation.id, annotation.anchor, annotation.width, annotation.height)
        annotationBoundsCache[annotation.id]?.let { (cachedKey, cachedBounds) ->
            if (cachedKey == key) {
                DrawingPerformanceDiagnostics.recordBoundsCacheHit()
                return Rectangle(cachedBounds)
            }
        }
        DrawingPerformanceDiagnostics.recordBoundsCacheMiss()
        val topLeft = coordinateMapper.toContentPoint(annotation.anchor.copy()) ?: return null
        val bounds = Rectangle(topLeft.x, topLeft.y, annotation.width, annotation.height)
        annotationBoundsCache[annotation.id] = key to Rectangle(bounds)
        return bounds
    }

    fun annotationImage(annotation: AnnotationPath) = annotationImageCache.getOrRender(annotation)

    fun invalidateAnnotation(annotationId: Long) {
        annotationImageCache.invalidate(annotationId)
        invalidateAnnotationBounds(annotationId)
    }

    fun invalidateRasterFill(fillId: Long) {
        rasterFillImageCache.invalidate(fillId)
        invalidateRasterFillBounds(fillId)
    }

    fun invalidateRasterFillBounds(fillId: Long) {
        rasterFillBoundsCache.remove(fillId)
    }

    fun invalidateAnnotationBounds(annotationId: Long) {
        annotationBoundsCache.remove(annotationId)
    }

    fun clearObjectBoundsCaches() {
        rasterFillBoundsCache.clear()
        annotationBoundsCache.clear()
    }

    fun collapsedFoldMarkersFor(
        visibleStrokes: List<StrokePath>,
        collapsedRegions: List<CollapsedFoldRegionSnapshot>
    ): List<CollapsedFoldRegionSnapshot> {
        if (collapsedRegions.isEmpty() || visibleStrokes.isEmpty()) return emptyList()
        val key = CollapsedFoldMarkersCacheKey(
            foldLayoutSignature = coordinateMapper.currentFoldLayoutSignature(),
            strokeSignature = visibleStrokes.fold(1) { acc, stroke -> 31 * acc + stroke.id.hashCode() },
            collapsedRegionSignature = collapsedRegions.fold(1) { acc, region ->
                31 * acc + region.startOffset
                    .let { 31 * it + region.endOffset }
                    .let { 31 * it + region.placeholderPoint.x }
                    .let { 31 * it + region.placeholderPoint.y }
                    .let { 31 * it + region.placeholderWidth }
            }
        )
        collapsedFoldMarkersCache?.let { (cachedKey, cachedMarkers) ->
            if (cachedKey == key) return cachedMarkers
        }
        val markers = DrawingViewportTools.collapsedFoldMarkersFor(visibleStrokes, collapsedRegions)
        collapsedFoldMarkersCache = key to markers
        return markers
    }

    fun visibleStrokes(
        visibleLineRange: IntRange,
        visibleContentClip: Rectangle,
        collapsedFoldRegions: List<CollapsedFoldRegionSnapshot>
    ): List<StrokePath> {
        val visible = mutableListOf<StrokePath>()
        val boundsMap = currentStrokeBounds()
        for (stroke in currentStrokes()) {
            if (DrawingViewportTools.isStrokeHiddenByCollapsedFold(stroke, collapsedFoldRegions)) continue
            val lineBounds = boundsMap[stroke.id]
                ?: DrawingViewportTools.computeStrokeLineBounds(stroke)?.also { boundsMap[stroke.id] = it }
                ?: continue
            if (lineBounds.maxLine < visibleLineRange.first || lineBounds.minLine > visibleLineRange.last) continue
            val geometry = getOrBuildStrokeGeometryContent(stroke) ?: continue
            if (geometry.bounds.intersects(visibleContentClip)) {
                visible += stroke
            }
        }
        return visible
    }

    fun visibleRasterFills(
        visibleContentClip: Rectangle,
        collapsedFoldRegions: List<CollapsedFoldRegionSnapshot>
    ): List<RasterFillPath> {
        return currentRasterFills().filter { fill ->
            !DrawingViewportTools.isRasterFillHiddenByCollapsedFold(fill, collapsedFoldRegions) &&
                rasterFillContentBounds(fill)?.intersects(visibleContentClip) == true
        }
    }

    fun visibleAnnotations(
        visibleContentClip: Rectangle,
        collapsedFoldRegions: List<CollapsedFoldRegionSnapshot>
    ): List<AnnotationPath> {
        return currentAnnotations().filter { annotation ->
            !DrawingViewportTools.isAnnotationHiddenByCollapsedFold(annotation, collapsedFoldRegions) &&
                annotationContentBounds(annotation)?.intersects(visibleContentClip) == true
        }
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
        invalidateFoldMarkerCache()
    }

    fun updateStrokeBounds(stroke: StrokePath) {
        val bounds = DrawingViewportTools.computeStrokeLineBounds(stroke) ?: return
        currentStrokeBounds()[stroke.id] = bounds
        invalidateFoldMarkerCache()
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
        invalidateFoldMarkerCache()
    }

    fun resetStrokeGeometryCache(document: Document) {
        strokeStore.currentStrokeGeometries(document).clear()
        clearObjectBoundsCaches()
        invalidateFoldMarkerCache()
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
            DrawingPerformanceDiagnostics.recordGeometryCacheHit()
            return cached
        }

        DrawingPerformanceDiagnostics.recordGeometryCacheMiss()
        val built = buildStrokeGeometryContent(stroke) ?: return null
        cache[stroke.id] = built
        return built
    }

    private fun invalidateStrokeCaches() {
        currentStrokeBounds().clear()
        currentStrokeGeometries().clear()
        invalidateFoldMarkerCache()
    }

    private fun invalidateFoldMarkerCache() {
        collapsedFoldMarkersCache = null
    }

    private fun boundsCacheKey(id: Long, anchor: AnchorPoint, width: Int, height: Int): ObjectBoundsCacheKey {
        return ObjectBoundsCacheKey(
            id = id,
            anchorLine = anchor.line,
            anchorColumn = anchor.column,
            anchorDx = anchor.dx,
            anchorDy = anchor.dy,
            anchorOffset = anchor.offset,
            width = width,
            height = height,
            foldLayoutSignature = coordinateMapper.currentFoldLayoutSignature()
        )
    }

    private data class CollapsedFoldMarkersCacheKey(
        val foldLayoutSignature: Int,
        val strokeSignature: Int,
        val collapsedRegionSignature: Int
    )
}

