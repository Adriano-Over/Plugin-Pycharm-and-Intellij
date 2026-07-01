package com.drawing

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.FontMetrics
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
            paintText(g, annotation, width, height)
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
            moveTo(width * 0.42, baseY)
            lineTo(width * 0.56, baseY)
            lineTo(width * 0.68, (height - 2).toDouble())
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

    private fun paintText(g: Graphics2D, annotation: AnnotationPath, width: Int, height: Int) {
        val text = annotation.text.takeIf { it.isNotBlank() } ?: return
        val balloonTailReserve = if (annotation.kind == AnnotationKind.BALLOON) {
            max(14, height / 4).coerceAtMost(max(14, height / 3)).coerceAtMost(height / 2)
        } else {
            0
        }
        val padding = max(3, min(width, height) / 12)
        val innerWidth = (width - padding * 2).coerceAtLeast(1)
        val innerHeight = (height - balloonTailReserve - padding * 2).coerceAtLeast(1)
        val innerX = padding
        val innerY = padding

        val font = chooseFont(g, text, innerWidth, innerHeight)
        val lines = wrapText(text, g.getFontMetrics(font), innerWidth)
        if (lines.isEmpty()) return

        val metrics = g.getFontMetrics(font)
        val lineHeight = metrics.height + 2
        var y = innerY + metrics.ascent + max(0, (innerHeight - (lineHeight * lines.size - 2)) / 2)

        g.font = font
        g.color = annotation.color

        for (line in lines) {
            val lineWidth = metrics.stringWidth(line)
            val x = innerX + max(0, (innerWidth - lineWidth) / 2)
            if (annotation.style == BalloonTextStyle.OUTLINE) {
                paintOutlinedString(g, line, x, y)
            } else {
                g.drawString(line, x, y)
            }
            y += lineHeight
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

    private fun chooseFont(g: Graphics2D, text: String, width: Int, height: Int): Font {
        val startSize = min(36, max(12, height * 4 / 5))
        for (size in startSize downTo 10) {
            val font = Font("Dialog", Font.PLAIN, size)
            val metrics = g.getFontMetrics(font)
            val lines = wrapText(text, metrics, width)
            if (lines.isEmpty()) continue
            val totalHeight = lines.size * (metrics.height + 2)
            val widestLine = lines.maxOf { metrics.stringWidth(it) }
            if (totalHeight <= height && widestLine <= width) {
                return font
            }
        }
        return Font("Dialog", Font.PLAIN, 10)
    }

    private fun wrapText(text: String, metrics: FontMetrics, maxWidth: Int): List<String> {
        val lines = mutableListOf<String>()
        for (paragraph in splitParagraphs(text)) {
            if (paragraph.isBlank()) {
                lines += ""
                continue
            }
            var currentLine = ""
            for (word in paragraph.trim().split(Regex("\\s+")).filter { it.isNotBlank() }) {
                currentLine = wrapWord(currentLine, word, maxWidth, metrics, lines)
            }
            if (currentLine.isNotEmpty()) {
                lines += currentLine
            }
        }
        return lines
    }

    private fun wrapWord(
        currentLine: String,
        word: String,
        maxWidth: Int,
        metrics: FontMetrics,
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

            val breakIndex = fittingPrefixLength(remaining, maxWidth, metrics)
            lines += remaining.substring(0, breakIndex)
            remaining = remaining.substring(breakIndex)
        }
        return line
    }

    private fun fittingPrefixLength(text: String, maxWidth: Int, metrics: FontMetrics): Int {
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
}
