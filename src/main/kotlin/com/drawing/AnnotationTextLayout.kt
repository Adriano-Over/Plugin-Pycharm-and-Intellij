package com.drawing

import java.awt.Font
import java.awt.FontMetrics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.image.BufferedImage
import kotlin.math.max
import kotlin.math.min

private val WHITESPACE_REGEX = Regex("\\s+")

internal data class AnnotationTextLineLayout(
    val text: String,
    val startIndex: Int,
    val x: Int,
    val baselineY: Int
)

internal data class AnnotationTextLayoutResult(
    val font: Font,
    val lines: List<AnnotationTextLineLayout>
)

internal data class AnnotationCharacterBounds(
    val index: Int,
    val character: Char,
    val bounds: Rectangle
)

internal object AnnotationTextLayout {
    fun layout(annotation: AnnotationPath, g: Graphics2D): AnnotationTextLayoutResult? {
        val text = annotation.text.takeIf { it.isNotBlank() } ?: return null
        val textArea = textArea(annotation)
        val font = chooseFont(g, text, textArea.width, textArea.height)
        val metrics = g.getFontMetrics(font)
        val wrappedLines = wrapText(text, metrics, textArea.width)
        if (wrappedLines.isEmpty()) return null

        val lineHeight = metrics.height + 2
        var baselineY = textArea.y + metrics.ascent + max(0, (textArea.height - (lineHeight * wrappedLines.size - 2)) / 2)
        var searchStart = 0
        val lines = wrappedLines.map { line ->
            val lineStart = text.indexOf(line, searchStart).takeIf { it >= 0 } ?: searchStart.coerceAtMost(text.length)
            val lineWidth = metrics.stringWidth(line)
            val x = textArea.x + max(0, (textArea.width - lineWidth) / 2)
            searchStart = (lineStart + line.length).coerceAtMost(text.length)
            AnnotationTextLineLayout(
                text = line,
                startIndex = lineStart,
                x = x,
                baselineY = baselineY
            ).also {
                baselineY += lineHeight
            }
        }
        return AnnotationTextLayoutResult(font = font, lines = lines)
    }

    fun characterBounds(annotation: AnnotationPath): List<AnnotationCharacterBounds> {
        val image = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        return try {
            val layout = layout(annotation, g) ?: return emptyList()
            val metrics = g.getFontMetrics(layout.font)
            val outlinePadding = if (annotation.style == BalloonTextStyle.OUTLINE) 1 else 0
            val result = mutableListOf<AnnotationCharacterBounds>()
            for (line in layout.lines) {
                var x = line.x
                for (lineIndex in line.text.indices) {
                    val character = line.text[lineIndex]
                    val charWidth = metrics.charWidth(character).coerceAtLeast(1)
                    if (!character.isWhitespace()) {
                        result += AnnotationCharacterBounds(
                            index = (line.startIndex + lineIndex).coerceAtMost(annotation.text.lastIndex),
                            character = character,
                            bounds = Rectangle(
                                x - outlinePadding,
                                line.baselineY - metrics.ascent - outlinePadding,
                                charWidth + outlinePadding * 2,
                                metrics.height + outlinePadding * 2
                            )
                        )
                    }
                    x += charWidth
                }
            }
            result
        } finally {
            g.dispose()
        }
    }

    private fun textArea(annotation: AnnotationPath): Rectangle {
        val width = annotation.width.coerceAtLeast(1)
        val height = annotation.height.coerceAtLeast(1)
        val balloonTailReserve = if (annotation.kind == AnnotationKind.BALLOON) {
            max(14, height / 4).coerceAtMost(max(14, height / 3)).coerceAtMost(height / 2)
        } else {
            0
        }
        val padding = max(3, min(width, height) / 12)
        return Rectangle(
            padding,
            padding,
            (width - padding * 2).coerceAtLeast(1),
            (height - balloonTailReserve - padding * 2).coerceAtLeast(1)
        )
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
            for (word in paragraph.trim().split(WHITESPACE_REGEX).filter { it.isNotBlank() }) {
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
