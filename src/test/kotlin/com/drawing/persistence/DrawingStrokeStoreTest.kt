package com.drawing.persistence

import com.drawing.AnchorPoint
import com.drawing.AnnotationKind
import com.drawing.AnnotationPath
import com.drawing.BalloonTextStyle
import com.drawing.DrawingStrokeStore
import com.drawing.DrawingStateService
import com.drawing.RasterFillCodec
import com.drawing.RasterFillPath
import com.drawing.SavedFileDrawing
import com.drawing.SavedPoint
import com.drawing.SavedRasterFill
import com.drawing.ShapeKind
import com.drawing.StrokePath
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage

class DrawingStrokeStoreTest {
    private val projectBasePath = "C:/work/drawing-project"
    private val filePath = "$projectBasePath/src/Main.kt"

    @Test
    fun `persistStrokes stores modern anchors and loadPersistedStrokes restores runtime strokes`() {
        val service = DrawingStateService(testProject(projectBasePath))
        val store = DrawingStrokeStore(service)
        val document = testDocument()
        val stroke = StrokePath(
            color = Color(12, 34, 56, 160),
            width = 6.25f,
            points = mutableListOf(
                AnchorPoint(
                    line = 3,
                    column = 5,
                    dx = 7,
                    dy = 9,
                    offset = 11,
                    outsideCode = true,
                    afterLineEndPx = 13,
                    foldHiddenHeightAbove = 15
                )
            ),
            filled = true,
            kind = ShapeKind.RECTANGLE,
            objectGroupId = 99L,
            rigidObjectAnchor = true
        )

        store.persistStrokes(filePath, listOf(stroke))

        val saved = service.getStrokes(filePath).single()
        assertEquals(Color(12, 34, 56, 160).rgb, saved.color)
        assertEquals(6.25f, saved.width)
        assertEquals(true, saved.filled)
        assertEquals(ShapeKind.RECTANGLE.name, saved.kind)
        assertEquals(99L, saved.objectGroupId)
        assertEquals(true, saved.rigidObjectAnchor)
        assertEquals(15, saved.points.single().foldHiddenHeightAbove)

        var normalizedAnchors = 0
        val loaded = store.loadPersistedStrokes(filePath, document) { _, anchor ->
            normalizedAnchors += 1
            anchor.column += 1
        }

        assertEquals(1, normalizedAnchors)
        assertEquals(1, loaded.size)
        assertNotEquals(stroke.id, loaded.single().id, "Persisted strokes are restored with fresh runtime ids")
        assertEquals(Color(12, 34, 56, 160), loaded.single().color)
        assertEquals(6.25f, loaded.single().width)
        assertEquals(true, loaded.single().filled)
        assertEquals(ShapeKind.RECTANGLE, loaded.single().kind)
        assertEquals(99L, loaded.single().objectGroupId)
        assertEquals(true, loaded.single().rigidObjectAnchor)
        assertEquals(6, loaded.single().points.single().column)
        assertEquals(7, loaded.single().points.single().dx)
        assertEquals(9, loaded.single().points.single().dy)
        assertEquals(11, loaded.single().points.single().offset)
        assertEquals(true, loaded.single().points.single().outsideCode)
        assertEquals(13, loaded.single().points.single().afterLineEndPx)
        assertEquals(15, loaded.single().points.single().foldHiddenHeightAbove)
        assertEquals(loaded, store.currentStrokes(document))
    }

    @Test
    fun `raster fills round trip through drawing persistence`() {
        val service = DrawingStateService(testProject(projectBasePath))
        val store = DrawingStrokeStore(service)
        val document = testDocument()
        val image = BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB)
        image.setRGB(0, 0, Color.GREEN.rgb)
        image.setRGB(1, 1, Color(0, 255, 0, 120).rgb)
        val fill = RasterFillPath(
            id = 123L,
            color = Color.GREEN,
            anchor = AnchorPoint(
                line = 4,
                column = 9,
                dx = 20,
                dy = 30,
                offset = 40,
                outsideCode = true,
                afterLineEndPx = 50,
                foldHiddenHeightAbove = 60
            ),
            width = 2,
            height = 2,
            pngBase64 = RasterFillCodec.encodePngBase64(image),
            objectGroupId = 456L
        )

