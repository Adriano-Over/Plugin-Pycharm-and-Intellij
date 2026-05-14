package com.floatbar

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
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.math.ceil

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
}
