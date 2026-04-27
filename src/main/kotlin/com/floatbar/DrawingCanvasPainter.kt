package com.floatbar

import com.intellij.openapi.editor.Editor
import java.awt.BasicStroke
import java.awt.Color
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
                if (previewBounds != null &&
                    previewBounds.maxLine >= visibleLineRange.first &&
                    previewBounds.minLine <= visibleLineRange.second
                ) {
                    paintStroke(gContent, preview, preview = true, visibleContentClip = contentClip)
                }
            }
        } finally {
            gContent.dispose()
        }

        paintToolPreview(g)
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