        store.persistDrawing(filePath, emptyList(), listOf(fill))

        val saved = service.getRasterFills(filePath).single()
        assertEquals(123L, saved.id)
        assertEquals(Color.GREEN.rgb, saved.color)
        assertEquals(2, saved.width)
        assertEquals(2, saved.height)
        assertEquals(456L, saved.objectGroupId)
        assertEquals(60, saved.anchor.foldHiddenHeightAbove)

        val loadedStrokes = store.loadPersistedStrokes(filePath, document) { _, anchor ->
            anchor.column += 1
        }
        val loadedFill = store.currentRasterFills(document).single()

        assertEquals(emptyList<StrokePath>(), loadedStrokes)
        assertEquals(123L, loadedFill.id)
        assertEquals(Color.GREEN, loadedFill.color)
        assertEquals(2, loadedFill.width)
        assertEquals(2, loadedFill.height)
        assertEquals(456L, loadedFill.objectGroupId)
        assertEquals(10, loadedFill.anchor.column)
        assertEquals(20, loadedFill.anchor.dx)
        assertEquals(30, loadedFill.anchor.dy)
        assertEquals(40, loadedFill.anchor.offset)
        assertEquals(true, loadedFill.anchor.outsideCode)
        assertEquals(50, loadedFill.anchor.afterLineEndPx)
        assertEquals(60, loadedFill.anchor.foldHiddenHeightAbove)
        assertEquals(Color.GREEN.rgb, RasterFillCodec.decodePngBase64(loadedFill.pngBase64).getRGB(0, 0))
    }

    @Test
    fun `loadPersistedStrokes skips unsupported raster fill payloads`() {
        val service = DrawingStateService(testProject(projectBasePath))
        val store = DrawingStrokeStore(service)
        val document = testDocument()
        val image = BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB)
        val validFill = SavedRasterFill(
            id = 123L,
            color = Color.GREEN.rgb,
            anchor = SavedPoint(line = 1, column = 2, dx = 3, dy = 4, offset = 5),
            width = 2,
            height = 2,
            pngBase64 = RasterFillCodec.encodePngBase64(image)
        )
        val oversizedFill = SavedRasterFill(
            id = 456L,
            color = Color.RED.rgb,
            anchor = SavedPoint(line = 1, column = 2, dx = 3, dy = 4, offset = 5),
            width = 20_000,
            height = 20_000,
            pngBase64 = RasterFillCodec.encodePngBase64(image)
        )
        val invalidBase64Fill = SavedRasterFill(
            id = 789L,
            color = Color.BLUE.rgb,
            anchor = SavedPoint(line = 1, column = 2, dx = 3, dy = 4, offset = 5),
            width = 2,
            height = 2,
            pngBase64 = "not valid base64"
        )
        service.loadState(
            service.state.copy(
                files = mutableListOf(
                    SavedFileDrawing(
                        filePath = "\$PROJECT_DIR$/src/Main.kt",
                        rasterFills = mutableListOf(validFill, oversizedFill, invalidBase64Fill)
                    )
                )
            )
        )

        store.loadPersistedStrokes(filePath, document) { _, _ -> }

        assertEquals(listOf(123L), store.currentRasterFills(document).map { it.id })
    }

    @Test
    fun `annotations round trip through drawing persistence`() {
        val service = DrawingStateService(testProject(projectBasePath))
        val store = DrawingStrokeStore(service)
        val document = testDocument()
        val annotation = AnnotationPath(
            id = 321L,
            text = "Semantic label",
            color = Color.MAGENTA,
            anchor = AnchorPoint(
                line = 6,
                column = 3,
                dx = 44,
                dy = 12,
                offset = 60,
                outsideCode = true,
                afterLineEndPx = 7,
                foldHiddenHeightAbove = 9
            ),
            width = 160,
            height = 48,
            kind = AnnotationKind.BALLOON,
            style = BalloonTextStyle.OUTLINE,
            objectGroupId = 654L
        )

        store.persistDrawing(filePath, emptyList(), emptyList(), listOf(annotation))

        val saved = service.getAnnotations(filePath).single()
        assertEquals(321L, saved.id)
        assertEquals("Semantic label", saved.text)
        assertEquals(Color.MAGENTA.rgb, saved.color)
        assertEquals(160, saved.width)
        assertEquals(48, saved.height)
        assertEquals(AnnotationKind.BALLOON.name, saved.kind)
        assertEquals(BalloonTextStyle.OUTLINE.name, saved.style)
        assertEquals(654L, saved.objectGroupId)

        val loadedStrokes = store.loadPersistedStrokes(filePath, document) { _, anchor ->
            anchor.column += 2
        }
        val loadedAnnotation = store.currentAnnotations(document).single()

        assertEquals(emptyList<StrokePath>(), loadedStrokes)
        assertEquals(321L, loadedAnnotation.id)
        assertEquals("Semantic label", loadedAnnotation.text)
        assertEquals(Color.MAGENTA, loadedAnnotation.color)
        assertEquals(5, loadedAnnotation.anchor.column)
        assertEquals(44, loadedAnnotation.anchor.dx)
        assertEquals(12, loadedAnnotation.anchor.dy)
        assertEquals(60, loadedAnnotation.anchor.offset)
        assertEquals(true, loadedAnnotation.anchor.outsideCode)
        assertEquals(7, loadedAnnotation.anchor.afterLineEndPx)
        assertEquals(9, loadedAnnotation.anchor.foldHiddenHeightAbove)
        assertEquals(160, loadedAnnotation.width)
        assertEquals(48, loadedAnnotation.height)
        assertEquals(AnnotationKind.BALLOON, loadedAnnotation.kind)
        assertEquals(BalloonTextStyle.OUTLINE, loadedAnnotation.style)
        assertEquals(654L, loadedAnnotation.objectGroupId)
    }

    @Test
    fun `mixed strokes raster fills text and balloons round trip together`() {
        val service = DrawingStateService(testProject(projectBasePath))
        val store = DrawingStrokeStore(service)
        val document = testDocument()
        val image = BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB)
        image.setRGB(0, 0, Color.GREEN.rgb)
        val stroke = StrokePath(
            color = Color.CYAN,
            width = 4.5f,
            points = mutableListOf(
                AnchorPoint(line = 2, column = 10, dx = 24, dy = 4, offset = 80),
                AnchorPoint(line = 2, column = 10, dx = 84, dy = 34, offset = 80)
            ),
            kind = ShapeKind.RECTANGLE,
            objectGroupId = 700L,
            rigidObjectAnchor = true
        )
        val fill = RasterFillPath(
            id = 701L,
            color = Color.GREEN,
            anchor = AnchorPoint(line = 3, column = 8, dx = 40, dy = 10, offset = 110),
            width = 2,
            height = 2,
            pngBase64 = RasterFillCodec.encodePngBase64(image),
            objectGroupId = 700L
        )
        val text = AnnotationPath(
            id = 702L,
            text = "Text note",
            color = Color.ORANGE,
            anchor = AnchorPoint(line = 4, column = 5, dx = 50, dy = 12, offset = 140),
            width = 120,
            height = 42,
            kind = AnnotationKind.TEXT,
            style = BalloonTextStyle.SOLID,
            objectGroupId = 702L
        )
        val balloon = AnnotationPath(
            id = 703L,
            text = "Balloon note",
            color = Color.MAGENTA,
            anchor = AnchorPoint(line = 5, column = 5, dx = 60, dy = 14, offset = 180),
            width = 140,
            height = 64,
            kind = AnnotationKind.BALLOON,
            style = BalloonTextStyle.OUTLINE,
            objectGroupId = 703L
        )

        store.persistDrawing(filePath, listOf(stroke), listOf(fill), listOf(text, balloon))
        store.loadPersistedStrokes(filePath, document) { _, _ -> }

        assertEquals(listOf(ShapeKind.RECTANGLE), store.currentStrokes(document).map { it.kind })
        assertEquals(listOf(701L), store.currentRasterFills(document).map { it.id })
        assertEquals(
            listOf(AnnotationKind.TEXT, AnnotationKind.BALLOON),
            store.currentAnnotations(document).map { it.kind }
        )
        assertEquals(
            listOf("Text note", "Balloon note"),
            store.currentAnnotations(document).map { it.text }
        )
    }
}
