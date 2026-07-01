package com.drawing

import java.awt.Color
import java.awt.Font
import java.awt.Rectangle
import java.awt.Shape
import java.awt.font.FontRenderContext
import java.awt.geom.PathIterator
import java.text.Normalizer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Legacy vector-glyph text generator.
 *
 * Committed text and balloon annotations should use AnnotationPath plus the cached
 * AnnotationRenderer path instead of generating many StrokePath glyph fragments.
 * Kept temporarily for fallback, migration tests, and old geometry coverage.
 */
internal object BalloonTextStrokeFactory {
    private const val MIN_FONT_SIZE = 10
    private const val MAX_FONT_SIZE = 32
    private const val TEXT_STROKE_WIDTH = 1.75f
    private const val TEXT_LETTER_SPACING_PX = 1.0
    private const val TEXT_LINE_SPACING_PX = 2.0
    private const val TEXT_ACCENT_CLOSER_PX = 2.0
    private const val DRAWN_TEXT_TOP_EXTRA = 0.16
    private const val DRAWN_TEXT_LINE_HEIGHT = 1.26
    private const val DRAWN_CORNER_ROUNDING = 0.34
    private const val DRAWN_CORNER_CURVE_STEPS = 8
    private const val DRAWN_CAP_TOP = 0.04
    private const val DRAWN_BASELINE = 0.96

    fun buildTextStrokes(
        text: String,
        bounds: Rectangle,
        color: Color,
        style: BalloonTextStyle = BalloonTextStyle.SOLID
    ): List<StrokePath> {
        if (text.trim().isEmpty() || bounds.width <= 4 || bounds.height <= 4) {
            return emptyList()
        }

        if (style == BalloonTextStyle.SOLID) {
            return buildDrawnTextStrokes(text, bounds, color)
        }

        val fontRenderContext = FontRenderContext(null, true, true)
        val font = chooseFont(text, bounds, fontRenderContext)
        val lines = wrapText(text, font, fontRenderContext, bounds.width)
        if (lines.isEmpty()) {
            return emptyList()
        }

        val lineMetrics = font.getLineMetrics("Ag", fontRenderContext)
        val lineHeight = lineMetrics.height + TEXT_LINE_SPACING_PX.toFloat()
        val totalHeight = lineMetrics.height * lines.size +
            TEXT_LINE_SPACING_PX.toFloat() * (lines.size - 1).coerceAtLeast(0)
        var baseline = bounds.y + ((bounds.height - totalHeight) / 2.0f) + lineMetrics.ascent

        val strokes = mutableListOf<StrokePath>()
        for (line in lines) {
            val lineWidth = outlineTextWidthPx(line, font, fontRenderContext)
            var x = bounds.x + ((bounds.width - lineWidth) / 2.0)
            for (character in line) {
                val glyphVector = font.createGlyphVector(fontRenderContext, character.toString())
                val visualBounds = glyphVector.visualBounds
                if (!character.isWhitespace()) {
                    val outline = glyphVector.getOutline((x - visualBounds.x).toFloat(), baseline)
                    strokes += outlineToStrokes(outline, color)
                }
                x += outlineCharacterAdvancePx(character, font, fontRenderContext) + TEXT_LETTER_SPACING_PX
            }
            baseline += lineHeight
        }
        return strokes
    }

    private fun chooseFont(text: String, bounds: Rectangle, fontRenderContext: FontRenderContext): Font {
        val startSize = min(MAX_FONT_SIZE, max(MIN_FONT_SIZE, bounds.height / 2))
        for (size in startSize downTo MIN_FONT_SIZE) {
            val font = Font("Dialog", Font.PLAIN, size)
            val lines = wrapText(text, font, fontRenderContext, bounds.width)
            if (lines.isEmpty()) {
                continue
            }

            val lineMetrics = font.getLineMetrics("Ag", fontRenderContext)
            val totalHeight = lineMetrics.height * lines.size +
                TEXT_LINE_SPACING_PX * (lines.size - 1).coerceAtLeast(0)
            val widestLine = lines.maxOf { outlineTextWidthPx(it, font, fontRenderContext) }
            if (totalHeight <= bounds.height && widestLine <= bounds.width) {
                return font
            }
        }
        return Font("Dialog", Font.PLAIN, MIN_FONT_SIZE)
    }

