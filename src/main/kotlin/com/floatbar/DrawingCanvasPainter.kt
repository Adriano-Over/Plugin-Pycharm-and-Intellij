package com.floatbar

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.FoldRegion
import com.intellij.openapi.editor.LogicalPosition
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.Polygon
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

class DrawingCanvasPainter(
    private val canvas: JPanel,
    private val editorProvider: () -> Editor?,
    private val currentStrokesProvider: () -> List<StrokePath>,
    private val shapePreviewProvider: () -> StrokePath?,
    private val gridEnabledProvider: () -> Boolean,
    private val currentToolProvider: () -> FloatBarToolMode,
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
        val gContent = g.create() as Graphics2D
        gContent.translate(contentOrigin.x.toDouble(), contentOrigin.y.toDouble())

        try {
            for (stroke in currentStrokesProvider()) {
                val bounds = boundsMap[stroke.id]
                    ?: DrawingViewportTools.computeStrokeLineBounds(stroke)?.also { boundsMap[stroke.id] = it }
                if (bounds != null && bounds.maxLine >= visibleLineRange.first && bounds.minLine <= visibleLineRange.second) {
                    paintStroke(gContent, stroke, preview = false, visibleContentClip = contentClip)
                }
            }

            shapePreviewProvider()?.let { preview ->
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

    private fun paintShapePreviewHandles(
        g: Graphics2D,
        preview: StrokePath,
        geometry: StrokeGeometryContent,
        visibleContentClip: Rectangle
    ) {
        val kind = preview.kind ?: return
        if (kind == ShapeKind.LINE || kind == ShapeKind.ARROW) return

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
        if (currentToolProvider() != FloatBarToolMode.ERASE) return
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
        val editor = editorProvider()
        if (!preview && editor != null && paintFoldAwareStroke(g, editor, stroke, visibleContentClip)) {
            return
        }

        val geometry = if (preview) {
            strokeWorkspace.buildStrokeGeometryContent(stroke)
        } else {
            strokeWorkspace.getOrBuildStrokeGeometryContent(stroke)
        } ?: return

        strokeRenderer.paintStroke(
            g = g,
            stroke = stroke,
            geometry = geometry,
            preview = preview,
            visibleContentClip = visibleContentClip
        )
    }

    private fun paintFoldAwareStroke(
        g: Graphics2D,
        editor: Editor,
        stroke: StrokePath,
        visibleContentClip: Rectangle?
    ): Boolean {
        if (stroke.points.isEmpty()) return false

        val hiddenMarkers = linkedSetOf<CollapsedDrawingMarker>()
        val visibleSegments = mutableListOf<MutableList<AnchorPoint>>()
        var currentSegment = mutableListOf<AnchorPoint>()
        var hasHiddenPoint = false

        for (point in stroke.points) {
            val foldedRegion = collapsedRegionFor(point, editor)
            if (foldedRegion == null) {
                currentSegment.add(point)
            } else {
                hasHiddenPoint = true
                hiddenMarkers.add(
                    CollapsedDrawingMarker(
                        startOffset = foldedRegion.startOffset,
                        endOffset = foldedRegion.endOffset,
                        colorRgb = stroke.color.rgb
                    )
                )
                if (currentSegment.isNotEmpty()) {
                    visibleSegments.add(currentSegment)
                    currentSegment = mutableListOf()
                }
            }
        }

        if (!hasHiddenPoint) return false

        if (currentSegment.isNotEmpty()) {
            visibleSegments.add(currentSegment)
        }

        if (!stroke.filled && stroke.kind == null) {
            for (segment in visibleSegments) {
                if (segment.size < 2) continue
                val segmentStroke = StrokePath(
                    color = stroke.color,
                    width = stroke.width,
                    points = segment.map { it.copy() }.toMutableList(),
                    filled = false,
                    kind = null
                )
                val segmentGeometry = strokeWorkspace.buildStrokeGeometryContent(segmentStroke) ?: continue
                strokeRenderer.paintStroke(
                    g = g,
                    stroke = segmentStroke,
                    geometry = segmentGeometry,
                    preview = false,
                    visibleContentClip = visibleContentClip
                )
            }
        }

        paintCollapsedDrawingMarkers(g, editor, hiddenMarkers, visibleContentClip)
        return true
    }

    private fun collapsedRegionFor(point: AnchorPoint, editor: Editor): FoldRegion? {
        val document = editor.document
        if (document.textLength <= 0) return null

        val safeOffset = point.offset.coerceIn(0, document.textLength)
        val region = editor.foldingModel.getCollapsedRegionAtOffset(safeOffset) ?: return null
        if (region.isExpanded) return null
        if (safeOffset <= region.startOffset || safeOffset >= region.endOffset) return null

        return region
    }

    private fun paintCollapsedDrawingMarkers(
        g: Graphics2D,
        editor: Editor,
        markers: Set<CollapsedDrawingMarker>,
        visibleContentClip: Rectangle?
    ) {
        if (markers.isEmpty()) return

        val gMarker = g.create() as Graphics2D
        try {
            gMarker.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            gMarker.stroke = BasicStroke(1f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)

            for (marker in markers) {
                paintCollapsedDrawingMarker(gMarker, editor, marker, visibleContentClip)
            }
        } finally {
            gMarker.dispose()
        }
    }

    private fun paintCollapsedDrawingMarker(
        g: Graphics2D,
        editor: Editor,
        marker: CollapsedDrawingMarker,
        visibleContentClip: Rectangle?
    ) {
        val document = editor.document
        if (document.lineCount <= 0) return

        val safeOffset = marker.startOffset.coerceIn(0, document.textLength)
        val line = document.getLineNumber(safeOffset).coerceIn(0, document.lineCount - 1)
        val lineStart = editor.logicalPositionToXY(LogicalPosition(line, 0))
        val lineEndOffset = document.getLineEndOffset(line)
        val lineEndPoint = editor.logicalPositionToXY(editor.offsetToLogicalPosition(lineEndOffset))

        val starRadius = 6
        val starDiameter = starRadius * 2
        val starLeftX = max(lineStart.x, lineEndPoint.x) + 40
        val centerX = starLeftX + starRadius
        val centerY = lineStart.y + editor.lineHeight / 2
        val bounds = Rectangle(starLeftX, centerY - starRadius, starDiameter, starDiameter)
        if (visibleContentClip != null && !bounds.intersects(visibleContentClip)) return

        val star = createStarPolygon(centerX, centerY, outerRadius = starRadius, innerRadius = 3)
        g.color = Color(255, 215, 0, 240)
        g.fillPolygon(star)
        g.color = Color(150, 110, 0, 235)
        g.stroke = BasicStroke(1f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g.drawPolygon(star)
    }

    private fun createStarPolygon(centerX: Int, centerY: Int, outerRadius: Int, innerRadius: Int): Polygon {
        val polygon = Polygon()
        val points = 10
        val startAngle = -PI / 2.0

        for (index in 0 until points) {
            val radius = if (index % 2 == 0) outerRadius else innerRadius
            val angle = startAngle + index * PI / 5.0
            val x = centerX + (cos(angle) * radius).toInt()
            val y = centerY + (sin(angle) * radius).toInt()
            polygon.addPoint(x, y)
        }

        return polygon
    }

    private data class CollapsedDrawingMarker(
        val startOffset: Int,
        val endOffset: Int,
        val colorRgb: Int
    )
}
