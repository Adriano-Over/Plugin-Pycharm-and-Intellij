package com.floatbar.geometry

import com.floatbar.BalloonTextStyle
import com.floatbar.BalloonTextStrokeFactory
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.Rectangle

class BalloonTextStrokeFactoryTest {
    @Test
    fun `typed balloon text defaults to filled drawable strokes`() {
        val bounds = Rectangle(10, 10, 140, 60)
        val strokes = BalloonTextStrokeFactory.buildTextStrokes(
            text = "Hello",
            bounds = bounds,
            color = Color.BLACK
        )
        val points = strokes.flatMap { it.points }

        assertTrue(strokes.isNotEmpty(), "Balloon text should create drawable strokes")
        assertTrue(points.isNotEmpty(), "Balloon text strokes should contain points")
        assertTrue(strokes.all { !it.filled }, "Balloon text should remain erasable outline strokes")
        assertTrue(strokes.all { it.kind == null }, "Balloon text should not masquerade as a separate shape")
        assertTrue(points.minOf { it.dx } >= bounds.x - 4, "Text should stay near the requested bounds")
        assertTrue(points.maxOf { it.dx } <= bounds.x + bounds.width + 4, "Text should stay near the requested bounds")
        assertTrue(points.minOf { it.dy } >= bounds.y - 4, "Text should stay near the requested bounds")
        assertTrue(points.maxOf { it.dy } <= bounds.y + bounds.height + 4, "Text should stay near the requested bounds")
    }

    @Test
    fun `hollow balloon text remains available as outline strokes`() {
        val bounds = Rectangle(10, 10, 140, 60)
        val strokes = BalloonTextStrokeFactory.buildTextStrokes(
            text = "Hello",
            bounds = bounds,
            color = Color.BLACK,
            style = BalloonTextStyle.OUTLINE
        )
        val points = strokes.flatMap { it.points }

        assertTrue(strokes.isNotEmpty(), "Hollow balloon text should create drawable outline strokes")
        assertTrue(points.isNotEmpty(), "Hollow balloon text strokes should contain points")
        assertTrue(strokes.all { !it.filled }, "Hollow text should remain erasable outline strokes")
        assertTrue(strokes.all { it.kind == null }, "Hollow text should not masquerade as a separate shape")
    }

    @Test
    fun `drawn balloon text becomes single-line drawing strokes`() {
        val bounds = Rectangle(10, 10, 160, 70)
        val filledStrokes = BalloonTextStrokeFactory.buildTextStrokes(
            text = "Nome",
            bounds = bounds,
            color = Color.BLUE,
            style = BalloonTextStyle.SOLID
        )

        assertTrue(filledStrokes.isNotEmpty(), "Drawn balloon text should create drawable strokes")
        assertTrue(filledStrokes.size in 4..16, "Drawn style should use a small set of pen strokes, not fill stripes")
        assertTrue(filledStrokes.all { !it.filled && it.kind == null }, "Drawn text should still save as normal strokes")
        assertTrue(filledStrokes.any { it.points.size > 2 }, "Drawn text should include continuous letter paths")
        assertTrue(filledStrokes.all { it.width >= 2.2f }, "Drawn text should use a visible pen width")
    }

    @Test
    fun `filled multiline text keeps a visible line gap`() {
        val bounds = Rectangle(10, 10, 180, 120)
        val strokes = BalloonTextStrokeFactory.buildTextStrokes(
            text = "A\nA",
            bounds = bounds,
            color = Color.BLUE
        )
        val points = strokes.flatMap { it.points }
        val midpointY = (points.minOf { it.dy } + points.maxOf { it.dy }) / 2
        val topLineBottom = points.filter { it.dy < midpointY }.maxOf { it.dy }
        val bottomLineTop = points.filter { it.dy > midpointY }.minOf { it.dy }

        assertTrue(
            bottomLineTop - topLineBottom >= 2,
            "Filled multiline text should leave at least the requested 2 px line gap"
        )
    }

