package com.drawing

import com.intellij.openapi.editor.Editor
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.RoundRectangle2D
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.math.ceil

class DrawingCanvasPainter(
    private val canvas: JPanel,
    private val editorProvider: () -> Editor?,
    private val currentStrokesProvider: () -> List<StrokePath>,
    private val currentRasterFillsProvider: () -> List<RasterFillPath> = { emptyList() },
    private val currentAnnotationsProvider: () -> List<AnnotationPath> = { emptyList() },
    private val shapePreviewProvider: () -> StrokePath?,
    private val collapsedFoldRegionsProvider: () -> List<CollapsedFoldRegionSnapshot> = { emptyList() },
    private val selectedStrokeIdsProvider: () -> Set<Long> = { emptySet() },
    private val selectedRasterFillIdsProvider: () -> Set<Long> = { emptySet() },
    private val selectedAnnotationIdsProvider: () -> Set<Long> = { emptySet() },
    private val selectionMarqueeProvider: () -> Rectangle? = { null },
    private val gridEnabledProvider: () -> Boolean,
    private val currentToolProvider: () -> DrawingToolMode,
    private val toolPreviewPointProvider: () -> Point?,
    private val eraseRadiusProvider: () -> Double,
    private val strokeRenderer: DrawingStrokeRenderer,
    private val strokeWorkspace: DrawingStrokeWorkspace
) {
    fun paint(graphics: Graphics) {
        val paintStartedAt = System.nanoTime()
        val stats = PaintPerformanceStats()
        val currentEditor = editorProvider() ?: return
        val g = graphics as? Graphics2D ?: return
        DrawingPerformanceDiagnostics.beginPaint(stats)

        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        val clip = g.clipBounds ?: Rectangle(0, 0, canvas.width, canvas.height)
        val lineHeight = currentEditor.lineHeight.takeIf { it > 0 } ?: 16

        if (gridEnabledProvider()) {
            strokeRenderer.paintGridWithEdge(
                g = g,
                cellSize = lineHeight,
                clip = clip,
                width = canvas.width,
                height = canvas.height
            )
        }

        val visibleLineRange = DrawingViewportTools.resolveVisibleLineRange(canvas, currentEditor, clip)

        val contentOrigin = SwingUtilities.convertPoint(currentEditor.contentComponent, Point(0, 0), canvas)
        val contentClip = Rectangle(
            clip.x - contentOrigin.x,
            clip.y - contentOrigin.y,
            clip.width,
            clip.height
        )
        val collapsedFoldRegions = collapsedFoldRegionsProvider()
        val gContent = g.create() as Graphics2D
        gContent.translate(contentOrigin.x.toDouble(), contentOrigin.y.toDouble())

        try {
            stats.strokesInspected = currentStrokesProvider().size
            stats.rasterFillsInspected = currentRasterFillsProvider().size
            stats.annotationsInspected = currentAnnotationsProvider().size

            stats.rasterFillsPainted = paintRasterFills(gContent, contentClip, collapsedFoldRegions)

            val visibleStrokes = strokeWorkspace.visibleStrokes(
                visibleLineRange = visibleLineRange.first..visibleLineRange.second,
                visibleContentClip = contentClip,
                collapsedFoldRegions = collapsedFoldRegions
            )
            stats.strokesPainted = visibleStrokes.size
            for (stroke in visibleStrokes) {
                paintStroke(
                    g = gContent,
                    stroke = stroke,
                    preview = false,
                    visibleContentClip = contentClip
                )
            }

            stats.annotationsPainted = paintAnnotations(gContent, contentClip, collapsedFoldRegions)
            paintSelectionHighlight(gContent, contentClip)
            paintCollapsedFoldMarkers(gContent, collapsedFoldRegions, contentClip)

            shapePreviewProvider()?.let { preview ->
                if (DrawingViewportTools.isStrokeHiddenByCollapsedFold(preview, collapsedFoldRegions)) {
                    return@let
                }
                val previewBounds = DrawingViewportTools.computeStrokeLineBounds(preview)
                val previewGeometry = strokeWorkspace.buildStrokeGeometryContent(preview)
                if (previewBounds != null &&
                    previewGeometry != null &&
                    previewBounds.maxLine >= visibleLineRange.first &&
                    previewBounds.minLine <= visibleLineRange.second &&
                    previewGeometry.bounds.intersects(contentClip)
                ) {
                    strokeRenderer.paintStroke(
                        g = gContent,
                        stroke = preview,
                        geometry = previewGeometry,
                        preview = true,
                        visibleContentClip = contentClip
                    )
                    paintShapePreviewHandles(gContent, preview, previewGeometry, contentClip)
                    paintShapePreviewBadge(gContent, preview, previewGeometry, contentClip)
                }
            }
        } finally {
            gContent.dispose()
        }

        paintSelectionMarquee(g)
        paintToolPreview(g)
        } finally {
            stats.paintMs = (System.nanoTime() - paintStartedAt) / 1_000_000L
            DrawingPerformanceDiagnostics.logSlowPaint(stats)
            DrawingPerformanceDiagnostics.endPaint()
        }
    }


    fun paintEditorContent(graphics: Graphics) {
        val paintStartedAt = System.nanoTime()
        val stats = PaintPerformanceStats()
        val currentEditor = editorProvider() ?: return
        val g = graphics as? Graphics2D ?: return
        DrawingPerformanceDiagnostics.beginPaint(stats)

        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        val clip = g.clipBounds ?: currentEditor.scrollingModel.visibleArea
        val lineHeight = currentEditor.lineHeight.takeIf { it > 0 } ?: 16

        if (gridEnabledProvider()) {
            strokeRenderer.paintGridWithEdge(
                g = g,
                cellSize = lineHeight,
                clip = clip,
                width = currentEditor.contentComponent.width.coerceAtLeast(clip.x + clip.width),
                height = currentEditor.contentComponent.height.coerceAtLeast(clip.y + clip.height)
            )
        }

        val visibleLineRange = DrawingViewportTools.resolveVisibleLineRangeInContent(currentEditor, clip)
        val collapsedFoldRegions = collapsedFoldRegionsProvider()
        stats.strokesInspected = currentStrokesProvider().size
        stats.rasterFillsInspected = currentRasterFillsProvider().size
        stats.annotationsInspected = currentAnnotationsProvider().size
        stats.rasterFillsPainted = paintRasterFills(g, clip, collapsedFoldRegions)

        val visibleStrokes = strokeWorkspace.visibleStrokes(
            visibleLineRange = visibleLineRange.first..visibleLineRange.second,
            visibleContentClip = clip,
            collapsedFoldRegions = collapsedFoldRegions
        )
        stats.strokesPainted = visibleStrokes.size
        for (stroke in visibleStrokes) {
            paintStroke(
                g = g,
                stroke = stroke,
                preview = false,
                visibleContentClip = clip
            )
        }

        stats.annotationsPainted = paintAnnotations(g, clip, collapsedFoldRegions)
        paintSelectionHighlight(g, clip)
        paintCollapsedFoldMarkers(g, collapsedFoldRegions, clip)

        shapePreviewProvider()?.let { preview ->
            if (DrawingViewportTools.isStrokeHiddenByCollapsedFold(preview, collapsedFoldRegions)) {
                return@let
            }
            val previewBounds = DrawingViewportTools.computeStrokeLineBounds(preview)
            val previewGeometry = strokeWorkspace.buildStrokeGeometryContent(preview)
            if (previewBounds != null &&
                previewGeometry != null &&
                previewBounds.maxLine >= visibleLineRange.first &&
                previewBounds.minLine <= visibleLineRange.second &&
                previewGeometry.bounds.intersects(clip)
            ) {
                strokeRenderer.paintStroke(
                    g = g,
                    stroke = preview,
                    geometry = previewGeometry,
                    preview = true,
                    visibleContentClip = clip
                )
                paintShapePreviewHandles(g, preview, previewGeometry, clip)
                paintShapePreviewBadge(g, preview, previewGeometry, clip)
            }
        }

        paintSelectionMarqueeInEditorContent(g, currentEditor)
        paintToolPreviewInEditorContent(g, currentEditor)
        } finally {
            stats.paintMs = (System.nanoTime() - paintStartedAt) / 1_000_000L
            DrawingPerformanceDiagnostics.logSlowPaint(stats)
            DrawingPerformanceDiagnostics.endPaint()
        }
    }

    private fun paintSelectionMarquee(g: Graphics2D) {
        val bounds = selectionMarqueeProvider() ?: return
        if (bounds.width <= 0 || bounds.height <= 0) return

        val gSelection = g.create() as Graphics2D
        try {
            gSelection.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            gSelection.color = Color(40, 160, 220, 35)
            gSelection.fill(bounds)
            gSelection.stroke = BasicStroke(
                1.4f,
                BasicStroke.CAP_ROUND,
                BasicStroke.JOIN_ROUND,
                10f,
                floatArrayOf(7f, 5f),
                0f
            )
            gSelection.color = Color(115, 220, 255, 230)
            gSelection.draw(bounds)
        } finally {
            gSelection.dispose()
        }
    }

    private fun paintSelectionMarqueeInEditorContent(g: Graphics2D, editor: Editor) {
        val canvasBounds = selectionMarqueeProvider() ?: return
        val contentBounds = SwingUtilities.convertRectangle(canvas, canvasBounds, editor.contentComponent)
        val gSelection = g.create() as Graphics2D
        try {
            gSelection.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            gSelection.color = Color(40, 160, 220, 35)
            gSelection.fill(contentBounds)
            gSelection.stroke = BasicStroke(
                1.4f,
                BasicStroke.CAP_ROUND,
                BasicStroke.JOIN_ROUND,
                10f,
                floatArrayOf(7f, 5f),
                0f
            )
            gSelection.color = Color(115, 220, 255, 230)
            gSelection.draw(contentBounds)
        } finally {
            gSelection.dispose()
        }
    }

    private fun paintRasterFills(
        g: Graphics2D,
        visibleContentClip: Rectangle,
        collapsedRegions: List<CollapsedFoldRegionSnapshot>
    ): Int {
        var painted = 0
        for (fill in strokeWorkspace.visibleRasterFills(visibleContentClip, collapsedRegions)) {
            val bounds = strokeWorkspace.rasterFillContentBounds(fill) ?: continue
            val image = runCatching { strokeWorkspace.rasterFillImage(fill) }.getOrNull() ?: continue
            g.drawImage(image, bounds.x, bounds.y, null)
            painted += 1
        }
        return painted
    }

    private fun paintAnnotations(
        g: Graphics2D,
        visibleContentClip: Rectangle,
        collapsedRegions: List<CollapsedFoldRegionSnapshot>
    ): Int {
        var painted = 0
        for (annotation in strokeWorkspace.visibleAnnotations(visibleContentClip, collapsedRegions)) {
            val bounds = strokeWorkspace.annotationContentBounds(annotation) ?: continue
            val image = runCatching { strokeWorkspace.annotationImage(annotation) }.getOrNull() ?: continue
            g.drawImage(image, bounds.x, bounds.y, null)
            painted += 1
        }
        return painted
    }

    private fun paintCollapsedFoldMarkers(
        g: Graphics2D,
        collapsedRegions: List<CollapsedFoldRegionSnapshot>,
        visibleContentClip: Rectangle
    ) {
        if (collapsedRegions.isEmpty()) return

        val hiddenRegions = strokeWorkspace.collapsedFoldMarkersFor(currentStrokesProvider(), collapsedRegions)
        if (hiddenRegions.isEmpty()) return

        // FIX: don't call g.create() here — g is already the correctly translated
        // gContent context passed from paint(). Creating a child context via g.create()
        // drops the translation set on gContent, placing the star in canvas coords
        // instead of content coords, causing visible misalignment.
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val savedHint = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING) // optional: restore after

        for (region in hiddenRegions) {
            val point = region.placeholderPoint
            val starX = point.x + region.placeholderWidth + 32
            val starY = point.y + 10
            if (!visibleContentClip.contains(starX, starY)) continue
            drawStar(g, starX, starY, 6, Color(255, 215, 80, 230))
        }
    }

    private fun drawStar(g: Graphics2D, centerX: Int, centerY: Int, radius: Int, color: Color) {
        val outer = radius.toDouble()
        val inner = outer * 0.45
        val points = ArrayList<Point>(10)
        for (i in 0 until 10) {
            val angle = -Math.PI / 2 + i * Math.PI / 5
            val r = if (i % 2 == 0) outer else inner
            points += Point(
                (centerX + kotlin.math.cos(angle) * r).toInt(),
                (centerY + kotlin.math.sin(angle) * r).toInt()
            )
        }
        val polygon = java.awt.Polygon()
        points.forEach { polygon.addPoint(it.x, it.y) }
        g.color = color
        g.fillPolygon(polygon)
        g.color = Color(20, 20, 20, 160)
        g.drawPolygon(polygon)
    }

    private fun paintToolPreviewInEditorContent(g: Graphics2D, editor: Editor) {
        if (currentToolProvider() != DrawingToolMode.ERASE) return
        val canvasPoint = toolPreviewPointProvider() ?: return
        val contentPoint = SwingUtilities.convertPoint(canvas, canvasPoint, editor.contentComponent)
        val radius = ceil(eraseRadiusProvider()).toInt().coerceAtLeast(1)

        val gPreview = g.create() as Graphics2D
        try {
            gPreview.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

            val diameter = radius * 2
            val circle = Ellipse2D.Double(
                (contentPoint.x - radius).toDouble(),
                (contentPoint.y - radius).toDouble(),
                diameter.toDouble(),
                diameter.toDouble()
            )

            gPreview.stroke = BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            gPreview.color = Color(255, 255, 255, 190)
            gPreview.draw(circle)

            gPreview.stroke = BasicStroke(1f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            gPreview.color = Color(30, 30, 30, 180)
            gPreview.draw(circle)

            val centerTick = 3
            gPreview.drawLine(contentPoint.x - centerTick, contentPoint.y, contentPoint.x + centerTick, contentPoint.y)
            gPreview.drawLine(contentPoint.x, contentPoint.y - centerTick, contentPoint.x, contentPoint.y + centerTick)
        } finally {
            gPreview.dispose()
        }
    }

    private fun paintShapePreviewHandles(
        g: Graphics2D,
        preview: StrokePath,
        geometry: StrokeGeometryContent,
        visibleContentClip: Rectangle
    ) {
        val kind = preview.kind ?: return
        if (kind == ShapeKind.LINE) return

        val bounds = geometry.bounds
        if (bounds.width <= 0 || bounds.height <= 0 || !bounds.intersects(visibleContentClip)) return

        val handleSize = 7
        val half = handleSize / 2
        val points = listOf(
            Point(bounds.x, bounds.y),
            Point(bounds.x + bounds.width, bounds.y),
            Point(bounds.x + bounds.width, bounds.y + bounds.height),
            Point(bounds.x, bounds.y + bounds.height)
        )

        val gHandles = g.create() as Graphics2D
        try {
            gHandles.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

            gHandles.stroke = BasicStroke(1f)
            for (point in points) {
                val x = point.x - half
                val y = point.y - half
                val dot = Ellipse2D.Double(x.toDouble(), y.toDouble(), handleSize.toDouble(), handleSize.toDouble())

                gHandles.color = Color(25, 25, 25, 170)
                gHandles.fill(dot)
                gHandles.color = Color(255, 255, 255, 230)
                gHandles.draw(dot)
            }
        } finally {
            gHandles.dispose()
        }
    }

    private fun paintSelectionHighlight(g: Graphics2D, visibleContentClip: Rectangle) {
        val selectedIds = selectedStrokeIdsProvider()
        val selectedRasterFillIds = selectedRasterFillIdsProvider()
        val selectedAnnotationIds = selectedAnnotationIdsProvider()
        if (selectedIds.isEmpty() && selectedRasterFillIds.isEmpty() && selectedAnnotationIds.isEmpty()) return

        val strokeBounds = selectedIds
            .asSequence()
            .mapNotNull(strokeWorkspace::strokeById)
            .mapNotNull { strokeWorkspace.getOrBuildStrokeGeometryContent(it)?.bounds }
            .fold(null as Rectangle?) { union, bounds ->
                if (union == null) Rectangle(bounds) else union.apply { add(bounds) }
            }
        val rasterBounds = selectedRasterFillIds
            .asSequence()
            .mapNotNull(strokeWorkspace::rasterFillById)
            .mapNotNull { strokeWorkspace.rasterFillContentBounds(it) }
            .fold(null as Rectangle?) { union, bounds ->
                if (union == null) Rectangle(bounds) else union.apply { add(bounds) }
            }
        val annotationBounds = selectedAnnotationIds
            .asSequence()
            .mapNotNull(strokeWorkspace::annotationById)
            .mapNotNull { strokeWorkspace.annotationContentBounds(it) }
            .fold(null as Rectangle?) { union, bounds ->
                if (union == null) Rectangle(bounds) else union.apply { add(bounds) }
            }
        val selectedBounds = DrawingViewportTools.unionRectangles(
            DrawingViewportTools.unionRectangles(strokeBounds, rasterBounds),
            annotationBounds
        ) ?: return

        selectedBounds.grow(8, 8)
        if (!selectedBounds.intersects(visibleContentClip)) return

        val highlight = RoundRectangle2D.Double(
            selectedBounds.x.toDouble(),
            selectedBounds.y.toDouble(),
            selectedBounds.width.toDouble(),
            selectedBounds.height.toDouble(),
            12.0,
            12.0
        )

        val gSelection = g.create() as Graphics2D
        try {
            gSelection.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            gSelection.color = Color(40, 160, 220, 35)
            gSelection.fill(highlight)
            gSelection.stroke = BasicStroke(
                1.6f,
                BasicStroke.CAP_ROUND,
                BasicStroke.JOIN_ROUND,
                10f,
                floatArrayOf(8f, 5f),
                0f
            )
            gSelection.color = Color(115, 220, 255, 220)
            gSelection.draw(highlight)
        } finally {
            gSelection.dispose()
        }
    }


    private fun paintShapePreviewBadge(
        g: Graphics2D,
        preview: StrokePath,
        geometry: StrokeGeometryContent,
        visibleContentClip: Rectangle
    ) {
        val kind = preview.kind ?: return
        val bounds = geometry.bounds
        if (bounds.width <= 0 || bounds.height <= 0) return

        val label = buildShapePreviewLabel(kind, bounds)
        val gBadge = g.create() as Graphics2D
        try {
            gBadge.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            gBadge.font = Font("Dialog", Font.BOLD, 11)

            val metrics = gBadge.fontMetrics
            val horizontalPadding = 8
            val badgeWidth = metrics.stringWidth(label) + horizontalPadding * 2
            val badgeHeight = metrics.height + 6

            val minX = visibleContentClip.x + 6
            val maxX = visibleContentClip.x + visibleContentClip.width - badgeWidth - 6
            val preferredX = bounds.x
            val badgeX = clamp(preferredX, minX, maxX.coerceAtLeast(minX))

            val aboveY = bounds.y - badgeHeight - 6
            val belowY = bounds.y + 8
            val badgeY = if (aboveY >= visibleContentClip.y + 6) {
                aboveY
            } else {
                belowY.coerceAtMost(visibleContentClip.y + visibleContentClip.height - badgeHeight - 6)
            }.coerceAtLeast(visibleContentClip.y + 6)

            gBadge.color = Color(25, 25, 25, 220)
            gBadge.fillRoundRect(badgeX, badgeY, badgeWidth, badgeHeight, 10, 10)

            gBadge.color = Color(150, 190, 255, 210)
            gBadge.stroke = BasicStroke(1f)
            gBadge.drawRoundRect(badgeX, badgeY, badgeWidth, badgeHeight, 10, 10)

            gBadge.color = Color(240, 240, 240, 235)
            gBadge.drawString(label, badgeX + horizontalPadding, badgeY + metrics.ascent + 3)
        } finally {
            gBadge.dispose()
        }
    }

    private fun buildShapePreviewLabel(kind: ShapeKind, bounds: Rectangle): String {
        val width = bounds.width.coerceAtLeast(1)
        val height = bounds.height.coerceAtLeast(1)
        return when (kind) {
            ShapeKind.LINE,
            ShapeKind.ARROW -> "${kind.displayName} - Shift snaps"

            else -> "${kind.displayName} - ${width}x${height}px"
        }
    }

    private fun clamp(value: Int, min: Int, max: Int): Int {
        return if (max < min) min else value.coerceIn(min, max)
    }

    private fun paintToolPreview(g: Graphics2D) {
        if (currentToolProvider() != DrawingToolMode.ERASE) return
        val point = toolPreviewPointProvider() ?: return
        val radius = ceil(eraseRadiusProvider()).toInt().coerceAtLeast(1)

        val gPreview = g.create() as Graphics2D
        try {
            gPreview.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

            val diameter = radius * 2
            val circle = Ellipse2D.Double(
                (point.x - radius).toDouble(),
                (point.y - radius).toDouble(),
                diameter.toDouble(),
                diameter.toDouble()
            )

            gPreview.stroke = BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            gPreview.color = Color(255, 255, 255, 190)
            gPreview.draw(circle)

            gPreview.stroke = BasicStroke(1f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            gPreview.color = Color(30, 30, 30, 180)
            gPreview.draw(circle)

            val centerTick = 3
            gPreview.drawLine(point.x - centerTick, point.y, point.x + centerTick, point.y)
            gPreview.drawLine(point.x, point.y - centerTick, point.x, point.y + centerTick)
        } finally {
            gPreview.dispose()
        }
    }

    private fun paintStroke(
        g: Graphics2D,
        stroke: StrokePath,
        preview: Boolean,
        visibleContentClip: Rectangle?
    ) {
        // Paint the stroke as one continuous geometry.
        //
        // Collapsed-fold markers are rebuilt from a cached/read-action-safe model.
        // The normal paint path must stay geometry-only and must not read foldingModel
        // during paintComponent().
        val geometry = if (preview) {
            strokeWorkspace.buildStrokeGeometryContent(stroke)
        } else {
            strokeWorkspace.getOrBuildStrokeGeometryContent(stroke)
        } ?: run {
            DrawingDiagnosticLog.warn("PAINTER", "paintStroke skipped nullGeometry preview=$preview ${DrawingDiagnosticLog.strokeSummary(stroke)}")
            return
        }

        strokeRenderer.paintStroke(
            g = g,
            stroke = stroke,
            geometry = geometry,
            preview = preview,
            visibleContentClip = visibleContentClip
        )
    }

}
