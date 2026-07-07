package com.drawing.geometry

import com.drawing.AnchorPoint
import com.drawing.AnnotationImageCache
import com.drawing.AnnotationKind
import com.drawing.AnnotationPath
import com.drawing.AnnotationRenderer
import com.drawing.BalloonTextStyle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Color

class AnnotationRendererTest {
    @Test
    fun `annotation path deep copy preserves semantic fields`() {
        val annotation = annotation(kind = AnnotationKind.BALLOON, style = BalloonTextStyle.OUTLINE)

        val copy = annotation.deepCopy()

        assertEquals(annotation, copy)
        assertNotSame(annotation.anchor, copy.anchor)
        assertEquals(Color.BLUE, copy.color)
    }

    @Test
    fun `renderer emits non-empty text and balloon images`() {
        val textImage = AnnotationRenderer.render(annotation(kind = AnnotationKind.TEXT))
        val balloonImage = AnnotationRenderer.render(annotation(kind = AnnotationKind.BALLOON))

        assertTrue(hasVisiblePixel(textImage), "Text annotation should render visible pixels")
        assertTrue(hasVisiblePixel(balloonImage), "Balloon annotation should render visible pixels")
    }

    @Test
    fun `balloon renderer tail points to lower-left like the preview shape`() {
        val image = AnnotationRenderer.render(annotation(kind = AnnotationKind.BALLOON))
        val lowestVisibleY = (image.height - 1 downTo 0).first { y ->
            (0 until image.width).any { x -> image.visibleAt(x, y) }
        }
        val lowestVisibleXs = (0 until image.width).filter { x -> image.visibleAt(x, lowestVisibleY) }
        val tailCenterX = lowestVisibleXs.average()

        assertTrue(
            tailCenterX < image.width / 2.0,
            "Committed balloon tail should point to the lower-left side, matching the preview"
        )
    }

    @Test
    fun `image cache reuses unchanged annotation and invalidates by id`() {
        val cache = AnnotationImageCache()
        val annotation = annotation(kind = AnnotationKind.TEXT)

        val first = cache.getOrRender(annotation)
        val second = cache.getOrRender(annotation)
        cache.invalidate(annotation.id)
        val third = cache.getOrRender(annotation)

        assertSame(first, second)
        assertNotSame(first, third)
    }

    private fun annotation(
        kind: AnnotationKind,
        style: BalloonTextStyle = BalloonTextStyle.SOLID
    ): AnnotationPath {
        return AnnotationPath(
            id = 42L,
            text = "Hello annotation",
            color = Color.BLUE,
            anchor = AnchorPoint(line = 1, column = 2, dx = 3, dy = 4, offset = 5),
            width = 180,
            height = 70,
            kind = kind,
            style = style,
            objectGroupId = 42L
        )
    }

    private fun hasVisiblePixel(image: java.awt.image.BufferedImage): Boolean {
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                if (image.visibleAt(x, y)) return true
            }
        }
        return false
    }

    private fun java.awt.image.BufferedImage.visibleAt(x: Int, y: Int): Boolean {
        return (getRGB(x, y) ushr 24) != 0
    }
}
