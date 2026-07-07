package com.drawing

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import kotlin.math.max
import kotlin.math.min

internal object AnnotationRenderer {
    fun render(annotation: AnnotationPath): BufferedImage {
        val width = annotation.width.coerceAtLeast(1)
        val height = annotation.height.coerceAtLeast(1)
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try {
            configure(g)
            if (annotation.kind == AnnotationKind.BALLOON) {
                paintBalloon(g, width, height, annotation.color)
            }
            paintText(g, annotation)
        } finally {
            g.dispose()
        }
        return image
    }

    private fun configure(g: Graphics2D) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
    }

    private fun paintBalloon(g: Graphics2D, width: Int, height: Int, color: Color) {
        val tailHeight = max(14, height / 4).coerceAtMost(max(14, height / 3)).coerceAtMost(height / 2)
        val bubbleHeight = (height - tailHeight).coerceAtLeast(1)
        val arc = min(width, bubbleHeight).coerceAtLeast(1) / 4
        val bubble = RoundRectangle2D.Double(
            1.5,
            1.5,
            (width - 3).coerceAtLeast(1).toDouble(),
            (bubbleHeight - 3).coerceAtLeast(1).toDouble(),
            arc.coerceAtLeast(10).toDouble(),
            arc.coerceAtLeast(10).toDouble()
        )
        val tail = Path2D.Double().apply {
            val baseY = bubbleHeight - 2.0
            val tailCenterX = width * 0.34
            val tailHalf = max(7, width / 12).coerceAtMost(max(7, width / 4))
            moveTo(tailCenterX + tailHalf, baseY)
            lineTo(width * 0.20, (height - 2).toDouble())
            lineTo(tailCenterX - tailHalf, baseY)
            closePath()
        }

        g.color = Color(color.red, color.green, color.blue, 26)
        g.fill(bubble)
        g.fill(tail)
        g.stroke = BasicStroke(3.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g.color = color
        g.draw(bubble)
        g.draw(tail)
    }

    private fun paintText(g: Graphics2D, annotation: AnnotationPath) {
        val layout = AnnotationTextLayout.layout(annotation, g) ?: return

        g.font = layout.font
        g.color = annotation.color

        for (line in layout.lines) {
            if (annotation.style == BalloonTextStyle.OUTLINE) {
                paintOutlinedString(g, line.text, line.x, line.baselineY)
            } else {
                g.drawString(line.text, line.x, line.baselineY)
            }
        }
    }

    private fun paintOutlinedString(g: Graphics2D, text: String, x: Int, y: Int) {
        val originalColor = g.color
        val outline = Color(
            255 - originalColor.red,
            255 - originalColor.green,
            255 - originalColor.blue,
            originalColor.alpha
        )
        g.color = outline
        g.drawString(text, x - 1, y)
        g.drawString(text, x + 1, y)
        g.drawString(text, x, y - 1)
        g.drawString(text, x, y + 1)
        g.color = originalColor
        g.drawString(text, x, y)
    }
}
