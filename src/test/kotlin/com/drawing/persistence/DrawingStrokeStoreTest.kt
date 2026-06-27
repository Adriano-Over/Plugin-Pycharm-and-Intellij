package com.drawing.persistence

import com.drawing.AnchorPoint
import com.drawing.BalloonTextStyle
import com.drawing.DrawingStrokeStore
import com.drawing.DrawingStateService
import com.drawing.SavedPoint
import com.drawing.SavedStroke
import com.drawing.ShapeKind
import com.drawing.StrokePath
import com.drawing.UNSET_FOLD_HIDDEN_HEIGHT_ABOVE
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.Rectangle

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
        assertEquals(3, saved.points.single().anchorStorageVersion)
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
    fun `loadPersistedStrokes maps legacy x anchors for older saved drawings`() {
        val service = DrawingStateService(testProject(projectBasePath))
        val store = DrawingStrokeStore(service)
        val document = testDocument()
        service.setStrokes(
            filePath,
            listOf(
                SavedStroke(
                    color = Color.YELLOW.rgb,
                    width = 3.5f,
                    points = mutableListOf(
                        SavedPoint(
                            anchorStorageVersion = 0,
                            line = 8,
                            dy = 21,
                            x = 34
                        )
                    )
                )
            )
        )

        val loadedPoint = store.loadPersistedStrokes(filePath, document) { _, _ -> }
            .single()
            .points
            .single()

        assertEquals(8, loadedPoint.line)
        assertEquals(0, loadedPoint.column)
        assertEquals(34, loadedPoint.dx)
        assertEquals(21, loadedPoint.dy)
        assertEquals(0, loadedPoint.offset)
        assertEquals(false, loadedPoint.outsideCode)
        assertEquals(0, loadedPoint.afterLineEndPx)
        assertEquals(UNSET_FOLD_HIDDEN_HEIGHT_ABOVE, loadedPoint.foldHiddenHeightAbove)
    }

    @Test
    fun `text annotation metadata round trips through persistence`() {
        val service = DrawingStateService(testProject(projectBasePath))
        val store = DrawingStrokeStore(service)
        val document = testDocument()
        val stroke = StrokePath(
            color = Color.CYAN,
            width = 3.5f,
            points = mutableListOf(
                AnchorPoint(line = 2, column = 5, dx = 11, dy = 13, offset = 17)
            ),
            kind = ShapeKind.TEXT,
            objectGroupId = 77L,
            annotationText = "Hello, Drawing",
            annotationTextStyle = BalloonTextStyle.OUTLINE,
            annotationBounds = Rectangle(40, 50, 180, 60)
        )

        store.persistStrokes(filePath, listOf(stroke))

        val saved = service.getStrokes(filePath).single()
        assertEquals("Hello, Drawing", saved.annotationText)
        assertEquals(BalloonTextStyle.OUTLINE.name, saved.annotationTextStyle)
        assertEquals(40, saved.annotationBoundsX)
        assertEquals(50, saved.annotationBoundsY)
        assertEquals(180, saved.annotationBoundsWidth)
        assertEquals(60, saved.annotationBoundsHeight)

        val loaded = store.loadPersistedStrokes(filePath, document) { _, anchor -> anchor }
        assertEquals("Hello, Drawing", loaded.single().annotationText)
        assertEquals(BalloonTextStyle.OUTLINE, loaded.single().annotationTextStyle)
        assertEquals(Rectangle(40, 50, 180, 60), loaded.single().annotationBounds)
    }
}