    private fun wrapText(
        text: String,
        font: Font,
        fontRenderContext: FontRenderContext,
        maxWidth: Int
    ): List<String> {
        val lines = mutableListOf<String>()
        for (paragraph in splitParagraphs(text)) {
            if (paragraph.isBlank()) {
                lines += ""
                continue
            }

            var currentLine = ""
            for (word in paragraph.trim().split(Regex("\\s+")).filter { it.isNotBlank() }) {
                currentLine = wrapWord(
                    currentLine = currentLine,
                    word = word,
                    maxWidth = maxWidth.toDouble(),
                    widthOf = { candidate -> outlineTextWidthPx(candidate, font, fontRenderContext) },
                    lines = lines
                )
            }
            if (currentLine.isNotEmpty()) {
                lines += currentLine
            }
        }
        return lines
    }

    private fun outlineTextWidthPx(text: String, font: Font, fontRenderContext: FontRenderContext): Double {
        if (text.isEmpty()) return 0.0
        return text.sumOf { character -> outlineCharacterAdvancePx(character, font, fontRenderContext) } +
            (text.length - 1) * TEXT_LETTER_SPACING_PX
    }

    private fun outlineCharacterAdvancePx(
        character: Char,
        font: Font,
        fontRenderContext: FontRenderContext
    ): Double {
        return font.getStringBounds(character.toString(), fontRenderContext).width
    }

    private fun outlineToStrokes(
        shape: Shape,
        color: Color,
        strokeWidth: Float = TEXT_STROKE_WIDTH
    ): List<StrokePath> {
        val pathIterator = shape.getPathIterator(null, 1.0)
        val coordinates = DoubleArray(6)
        val strokes = mutableListOf<StrokePath>()
        var currentPoints = mutableListOf<AnchorPoint>()
        var firstPoint: AnchorPoint? = null

        fun flush(closePath: Boolean) {
            if (closePath && firstPoint != null && currentPoints.isNotEmpty()) {
                currentPoints += firstPoint!!.copy()
            }
            if (currentPoints.size >= 2) {
                strokes += StrokePath(
                    color = color,
                    width = strokeWidth,
                    points = currentPoints,
                    filled = false,
                    kind = null
                )
            }
            currentPoints = mutableListOf()
            firstPoint = null
        }

        while (!pathIterator.isDone) {
            when (pathIterator.currentSegment(coordinates)) {
                PathIterator.SEG_MOVETO -> {
                    flush(closePath = false)
                    val point = AnchorPoint(
                        line = 0,
                        column = 0,
                        dx = coordinates[0].roundToInt(),
                        dy = coordinates[1].roundToInt()
                    )
                    currentPoints = mutableListOf(point)
                    firstPoint = point.copy()
                }

                PathIterator.SEG_LINETO -> {
                    currentPoints += AnchorPoint(
                        line = 0,
                        column = 0,
                        dx = coordinates[0].roundToInt(),
                        dy = coordinates[1].roundToInt()
                    )
                }

                PathIterator.SEG_CLOSE -> flush(closePath = true)
            }
            pathIterator.next()
        }
        flush(closePath = false)

        return strokes
    }

    private fun buildDrawnTextStrokes(text: String, bounds: Rectangle, color: Color): List<StrokePath> {
        val layout = chooseDrawnTextLayout(text, bounds)
        if (layout.lines.isEmpty()) {
            return emptyList()
        }

        val fontSize = layout.fontSize
        val lineHeight = fontSize * DRAWN_TEXT_LINE_HEIGHT + TEXT_LINE_SPACING_PX
        val totalHeight = fontSize * DRAWN_TEXT_LINE_HEIGHT * layout.lines.size +
            TEXT_LINE_SPACING_PX * (layout.lines.size - 1).coerceAtLeast(0)
        var y = bounds.y + ((bounds.height - totalHeight) / 2.0) + fontSize * DRAWN_TEXT_TOP_EXTRA
        val strokeWidth = (fontSize * 0.105).coerceIn(2.2, 5.2).toFloat()
        val strokes = mutableListOf<StrokePath>()

        for (line in layout.lines) {
            val lineWidth = drawnTextWidthPx(line, fontSize)
            var x = bounds.x + ((bounds.width - lineWidth) / 2.0)
            for (character in line) {
                val glyphText = normalizeGlyph(character)
                val glyph = vectorGlyph(glyphText.base)
                if (glyph.strokes.isNotEmpty()) {
                    val normalizedStrokes = if (glyph.normalizeHeight) {
                        normalizeGlyphHeight(glyph.strokes)
                    } else {
                        glyph.strokes
                    }
                    for (strokePoints in normalizedStrokes + accentStrokes(glyphText.marks, glyph.advance, fontSize)) {
                        val points = strokePoints.map { point ->
                            handLetteredAnchor(point, x, y, fontSize)
                        }.distinctBy { it.dx to it.dy }.toMutableList()

                        if (points.size >= 2) {
                            strokes += StrokePath(
                                color = color,
                                width = strokeWidth,
                                points = points,
                                filled = false,
                                kind = null
                            )
                        }
                    }
                }
                x += glyph.advance * fontSize + TEXT_LETTER_SPACING_PX
            }
            y += lineHeight
        }

        return strokes
    }

