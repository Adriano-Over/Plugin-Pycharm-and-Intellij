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
    private val shapePreviewProvider: () -> StrokePath?,
    private val collapsedFoldRegionsProvider: () -> List<CollapsedFoldRegionSnapshot> = { emptyList() },
    private val selectedStrokeIdsProvider: () -> Set<Long> = { emptySet() },
    private val gridEnabledProvider: () -> Boolean,
    private val currentToolProvider: () -> DrawingToolMode,
    private val toolPreviewPointProvider: () -> Point?,
    private val eraseRadiusProvider: () -> Double,
    private val strokeRenderer: DrawingStrokeRenderer,
    private val strokeWorkspace: DrawingStrokeWorkspace
) {
    fun paint(graphics: Graphics) {
        val currentEditor = editorProvider() ?: return
        val g = graphics as? Graphics2D ?: return

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
        val boundsMap = strokeWorkspace.currentStrokeBounds()

        val contentOrigin = SwingUtilities.convertPoint(currentEditor.contentComponent, Point(0, 0), canvas)
        val contentClip = Rectangle(
            clip.x - contentOrigin.x,
            clip.y - contentOrigin.y,
            clip.width,
            clip.height
        )
        val collapsedFoldRegions = collapsedFoldRegionsProvider()
        val renderedSemanticAnnotationGroups = linkedSetOf<Long>()
        val gContent = g.create() as Graphics2D
        gContent.translate(contentOrigin.x.toDouble(), contentOrigin.y.toDouble())

        try {
            for (stroke in currentStrokesProvider()) {
                if (DrawingViewportTools.isStrokeHiddenByCollapsedFold(stroke, collapsedFoldRegions)) {
                    continue
                }
                val bounds = boundsMap[stroke.id]
                    ?: DrawingViewportTools.computeStrokeLineBounds(stroke)?.also { boundsMap[stroke.id] = it }
                if (bounds != null && bounds.maxLine >= visibleLineRange.first && bounds.minLine <= visibleLineRange.second) {
                    paintStroke(
                        g = gContent,
                        stroke = stroke,
                        preview = false,
                        visibleContentClip = contentClip,
                        renderedSemanticAnnotationGroups = renderedSemanticAnnotationGroups
                    )
                }
            }

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

        paintToolPreview(g)
    }


    fun paintEditorContent(graphics: Graphics) {
        val currentEditor = editorProvider() ?: return
        val g = graphics as? Graphics2D ?: return

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
        val boundsMap = strokeWorkspace.currentStrokeBounds()
        val renderedSemanticAnnotationGroups = linkedSetOf<Long>()
        for (stroke in currentStrokesProvider()) {
            val bounds = boundsMap[stroke.id]
                ?: DrawingViewportTools.computeStrokeLineBounds(stroke)?.also { boundsMap[stroke.id] = it }
                if (bounds != null && bounds.maxLine >= visibleLineRange.first && bounds.minLine <= visibleLineRange.second) {
                paintStroke(
                    g = g,
                    stroke = stroke,
                    preview = false,
                    visibleContentClip = clip,
                    renderedSemanticAnnotationGroups = renderedSemanticAnnotationGroups
                )
            }
        }

        paintSelectionHighlight(g, clip)
        paintCollapsedFoldMarkers(g, collapsedFoldRegionsProvider(), clip)

        shapePreviewProvider()?.let { preview ->
            if (DrawingViewportTools.isStrokeHiddenByCollapsedFold(preview, collapsedFoldRegionsProvider())) {
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

        paintToolPreviewInEditorContent(g, currentEditor)
    }

    private fun paintCollapsedFoldMarkers(
        g: Graphics2D,
        collapsedRegions: List<CollapsedFoldRegionSnapshot>,
        visibleContentClip: Rectangle
    ) {
        if (collapsedRegions.isEmpty()) return

        val hiddenRegions = DrawingViewportTools.collapsedFoldMarkersFor(currentStrokesProvider(), collapsedRegions)
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
        if (selectedIds.isEmpty()) return

        val selectedBounds = currentStrokesProvider()
            .asSequence()
            .filter { it.id in selectedIds }
            .mapNotNull { strokeWorkspace.getOrBuildStrokeGeometryContent(it)?.bounds }
            .fold(null as Rectangle?) { union, bounds ->
                if (union == null) Rectangle(bounds) else union.apply { add(bounds) }
            }
            ?: return

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
        visibleContentClip: Rectangle?,
        renderedSemanticAnnotationGroups: MutableSet<Long>
    ) {
        if (stroke.annotationText != null) {
            val semanticKey = annotationGroupKey(stroke)
            if (!renderedSemanticAnnotationGroups.add(semanticKey)) {
                return
            }
            val geometry = if (preview) {
                strokeWorkspace.buildStrokeGeometryContent(stroke)
            } else {
                strokeWorkspace.getOrBuildStrokeGeometryContent(stroke)
            } ?: return

            paintSemanticAnnotation(g, stroke, geometry, visibleContentClip)
            return
        }

        // Paint the stroke as one continuous geometry.
        //
        // The old fold-aware branch split strokes point-by-point when an anchor offset
        // landed inside a collapsed fold. That caused freehand strokes to break into
        // fragments and caused shape strokes to disappear almost completely because
        // shapes were not repainted after the split. It also read the editor folding
        // model from Swing paint code, which triggered IntelliJ read-access errors.
        //
        // Collapsed-fold markers should be rebuilt later from a cached/read-action-safe
        // model. The normal paint path must stay geometry-only and must not read
        // foldingModel during paintComponent().
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

    private fun paintSemanticAnnotation(
        g: Graphics2D,
        stroke: StrokePath,
        geometry: StrokeGeometryContent,
        visibleContentClip: Rectangle?
    ) {
        val text = stroke.annotationText?.takeIf { it.isNotBlank() } ?: return
        val bounds = resolveSemanticAnnotationBounds(stroke, geometry) ?: return
        if (bounds.width <= 0 || bounds.height <= 0) return
        if (visibleContentClip != null && !bounds.intersects(visibleContentClip)) return

        val color = stroke.color
        val padding = maxOf(2, minOf(bounds.width, bounds.height) / 12)
        val innerWidth = (bounds.width - padding * 2).coerceAtLeast(1)
        val innerHeight = (bounds.height - padding * 2).coerceAtLeast(1)
        val innerX = bounds.x + padding
        val innerY = bounds.y + padding
        val font = chooseSemanticFont(g, text, innerWidth, innerHeight)
        val lines = wrapSemanticText(text, font, g, innerWidth)
        if (lines.isEmpty()) return

        val metrics = g.getFontMetrics(font)
        val lineHeight = metrics.height + 2
        var y = innerY + metrics.ascent + maxOf(0, (innerHeight - (lineHeight * lines.size - 2)) / 2)

        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
        g.font = font
        g.color = color

        for (line in lines) {
            val lineWidth = metrics.stringWidth(line)
            val x = innerX + maxOf(0, (innerWidth - lineWidth) / 2)
            g.drawString(line, x, y)
            y += lineHeight
        }
    }

    private fun resolveSemanticAnnotationBounds(stroke: StrokePath, fallbackGeometry: StrokeGeometryContent): Rectangle? {
        val sizeBounds = stroke.annotationBounds?.takeIf { it.width > 0 && it.height > 0 }
        val anchorBounds = semanticGroupGeometryBounds(stroke)
            ?: fallbackGeometry.bounds.takeIf { it.width > 0 && it.height > 0 }?.let { Rectangle(it) }
            ?: sizeBounds?.let { Rectangle(it) }
            ?: return null
        val width = sizeBounds?.width ?: anchorBounds.width
        val height = sizeBounds?.height ?: anchorBounds.height
        val x = anchorBounds.x + (anchorBounds.width - width) / 2
        val y = anchorBounds.y + (anchorBounds.height - height) / 2
        return Rectangle(x, y, width, height)
    }

    private fun semanticGroupGeometryBounds(stroke: StrokePath): Rectangle? {
        val semanticKey = annotationGroupKey(stroke)
        var union: Rectangle? = null
        for (candidate in currentStrokesProvider()) {
            if (candidate.annotationText == null || annotationGroupKey(candidate) != semanticKey) {
                continue
            }
            val bounds = strokeWorkspace.getOrBuildStrokeGeometryContent(candidate)?.bounds ?: continue
            union = union?.apply { add(bounds) } ?: Rectangle(bounds)
        }
        return union
    }

    private fun chooseSemanticFont(g: Graphics2D, text: String, width: Int, height: Int): Font {
        val startSize = minOf(36, maxOf(12, height * 4 / 5))
        for (size in startSize downTo 10) {
            val font = Font("Dialog", Font.PLAIN, size)
            val lines = wrapSemanticText(text, font, g, width)
            if (lines.isEmpty()) continue
            val metrics = g.getFontMetrics(font)
            val totalHeight = lines.size * (metrics.height + 2)
            val widestLine = lines.maxOf { metrics.stringWidth(it) }
            if (totalHeight <= height && widestLine <= width) {
                return font
            }
        }
        return Font("Dialog", Font.PLAIN, 10)
    }

    private fun wrapSemanticText(text: String, font: Font, g: Graphics2D, maxWidth: Int): List<String> {
        val metrics = g.getFontMetrics(font)
        val lines = mutableListOf<String>()
        for (paragraph in splitParagraphs(text)) {
            if (paragraph.isBlank()) {
                lines += ""
                continue
            }
            var currentLine = ""
            for (word in paragraph.trim().split(Regex("\\s+")).filter { it.isNotBlank() }) {
                currentLine = wrapSemanticWord(currentLine, word, maxWidth, metrics, lines)
            }
            if (currentLine.isNotEmpty()) {
                lines += currentLine
            }
        }
        return lines
    }

    private fun wrapSemanticWord(
        currentLine: String,
        word: String,
        maxWidth: Int,
        metrics: java.awt.FontMetrics,
        lines: MutableList<String>
    ): String {
        var line = currentLine
        var remaining = word
        while (remaining.isNotEmpty()) {
            val candidate = if (line.isEmpty()) remaining else "$line $remaining"
            if (metrics.stringWidth(candidate) <= maxWidth) {
                line = candidate
                break
            }

            if (line.isNotEmpty()) {
                lines += line
                line = ""
                continue
            }

            val breakIndex = fittingSemanticPrefixLength(remaining, maxWidth, metrics)
            lines += remaining.substring(0, breakIndex)
            remaining = remaining.substring(breakIndex)
        }
        return line
    }

    private fun fittingSemanticPrefixLength(text: String, maxWidth: Int, metrics: java.awt.FontMetrics): Int {
        var index = 0
        var best = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            index += Character.charCount(codePoint)
            if (metrics.stringWidth(text.substring(0, index)) <= maxWidth) {
                best = index
            } else {
                break
            }
        }
        return if (best > 0) best else text.offsetByCodePoints(0, 1).coerceAtMost(text.length)
    }

    private fun splitParagraphs(text: String): List<String> {
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        val paragraphs = mutableListOf<String>()
        var start = 0
        while (start <= normalized.length) {
            val end = normalized.indexOf('\n', start)
            if (end < 0) {
                paragraphs += normalized.substring(start)
                break
            }
            paragraphs += normalized.substring(start, end)
            start = end + 1
        }
        return paragraphs
    }

    private fun annotationGroupKey(stroke: StrokePath): Long {
        return if (stroke.objectGroupId != 0L) stroke.objectGroupId else stroke.id
    }

}