    @Test
    fun `drawn text keeps letters on consistent top and baseline`() {
        val letters = "NOMETSAIBURGHDF".toList()

        val verticalRanges = letters.map { letter ->
            val strokes = BalloonTextStrokeFactory.buildTextStrokes(
                text = letter.toString(),
                bounds = Rectangle(10, 10, 80, 80),
                color = Color.BLUE,
                style = BalloonTextStyle.SOLID
            )
            val points = strokes.flatMap { it.points }

            assertTrue(points.isNotEmpty(), "$letter should create drawable letter strokes")
            points.minOf { it.dy } to points.maxOf { it.dy }
        }

        assertTrue(
            verticalRanges.maxOf { it.first } - verticalRanges.minOf { it.first } <= 5,
            "Drawn letters should share a consistent top line"
        )
        assertTrue(
            verticalRanges.maxOf { it.second } - verticalRanges.minOf { it.second } <= 5,
            "Drawn letters should share a consistent baseline"
        )
    }

    @Test
    fun `drawn text preserves lowercase instead of converting everything to caps`() {
        val bounds = Rectangle(10, 10, 220, 80)
        val mixedCase = BalloonTextStrokeFactory.buildTextStrokes(
            text = "Nome",
            bounds = bounds,
            color = Color.BLUE,
            style = BalloonTextStyle.SOLID
        ).flatMap { it.points }
        val upperCase = BalloonTextStrokeFactory.buildTextStrokes(
            text = "NOME",
            bounds = bounds,
            color = Color.BLUE,
            style = BalloonTextStyle.SOLID
        ).flatMap { it.points }

        val mixedCaseWidth = mixedCase.maxOf { it.dx } - mixedCase.minOf { it.dx }
        val upperCaseWidth = upperCase.maxOf { it.dx } - upperCase.minOf { it.dx }

        assertTrue(mixedCase.isNotEmpty(), "Mixed-case drawn text should create strokes")
        assertTrue(upperCase.isNotEmpty(), "Uppercase drawn text should create strokes")
        assertTrue(mixedCaseWidth < upperCaseWidth, "Lowercase letters should not be converted to uppercase shapes")
    }

    @Test
    fun `filled lowercase i keeps a visible rounded dot`() {
        val bounds = Rectangle(10, 10, 80, 80)
        val strokes = BalloonTextStrokeFactory.buildTextStrokes(
            text = "i",
            bounds = bounds,
            color = Color.BLUE,
            style = BalloonTextStyle.SOLID
        )

        val dotStroke = strokes
            .filter { stroke -> stroke.points.maxOf { it.dy } < bounds.y + bounds.height / 2 }
            .maxByOrNull { stroke -> stroke.points.size }

        assertTrue(strokes.size >= 2, "Lowercase i should have a stem and a separate dot stroke")
        assertTrue(dotStroke != null, "Lowercase i should include a dot above the stem")
        assertTrue(dotStroke!!.points.size >= 8, "Lowercase i dot should be rounded, not a collapsed one-pixel point")
        assertTrue(
            dotStroke.points.maxOf { it.dx } - dotStroke.points.minOf { it.dx } >= 2,
            "Lowercase i dot should have visible width"
        )
        assertTrue(
            dotStroke.points.maxOf { it.dy } - dotStroke.points.minOf { it.dy } >= 2,
            "Lowercase i dot should have visible height"
        )
    }

    @Test
    fun `filled accents sit close to their base letter`() {
        val bounds = Rectangle(10, 10, 90, 90)
        val base = BalloonTextStrokeFactory.buildTextStrokes(
            text = "a",
            bounds = bounds,
            color = Color.BLUE,
            style = BalloonTextStyle.SOLID
        )
        val accented = BalloonTextStrokeFactory.buildTextStrokes(
            text = "\u00E1",
            bounds = bounds,
            color = Color.BLUE,
            style = BalloonTextStyle.SOLID
        )

        val baseTop = base.flatMap { it.points }.minOf { it.dy }
        val accentStroke = accented
            .filter { stroke -> stroke.points.maxOf { it.dy } < baseTop }
            .maxByOrNull { stroke -> stroke.points.size }

        assertTrue(accentStroke != null, "Accented filled text should include a separate accent stroke")
        assertTrue(
            baseTop - accentStroke!!.points.maxOf { it.dy } <= 14,
            "Accent should sit close to the base letter instead of floating too high"
        )
    }