    private fun chooseDrawnTextLayout(text: String, bounds: Rectangle): DrawnTextLayout {
        val maxSize = min(MAX_FONT_SIZE, max(MIN_FONT_SIZE, (bounds.height / 1.15).roundToInt()))
        for (size in maxSize downTo MIN_FONT_SIZE) {
            val lines = wrapDrawnText(text, bounds.width.toDouble(), size.toDouble())
            if (lines.isEmpty()) continue

            val widestLine = lines.maxOf { line -> drawnTextWidthPx(line, size.toDouble()) }
            val totalHeight = lines.size * size * DRAWN_TEXT_LINE_HEIGHT +
                TEXT_LINE_SPACING_PX * (lines.size - 1).coerceAtLeast(0)
            if (widestLine <= bounds.width && totalHeight <= bounds.height) {
                return DrawnTextLayout(size.toDouble(), lines)
            }
        }

        return DrawnTextLayout(
            fontSize = MIN_FONT_SIZE.toDouble(),
            lines = wrapDrawnText(text, bounds.width.toDouble(), MIN_FONT_SIZE.toDouble())
        )
    }

    private fun wrapDrawnText(text: String, maxLineWidthPx: Double, fontSize: Double): List<String> {
        val lines = mutableListOf<String>()
        for (paragraph in splitParagraphs(text)) {
            if (paragraph.isBlank()) {
                lines += ""
                continue
            }

            var currentLine = ""
            for (word in paragraph.trim().split(Regex("\\s+")).filter { it.isNotBlank() }) {
                currentLine = wrapWord(
                    currentLine = currentLine,
                    word = word,
                    maxWidth = maxLineWidthPx,
                    widthOf = { candidate -> drawnTextWidthPx(candidate, fontSize) },
                    lines = lines
                )
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
        maxWidth: Double,
        widthOf: (String) -> Double,
        lines: MutableList<String>
    ): String {
        var line = currentLine
        var remainingWord = word

        while (remainingWord.isNotEmpty()) {
            val candidate = if (line.isEmpty()) remainingWord else "$line $remainingWord"
            if (widthOf(candidate) <= maxWidth) {
                line = candidate
                remainingWord = ""
                break
            }

            if (line.isNotEmpty()) {
                lines += line
                line = ""
                continue
            }

            val breakIndex = fittingPrefixLength(remainingWord, maxWidth, widthOf)
            val prefix = remainingWord.substring(0, breakIndex)
            lines += prefix
            remainingWord = remainingWord.substring(breakIndex)
        }

        return line
    }

    private fun fittingPrefixLength(text: String, maxWidth: Double, widthOf: (String) -> Double): Int {
        var index = 0
        var bestFit = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            index += Character.charCount(codePoint)
            if (widthOf(text.substring(0, index)) <= maxWidth) {
                bestFit = index
            } else {
                break
            }
        }

        if (bestFit > 0) {
            return bestFit
        }

        return text.offsetByCodePoints(0, 1).coerceAtMost(text.length)
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

    private fun drawnTextWidthPx(text: String, fontSize: Double): Double {
        if (text.isEmpty()) return 0.0
        return text.sumOf { character -> vectorGlyph(normalizeGlyph(character).base).advance * fontSize } +
            (text.length - 1) * TEXT_LETTER_SPACING_PX
    }

    private fun normalizeGlyphHeight(strokes: List<List<VectorPoint>>): List<List<VectorPoint>> {
        val allPoints = strokes.flatten()
        if (allPoints.isEmpty()) return strokes

        val minY = allPoints.minOf { it.y }
        val maxY = allPoints.maxOf { it.y }
        val sourceHeight = maxY - minY
        if (sourceHeight < 0.08) return strokes

        val targetHeight = DRAWN_BASELINE - DRAWN_CAP_TOP
        return strokes.map { stroke ->
            stroke.map { point ->
                point.copy(
                    y = DRAWN_CAP_TOP + ((point.y - minY) / sourceHeight) * targetHeight
                )
            }
        }
    }

    private fun handLetteredAnchor(
        point: VectorPoint,
        originX: Double,
        originY: Double,
        fontSize: Double
    ): AnchorPoint {
        return AnchorPoint(
            line = 0,
            column = 0,
            dx = (originX + point.x * fontSize).roundToInt(),
            dy = (originY + point.y * fontSize).roundToInt()
        )
    }

    private fun normalizeGlyph(character: Char): NormalizedGlyph {
        val decomposed = Normalizer.normalize(character.toString(), Normalizer.Form.NFD)
        val nonSpacingMark = Character.NON_SPACING_MARK.toInt()
        val base = decomposed
            .firstOrNull { Character.getType(it) != nonSpacingMark }
            ?: character
        val marks = decomposed.filter { Character.getType(it) == nonSpacingMark }
        return NormalizedGlyph(base, marks)
    }

    private fun accentStrokes(marks: String, advance: Double, fontSize: Double): List<List<VectorPoint>> {
        val center = advance / 2.0
        val topAccentNudge = TEXT_ACCENT_CLOSER_PX / fontSize
        val bottomAccentNudge = -TEXT_ACCENT_CLOSER_PX / fontSize
        val strokes = mutableListOf<List<VectorPoint>>()
        fun top(points: List<VectorPoint>) = points.map { it.copy(y = it.y + topAccentNudge) }
        fun bottom(points: List<VectorPoint>) = points.map { it.copy(y = it.y + bottomAccentNudge) }
        for (mark in marks) {
            when (mark.code) {
                0x0301 -> strokes += top(
                    s(center - 0.06, -0.16, center + 0.16, -0.32)
                )
                0x0300 -> strokes += top(
                    s(center + 0.12, -0.16, center - 0.10, -0.32)
                )
                0x0302 -> strokes += top(
                    s(
                        center - 0.18, -0.12,
                        center, -0.30,
                        center + 0.18, -0.12
                    )
                )
                0x0303 -> strokes += top(
                    s(
                        center - 0.24, -0.18,
                        center - 0.10, -0.26,
                        center + 0.08, -0.14,
                        center + 0.24, -0.22
                    )
                )
                0x0327 -> strokes += bottom(
                    s(
                        center - 0.06, 1.02,
                        center + 0.08, 1.12,
                        center - 0.02, 1.22,
                        center - 0.16, 1.18
                    )
                )
            }
        }
        return strokes
    }

    private fun vectorGlyph(character: Char): VectorGlyph {
        return when (character) {
            'A' -> glyph(0.72, s(0.06, 1.0, 0.36, 0.02, 0.66, 1.0), s(0.18, 0.58, 0.54, 0.58))
            'B' -> glyph(0.72, s(0.08, 1.0, 0.08, 0.02), s(0.08, 0.02, 0.52, 0.06, 0.58, 0.28, 0.38, 0.47, 0.08, 0.47), s(0.08, 0.47, 0.58, 0.50, 0.62, 0.80, 0.40, 0.98, 0.08, 1.0))
            'C' -> glyph(0.72, s(0.62, 0.14, 0.48, 0.04, 0.20, 0.08, 0.08, 0.34, 0.08, 0.66, 0.20, 0.92, 0.48, 0.96, 0.62, 0.86))
            'D' -> glyph(0.76, s(0.08, 1.0, 0.08, 0.02), s(0.08, 0.02, 0.50, 0.04, 0.68, 0.30, 0.68, 0.50, 0.68, 0.70, 0.50, 0.96, 0.08, 1.0))
            'E' -> glyph(
                0.72,
                s(0.64, 0.04, 0.16, 0.04, 0.08, 0.14, 0.08, 0.50, 0.54, 0.50, 0.08, 0.50, 0.08, 0.86, 0.18, 0.96, 0.66, 0.96)
            )
            'F' -> glyph(0.66, s(0.08, 0.02, 0.08, 1.0), s(0.08, 0.04, 0.62, 0.04), s(0.08, 0.50, 0.52, 0.50))
            'G' -> glyph(0.76, s(0.66, 0.16, 0.50, 0.04, 0.20, 0.08, 0.08, 0.34, 0.08, 0.68, 0.22, 0.94, 0.54, 0.96, 0.68, 0.78, 0.68, 0.58, 0.46, 0.58))
            'H' -> glyph(0.76, s(0.08, 0.02, 0.08, 1.0), s(0.66, 0.02, 0.66, 1.0), s(0.08, 0.52, 0.66, 0.52))
            'I' -> glyph(0.42, s(0.21, 0.04, 0.21, 0.96), s(0.08, 0.04, 0.34, 0.04), s(0.08, 0.96, 0.34, 0.96))
            'J' -> glyph(0.62, s(0.50, 0.04, 0.50, 0.76, 0.38, 0.96, 0.18, 0.96, 0.08, 0.82))
            'K' -> glyph(0.72, s(0.08, 0.02, 0.08, 1.0), s(0.64, 0.04, 0.08, 0.56, 0.66, 0.98))
            'L' -> glyph(0.62, s(0.08, 0.02, 0.08, 0.96, 0.58, 0.96))
            'M' -> glyph(0.94, s(0.08, 0.96, 0.08, 0.14, 0.18, 0.04, 0.46, 0.62, 0.74, 0.04, 0.86, 0.14, 0.86, 0.96))
            'N' -> glyph(0.80, s(0.08, 0.96, 0.08, 0.14, 0.16, 0.04, 0.68, 0.86, 0.70, 0.14, 0.64, 0.04))
            'O' -> glyph(0.78, oval(0.39, 0.50, 0.31, 0.46, startAngle = -PI / 2.0))
            'P' -> glyph(0.70, s(0.08, 1.0, 0.08, 0.02), s(0.08, 0.02, 0.54, 0.06, 0.60, 0.34, 0.42, 0.52, 0.08, 0.52))
            'Q' -> glyph(0.80, oval(0.38, 0.49, 0.30, 0.45), s(0.48, 0.70, 0.72, 1.02))
            'R' -> glyph(0.74, s(0.08, 1.0, 0.08, 0.02), s(0.08, 0.02, 0.54, 0.06, 0.60, 0.34, 0.42, 0.52, 0.08, 0.52), s(0.34, 0.54, 0.68, 1.0))
            'S' -> glyph(0.70, s(0.62, 0.14, 0.44, 0.02, 0.18, 0.10, 0.12, 0.34, 0.34, 0.50, 0.58, 0.64, 0.52, 0.90, 0.22, 0.98, 0.08, 0.84))
            'T' -> glyph(0.70, s(0.06, 0.04, 0.64, 0.04), s(0.35, 0.04, 0.35, 0.98))
            'U' -> glyph(0.78, s(0.08, 0.04, 0.08, 0.72, 0.20, 0.96, 0.40, 1.0, 0.62, 0.96, 0.70, 0.72, 0.70, 0.04))
            'V' -> glyph(0.72, s(0.06, 0.04, 0.36, 1.0, 0.68, 0.04))
            'W' -> glyph(1.00, s(0.06, 0.04, 0.24, 1.0, 0.50, 0.38, 0.76, 1.0, 0.94, 0.04))
            'X' -> glyph(0.72, s(0.08, 0.04, 0.64, 0.98), s(0.64, 0.04, 0.08, 0.98))
            'Y' -> glyph(0.72, s(0.06, 0.04, 0.36, 0.50, 0.68, 0.04), s(0.36, 0.50, 0.36, 0.98))
            'Z' -> glyph(0.70, s(0.08, 0.04, 0.62, 0.04, 0.08, 0.96, 0.64, 0.96))
            '0' -> glyph(0.74, oval(0.37, 0.50, 0.28, 0.46), s(0.56, 0.16, 0.18, 0.86))
            '1' -> glyph(0.48, s(0.14, 0.22, 0.28, 0.06, 0.28, 0.96), s(0.16, 0.96, 0.42, 0.96))
            '2' -> glyph(0.68, s(0.10, 0.24, 0.24, 0.06, 0.52, 0.08, 0.60, 0.30, 0.08, 0.96, 0.62, 0.96))
            '3' -> glyph(0.68, s(0.12, 0.10, 0.56, 0.10, 0.34, 0.48, 0.58, 0.54, 0.54, 0.88, 0.22, 0.98, 0.08, 0.84))
            '4' -> glyph(0.72, s(0.56, 0.98, 0.56, 0.06, 0.08, 0.64, 0.66, 0.64))
            '5' -> glyph(0.68, s(0.60, 0.06, 0.16, 0.06, 0.10, 0.44, 0.42, 0.48, 0.60, 0.66, 0.50, 0.92, 0.18, 0.98, 0.08, 0.84))
            '6' -> glyph(0.70, s(0.58, 0.14, 0.28, 0.06, 0.10, 0.36, 0.12, 0.76, 0.30, 0.98, 0.56, 0.88, 0.58, 0.62, 0.38, 0.50, 0.12, 0.58))
            '7' -> glyph(0.66, s(0.08, 0.06, 0.62, 0.06, 0.24, 0.98))
            '8' -> glyph(0.70, oval(0.35, 0.28, 0.24, 0.22), oval(0.35, 0.74, 0.28, 0.25))
            '9' -> glyph(0.70, s(0.56, 0.50, 0.32, 0.52, 0.12, 0.38, 0.16, 0.12, 0.42, 0.04, 0.60, 0.24, 0.56, 0.70, 0.36, 0.98, 0.12, 0.88))
            ' ' -> glyph(0.36)
            '-' -> glyph(0.50, s(0.10, 0.54, 0.42, 0.54))
            '_' -> glyph(0.56, s(0.08, 1.04, 0.50, 1.04))
            '/' -> glyph(0.54, s(0.48, 0.04, 0.08, 1.0))
            '\\' -> glyph(0.54, s(0.08, 0.04, 0.48, 1.0))
            '.' -> lowerGlyph(0.24, dot(0.13, 0.92, 0.052), dot(0.13, 0.92, 0.030), dot(0.15, 0.98, 0.024))
            ',' -> lowerGlyph(0.28, dot(0.13, 0.88, 0.045), s(0.14, 0.90, 0.17, 1.01, 0.13, 1.12))
            ':' -> glyph(0.26, dot(0.13, 0.34, 0.035), dot(0.13, 0.82, 0.035))
            '?' -> lowerGlyph(0.60, s(0.12, 0.22, 0.26, 0.08, 0.50, 0.10, 0.58, 0.26, 0.54, 0.42, 0.42, 0.52, 0.36, 0.62, 0.36, 0.74), dot(0.30, 0.92, 0.042))
            '!' -> lowerGlyph(0.26, s(0.14, 0.10, 0.14, 0.68), dot(0.14, 0.92, 0.042))
            '¡' -> glyph(0.26, dot(0.14, 0.18, 0.042), s(0.14, 0.34, 0.14, 0.92))
            '¿' -> lowerGlyph(0.60, dot(0.30, 0.10, 0.042), s(0.52, 0.22, 0.38, 0.08, 0.18, 0.10, 0.10, 0.26, 0.14, 0.42, 0.26, 0.52, 0.32, 0.62, 0.32, 0.74))
            '"' -> lowerGlyph(0.44, s(0.09, 0.10, 0.07, 0.22, 0.09, 0.34), s(0.26, 0.10, 0.24, 0.22, 0.26, 0.34))
            '“' -> lowerGlyph(0.44, s(0.11, 0.18, 0.08, 0.30, 0.12, 0.36), s(0.28, 0.18, 0.26, 0.30, 0.30, 0.36))
            '”' -> lowerGlyph(0.44, s(0.09, 0.10, 0.13, 0.22, 0.09, 0.34), s(0.26, 0.10, 0.30, 0.22, 0.26, 0.34))
            '\'' -> lowerGlyph(0.22, s(0.14, 0.08, 0.11, 0.18, 0.13, 0.30))
            '‘' -> lowerGlyph(0.22, s(0.15, 0.18, 0.11, 0.30, 0.08, 0.38))
            '’' -> lowerGlyph(0.22, s(0.12, 0.08, 0.15, 0.18, 0.11, 0.30))
            ';' -> lowerGlyph(0.30, dot(0.13, 0.34, 0.035), s(0.14, 0.90, 0.17, 1.00, 0.14, 1.10))
            '(' -> glyph(0.38, s(0.28, 0.06, 0.12, 0.34, 0.12, 0.66, 0.28, 0.96))
            ')' -> glyph(0.38, s(0.10, 0.06, 0.26, 0.34, 0.26, 0.66, 0.10, 0.96))
            '[' -> glyph(0.40, s(0.30, 0.04, 0.12, 0.04, 0.12, 0.96, 0.30, 0.96))
            ']' -> glyph(0.40, s(0.10, 0.04, 0.28, 0.04, 0.28, 0.96, 0.10, 0.96))
            'a' -> lowerGlyph(0.66, s(0.54, 0.42, 0.40, 0.32, 0.18, 0.38, 0.10, 0.62, 0.16, 0.88, 0.42, 0.92, 0.56, 0.72, 0.56, 0.38, 0.56, 0.92))
            'b' -> lowerGlyph(0.68, s(0.10, 0.06, 0.10, 0.94), s(0.10, 0.48, 0.28, 0.30, 0.54, 0.38, 0.60, 0.64, 0.54, 0.88, 0.28, 0.96, 0.10, 0.78))
            'c' -> lowerGlyph(0.58, s(0.50, 0.42, 0.36, 0.32, 0.16, 0.40, 0.10, 0.64, 0.16, 0.86, 0.38, 0.94, 0.52, 0.84))
            'd' -> lowerGlyph(0.68, s(0.56, 0.06, 0.56, 0.94), s(0.56, 0.48, 0.38, 0.30, 0.14, 0.40, 0.08, 0.66, 0.16, 0.90, 0.42, 0.94, 0.56, 0.74))
            'e' -> lowerGlyph(0.60, s(0.12, 0.66, 0.52, 0.62, 0.48, 0.42, 0.30, 0.34, 0.12, 0.46, 0.10, 0.70, 0.24, 0.92, 0.52, 0.84))
            'f' -> lowerGlyph(0.46, s(0.34, 0.06, 0.22, 0.08, 0.18, 0.30, 0.18, 0.96), s(0.08, 0.42, 0.36, 0.42))
            'g' -> lowerGlyph(0.66, s(0.54, 0.42, 0.38, 0.30, 0.14, 0.40, 0.08, 0.66, 0.16, 0.90, 0.42, 0.94, 0.56, 0.72, 0.56, 0.36, 0.56, 1.10, 0.36, 1.22, 0.16, 1.14))
            'h' -> lowerGlyph(0.66, s(0.10, 0.06, 0.10, 0.94), s(0.10, 0.54, 0.26, 0.34, 0.50, 0.38, 0.56, 0.62, 0.56, 0.94))
            'i' -> lowerGlyph(0.30, s(0.15, 0.38, 0.15, 0.94), dot(0.15, 0.17))
            'j' -> lowerGlyph(0.34, s(0.22, 0.38, 0.22, 1.06, 0.10, 1.20, 0.02, 1.12), dot(0.22, 0.17))
            'k' -> lowerGlyph(0.62, s(0.10, 0.06, 0.10, 0.94), s(0.52, 0.38, 0.10, 0.66, 0.56, 0.94))
            'l' -> lowerGlyph(0.30, s(0.15, 0.06, 0.15, 0.94))
            'm' -> lowerGlyph(0.94, s(0.10, 0.94, 0.10, 0.38, 0.30, 0.36, 0.38, 0.58, 0.38, 0.94), s(0.38, 0.54, 0.56, 0.34, 0.76, 0.40, 0.82, 0.64, 0.82, 0.94))
            'n' -> lowerGlyph(0.66, s(0.10, 0.94, 0.10, 0.38, 0.28, 0.34, 0.50, 0.40, 0.56, 0.64, 0.56, 0.94))
            'o' -> lowerGlyph(0.64, oval(0.32, 0.66, 0.24, 0.28, startAngle = -PI / 2.0))
            'p' -> lowerGlyph(0.68, s(0.10, 1.20, 0.10, 0.38), s(0.10, 0.48, 0.28, 0.30, 0.54, 0.38, 0.60, 0.64, 0.54, 0.88, 0.28, 0.96, 0.10, 0.78))
            'q' -> lowerGlyph(0.68, s(0.56, 1.20, 0.56, 0.38), s(0.56, 0.48, 0.38, 0.30, 0.14, 0.40, 0.08, 0.66, 0.16, 0.90, 0.42, 0.94, 0.56, 0.74))
            'r' -> lowerGlyph(0.50, s(0.10, 0.94, 0.10, 0.38, 0.28, 0.34, 0.44, 0.42))
            's' -> lowerGlyph(0.56, s(0.48, 0.42, 0.32, 0.32, 0.12, 0.42, 0.20, 0.62, 0.44, 0.72, 0.42, 0.90, 0.16, 0.92, 0.08, 0.82))
            't' -> lowerGlyph(0.46, s(0.22, 0.18, 0.22, 0.82, 0.34, 0.94, 0.44, 0.88), s(0.08, 0.42, 0.38, 0.42))
            'u' -> lowerGlyph(0.66, s(0.10, 0.38, 0.10, 0.76, 0.18, 0.94, 0.40, 0.94, 0.56, 0.74, 0.56, 0.38))
            'v' -> lowerGlyph(0.58, s(0.08, 0.40, 0.30, 0.94, 0.54, 0.40))
            'w' -> lowerGlyph(0.86, s(0.06, 0.40, 0.20, 0.94, 0.42, 0.56, 0.64, 0.94, 0.80, 0.40))
            'x' -> lowerGlyph(0.58, s(0.10, 0.40, 0.52, 0.94), s(0.52, 0.40, 0.10, 0.94))
            'y' -> lowerGlyph(0.62, s(0.08, 0.40, 0.32, 0.94, 0.56, 0.40, 0.38, 1.12, 0.18, 1.20))
            'z' -> lowerGlyph(0.56, s(0.10, 0.40, 0.50, 0.40, 0.10, 0.94, 0.52, 0.94))
            else -> glyph(0.68, s(0.12, 0.22, 0.28, 0.04, 0.54, 0.12, 0.56, 0.34, 0.34, 0.54, 0.34, 0.72), s(0.34, 0.94, 0.35, 0.94))
        }
    }

    private fun glyph(advance: Double, vararg strokes: List<VectorPoint>): VectorGlyph {
        return VectorGlyph(advance, strokes.toList())
    }

    private fun lowerGlyph(advance: Double, vararg strokes: List<VectorPoint>): VectorGlyph {
        return VectorGlyph(advance, strokes.toList(), normalizeHeight = false)
    }

    private fun s(vararg coordinates: Double): List<VectorPoint> {
        val points = coordinates.asList()
            .chunked(2)
            .map { (x, y) -> VectorPoint(x, y) }
        return roundedPolyline(points)
    }

    private fun dot(centerX: Double, centerY: Double): List<VectorPoint> {
        return dot(centerX, centerY, radius = 0.05)
    }

    private fun dot(centerX: Double, centerY: Double, radius: Double): List<VectorPoint> {
        return oval(centerX, centerY, radiusX = radius, radiusY = radius)
    }

    private fun oval(
        centerX: Double,
        centerY: Double,
        radiusX: Double,
        radiusY: Double,
        startAngle: Double = 0.0
    ): List<VectorPoint> {
        val steps = 28
        return (0..steps).map { index ->
            val angle = startAngle + PI * 2.0 * index / steps.toDouble()
            VectorPoint(
                x = centerX + cos(angle) * radiusX,
                y = centerY + sin(angle) * radiusY
            )
        }
    }

    private fun roundedPolyline(points: List<VectorPoint>): List<VectorPoint> {
        if (points.size < 3) return points

        val rounded = mutableListOf(points.first())
        for (index in 1 until points.lastIndex) {
            val previous = points[index - 1]
            val current = points[index]
            val next = points[index + 1]

            if (current.distanceTo(previous) < 0.08 || current.distanceTo(next) < 0.08) {
                rounded += current
                continue
            }

            val entry = current.toward(previous, DRAWN_CORNER_ROUNDING)
            val exit = current.toward(next, DRAWN_CORNER_ROUNDING)
            rounded += entry
            for (step in 1..DRAWN_CORNER_CURVE_STEPS) {
                val t = step / (DRAWN_CORNER_CURVE_STEPS + 1.0)
                rounded += quadraticPoint(entry, current, exit, t)
            }
            rounded += exit
        }
        rounded += points.last()
        return rounded
    }

    private fun quadraticPoint(start: VectorPoint, control: VectorPoint, end: VectorPoint, t: Double): VectorPoint {
        val inverse = 1.0 - t
        return VectorPoint(
            x = inverse * inverse * start.x + 2.0 * inverse * t * control.x + t * t * end.x,
            y = inverse * inverse * start.y + 2.0 * inverse * t * control.y + t * t * end.y
        )
    }

    private data class DrawnTextLayout(val fontSize: Double, val lines: List<String>)
    private data class NormalizedGlyph(val base: Char, val marks: String)
    private data class VectorGlyph(
        val advance: Double,
        val strokes: List<List<VectorPoint>> = emptyList(),
        val normalizeHeight: Boolean = true
    )
    private data class VectorPoint(val x: Double, val y: Double) {
        fun distanceTo(other: VectorPoint): Double {
            return hypot(x - other.x, y - other.y)
        }

        fun toward(other: VectorPoint, ratio: Double): VectorPoint {
            return VectorPoint(
                x = x + (other.x - x) * ratio,
                y = y + (other.y - y) * ratio
            )
        }
    }
}