    @Test
    fun `filled punctuation stays visible and shaped like handwritten marks`() {
        val bounds = Rectangle(10, 10, 180, 90)
        val strokes = BalloonTextStrokeFactory.buildTextStrokes(
            text = ".,:\"';",
            bounds = bounds,
            color = Color.BLUE,
            style = BalloonTextStyle.SOLID
        )
        val points = strokes.flatMap { it.points }

        assertTrue(strokes.isNotEmpty(), "Punctuation should still produce drawable strokes")
        assertTrue(points.isNotEmpty(), "Punctuation should contain drawable points")
        assertTrue(strokes.count { it.points.size >= 4 } >= 3, "Punctuation should include rounded or slanted marks, not just single pixels")
        assertTrue(points.maxOf { it.dy } - points.minOf { it.dy } >= 30, "Punctuation should spread over a readable vertical span")
    }

    @Test
    fun `dot and comma remain visible as separate punctuation marks`() {
        val bounds = Rectangle(10, 10, 100, 80)
        val strokes = BalloonTextStrokeFactory.buildTextStrokes(
            text = ".,",
            bounds = bounds,
            color = Color.BLUE,
            style = BalloonTextStyle.SOLID
        )

        assertTrue(strokes.size >= 2, "Dot and comma should each produce a visible stroke group")
        assertTrue(
            strokes.flatMap { it.points }.maxOf { it.dy } - strokes.flatMap { it.points }.minOf { it.dy } >= 4,
            "Dot and comma should occupy a readable vertical span"
        )
        assertTrue(
            strokes.flatMap { it.points }.maxOf { it.dy } - strokes.flatMap { it.points }.minOf { it.dy } <= 18,
            "Dot and comma should stay compact instead of stretching like letters"
        )
    }

    @Test
    fun `opening and closing quotes are distinct readable marks`() {
        val bounds = Rectangle(10, 10, 180, 90)
        val strokes = BalloonTextStrokeFactory.buildTextStrokes(
            text = "\u0022\u0027\u201C\u201D\u2018\u2019",
            bounds = bounds,
            color = Color.BLUE,
            style = BalloonTextStyle.SOLID
        )

        assertTrue(strokes.size >= 6, "Each quote mark should generate its own readable stroke group")
        assertTrue(strokes.all { it.points.size >= 3 }, "Quote marks should not collapse into single-point fallbacks")
    }

    @Test
    fun `smart quotes and portuguese accents render together without fallback glyphs`() {
        val bounds = Rectangle(10, 10, 320, 120)
        val strokes = BalloonTextStrokeFactory.buildTextStrokes(
            text = "\u201C\u00C0s 3h, vov\u00F3 disse: \u2018Jo\u00E3o, p\u00F5e a\u00E7\u00FAcar, caf\u00E9, ma\u00E7\u00E3, b\u00EAn\u00E7\u00E3o\u2019",
            bounds = bounds,
            color = Color.BLUE,
            style = BalloonTextStyle.SOLID
        )
        val points = strokes.flatMap { it.points }

        assertTrue(strokes.isNotEmpty(), "The full Portuguese sentence should create strokes")
        assertTrue(points.isNotEmpty(), "The full Portuguese sentence should contain points")
        assertTrue(strokes.count { it.points.size >= 4 } >= 10, "The sentence should contain multiple readable glyph strokes")
        assertTrue(points.minOf { it.dx } >= bounds.x - 4, "The sentence should stay inside the target bounds")
        assertTrue(points.maxOf { it.dx } <= bounds.x + bounds.width + 4, "The sentence should stay inside the target bounds")
        assertTrue(points.maxOf { it.dy } <= bounds.y + bounds.height + 4, "The sentence should stay inside the target bounds")
    }

    @Test
    fun `blank balloon text creates no strokes`() {
        val strokes = BalloonTextStrokeFactory.buildTextStrokes(
            text = "   ",
            bounds = Rectangle(10, 10, 140, 60),
            color = Color.BLACK
        )

        assertTrue(strokes.isEmpty(), "Blank balloon text should not create strokes")
    }
}

