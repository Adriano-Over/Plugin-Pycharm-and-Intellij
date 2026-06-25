package com.floatbar.document

import com.floatbar.AnchorPoint
import com.floatbar.DrawingCoordinateMapper
import com.floatbar.DrawingDocumentSync
import com.floatbar.DrawingViewportTools
import com.floatbar.DrawingStrokeStore
import com.floatbar.FloatBarDrawingStateService
import com.floatbar.SavedPoint
import com.floatbar.SavedStroke
import com.floatbar.ShapeKind
import com.floatbar.ShapeStrokeFactory
import com.floatbar.StrokePath
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.project.Project
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.Point
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import javax.swing.JPanel

class DrawingDocumentEditInteractionTest {
    private val mapper = DrawingCoordinateMapper(
        canvas = JPanel(),
        editorProvider = { null },
        minCodeClearancePx = 8
    )

    @Test
    fun `existing drawing follows code when a new line is inserted above it`() {
        val document = EditableTestDocument(
            """
            class Sample {
                fun target() {}
            }
            """.trimIndent()
        )
        val anchor = document.anchorAtLineEnd(line = 1, dx = 32, dy = 6)
        val stroke = strokeWith(anchor)

        val event = document.insert(document.lineStartOffset(1), "    val added = 1\n")

        mapper.remapAnchorsForDocumentChange(document.document, event, listOf(stroke))

        assertEquals(2, anchor.line, "Drawing anchored to target code should move down with that code")
        assertEquals(document.lineEndColumn(2), anchor.column)
        assertEquals(document.lineEndOffset(2), anchor.offset)
        assertEquals(32, anchor.dx, "Horizontal distance from the code edge should remain stable")
        assertEquals(6, anchor.dy, "Vertical distance inside the line should remain stable")
    }

    @Test
    fun `drawing at the end of a line stays attached when code is typed before it`() {
        val document = EditableTestDocument(
            """
            fun main() {
                println("a")
            }
            """.trimIndent()
        )
        val originalColumn = document.lineEndColumn(1)
        val anchor = document.anchorAtLineEnd(line = 1, dx = 20, dy = 3)
        val stroke = strokeWith(anchor)
        val insertedCode = " // keep drawing beside this longer line"

        val event = document.insert(document.lineEndOffset(1), insertedCode)

        mapper.remapAnchorsForDocumentChange(document.document, event, listOf(stroke))

        assertEquals(1, anchor.line)
        assertEquals(originalColumn + insertedCode.length, anchor.column)
        assertEquals(document.lineEndOffset(1), anchor.offset)
        assertEquals(20, anchor.dx)
    }

    @Test
    fun `drawing does not move when code is inserted after its anchor`() {
        val document = EditableTestDocument(
            """
            fun main() {
                println("a")
            }
            """.trimIndent()
        )
        val anchor = document.anchorAtLineEnd(line = 1, dx = 16, dy = 5)
        val originalLine = anchor.line
        val originalColumn = anchor.column
        val originalOffset = anchor.offset
        val stroke = strokeWith(anchor)

        val event = document.insert(document.lineStartOffset(2), "    println(\"below\")\n")

        mapper.remapAnchorsForDocumentChange(document.document, event, listOf(stroke))

        assertEquals(originalLine, anchor.line)
        assertEquals(originalColumn, anchor.column)
        assertEquals(originalOffset, anchor.offset)
    }

    @Test
    fun `drawing follows code upward when a line above it is deleted`() {
        val document = EditableTestDocument(
            """
            package sample
            fun helper() {}
            fun target() {}
            """.trimIndent()
        )
        val anchor = document.anchorAtLineEnd(line = 2, dx = 18, dy = 4)
        val stroke = strokeWith(anchor)

        val event = document.delete(0, document.lineStartOffset(1))

        mapper.remapAnchorsForDocumentChange(document.document, event, listOf(stroke))

        assertEquals(1, anchor.line, "Deleting a line above should pull the drawing up with target code")
        assertEquals(document.lineEndColumn(1), anchor.column)
        assertEquals(document.lineEndOffset(1), anchor.offset)
        assertEquals(18, anchor.dx)
    }

    @Test
    fun `drawing remaps to the end of replacement text when its original anchor is replaced`() {
        val document = EditableTestDocument(
            """
            val before = 1
            targetCall()
            afterCall()
            """.trimIndent()
        )
        val anchor = document.anchorAtLineEnd(line = 1, dx = 24, dy = 7)
        val stroke = strokeWith(anchor)
        val replacement = "newCall()\nnewSecondCall()"

        val event = document.replace(
            startOffset = document.lineStartOffset(1),
            endOffset = document.lineEndOffset(1),
            replacement = replacement
        )

        mapper.remapAnchorsForDocumentChange(document.document, event, listOf(stroke))

        assertEquals(2, anchor.line, "Anchors inside replaced code should land at the end of the inserted text")
        assertEquals("newSecondCall()".length, anchor.column)
        assertEquals(document.lineEndOffset(2), anchor.offset)
        assertEquals(24, anchor.dx)
    }

    @Test
    fun `points before and after an edit remap independently within the same stroke`() {
        val document = EditableTestDocument(
            """
            first()
            second()
            third()
            fourth()
            """.trimIndent()
        )
        val pointBeforeEdit = document.anchorAtLineEnd(line = 0, dx = 12, dy = 2)
        val pointAfterEdit = document.anchorAtLineEnd(line = 3, dx = 14, dy = 5)
        val stroke = StrokePath(
            color = Color.ORANGE,
            width = 3.5f,
            points = mutableListOf(pointBeforeEdit, pointAfterEdit)
        )
        val originalBeforeOffset = pointBeforeEdit.offset

        val event = document.insert(document.lineStartOffset(2), "inserted()\n")

        mapper.remapAnchorsForDocumentChange(document.document, event, listOf(stroke))

        assertEquals(0, pointBeforeEdit.line)
        assertEquals(originalBeforeOffset, pointBeforeEdit.offset)
        assertEquals(4, pointAfterEdit.line)
        assertEquals(document.lineEndOffset(4), pointAfterEdit.offset)
    }

    @Test
    fun `rigid object anchors keep all shape points attached to one primary code anchor`() {
        val document = EditableTestDocument(
            """
            before()
            target()
            middle()
            after()
            """.trimIndent()
        )
        val canvas = JPanel()
        val editor = TestEditor(document, canvas)
        val mapper = DrawingCoordinateMapper(
            canvas = canvas,
            editorProvider = { editor.editor },
            minCodeClearancePx = 8
        )
        val anchorLineEndX = document.lineEndColumn(1) * editor.charWidth
        val anchorLineBaseY = 1 * editor.lineHeight

        mapper.beginObjectAnchor(Point(anchorLineEndX + 30, anchorLineBaseY + 5))
        val topPoint = mapper.viewPointToObjectAnchor(Point(anchorLineEndX + 30, anchorLineBaseY + 5))
        val lowerPoint = mapper.viewPointToObjectAnchor(Point(anchorLineEndX + 64, anchorLineBaseY + 44))
        mapper.endObjectAnchor()

        requireNotNull(topPoint)
        requireNotNull(lowerPoint)
        assertEquals(1, topPoint.line)
        assertEquals(topPoint.line, lowerPoint.line)
        assertEquals(document.lineEndOffset(1), topPoint.offset)
        assertEquals(topPoint.offset, lowerPoint.offset)
        assertEquals(30, topPoint.dx)
        assertEquals(64, lowerPoint.dx)
        assertEquals(5, topPoint.dy)
        assertEquals(44, lowerPoint.dy)
    }

    @Test
    fun `rigid generated shapes store all sampled points under one primary code anchor`() {
        val document = EditableTestDocument(
            """
            before()
            target()
            middle()
            after()
            """.trimIndent()
        )
        val canvas = JPanel()
        val editor = TestEditor(document, canvas)
        val mapper = DrawingCoordinateMapper(
            canvas = canvas,
            editorProvider = { editor.editor },
            minCodeClearancePx = 8
        )
        val anchorLineEndX = document.lineEndColumn(1) * editor.charWidth
        val anchorLineBaseY = 1 * editor.lineHeight
        val start = Point(anchorLineEndX + 24, anchorLineBaseY + 4)
        val end = Point(anchorLineEndX + 96, anchorLineBaseY + 48)

        mapper.beginObjectAnchor(start)
        val stroke = ShapeStrokeFactory.buildShapeStroke(
            start = start,
            end = end,
            kind = ShapeKind.ELLIPSE,
            constrain = false,
            color = Color.GREEN,
            width = 3.5f,
            shapeEdgeSpacing = 6.0,
            ellipseSegments = 16,
            toAnchor = { point -> mapper.viewPointToObjectAnchor(point) }
        )
        mapper.endObjectAnchor()

        assertEquals(ShapeKind.ELLIPSE, stroke.kind)
        assertEquals(setOf(1), stroke.points.map { it.line }.toSet())
        assertEquals(setOf(document.lineEndOffset(1)), stroke.points.map { it.offset }.toSet())
        assertEquals(true, stroke.points.maxOf { it.dy } - stroke.points.minOf { it.dy } > 30)
        assertEquals(true, stroke.points.maxOf { it.dx } - stroke.points.minOf { it.dx } > 50)
    }

    @Test
    fun `rigid shape reanchors to the topmost occupied line even when drag starts lower`() {
        val document = EditableTestDocument(
            """
            before()
            top()
            bottom()
            after()
            """.trimIndent()
        )
        val canvas = JPanel()
        val editor = TestEditor(document, canvas)
        val mapper = DrawingCoordinateMapper(
            canvas = canvas,
            editorProvider = { editor.editor },
            minCodeClearancePx = 8
        )
        val topLineEndX = document.lineEndColumn(1) * editor.charWidth
        val bottomLineEndX = document.lineEndColumn(2) * editor.charWidth
        val start = Point(bottomLineEndX + 144, 2 * editor.lineHeight + 16)
        val end = Point(topLineEndX + 96, 1 * editor.lineHeight + 4)

        mapper.beginObjectAnchor(start)
        val stroke = ShapeStrokeFactory.buildShapeStroke(
            start = start,
            end = end,
            kind = ShapeKind.ELLIPSE,
            constrain = false,
            color = Color.GREEN,
            width = 3.5f,
            shapeEdgeSpacing = 6.0,
            ellipseSegments = 16,
            toAnchor = { point -> mapper.viewPointToObjectAnchor(point) }
        )
        mapper.endObjectAnchor()
        val before = stroke.points.map { mapper.toContentPoint(it.copy()) }
        val reanchored = mapper.reanchorStrokeToObjectAnchor(stroke)
        val after = stroke.points.map { mapper.toContentPoint(it.copy()) }

        assertEquals(true, reanchored)
        assertEquals(setOf(1), stroke.points.map { it.line }.toSet())
        assertEquals(setOf(document.lineEndOffset(1)), stroke.points.map { it.offset }.toSet())
        assertEquals(before, after, "Shape top-line reanchoring must not visually move the shape")
    }

    @Test
    fun `compact freehand drawing can be reanchored as one object without moving visually`() {
        val document = EditableTestDocument(
            """
            before()
            target()
            middle()
            after()
            """.trimIndent()
        )
        val canvas = JPanel()
        val editor = TestEditor(document, canvas)
        val mapper = DrawingCoordinateMapper(
            canvas = canvas,
            editorProvider = { editor.editor },
            minCodeClearancePx = 8
        )
        val firstLineEndX = document.lineEndColumn(1) * editor.charWidth
        val secondLineEndX = document.lineEndColumn(2) * editor.charWidth
        val first = mapper.viewPointToAnchor(Point(firstLineEndX + 24, 1 * editor.lineHeight + 4))
        val second = mapper.viewPointToAnchor(Point(secondLineEndX + 68, 2 * editor.lineHeight + 18))
        requireNotNull(first)
        requireNotNull(second)
        val stroke = StrokePath(
            color = Color.MAGENTA,
            width = 3.5f,
            points = mutableListOf(first, second)
        )

        val before = stroke.points.map { mapper.toContentPoint(it.copy()) }
        val reanchored = mapper.reanchorStrokeToObjectAnchor(stroke)
        val after = stroke.points.map { mapper.toContentPoint(it.copy()) }

        assertEquals(true, reanchored)
        assertEquals(true, stroke.rigidObjectAnchor)
        assertEquals(setOf(1), stroke.points.map { it.line }.toSet())
        assertEquals(setOf(document.lineEndOffset(1)), stroke.points.map { it.offset }.toSet())
        assertEquals(before, after, "Reanchoring should not visually move the freehand stroke")
    }

    @Test
    fun `freehand reanchor chooses the topmost line occupied by the drawing`() {
        val document = EditableTestDocument(
            """
            before()
            if __name__ == "__main__":
                Processar_pesquisa_ICEC()
            after()
            """.trimIndent()
        )
        val canvas = JPanel()
        val editor = TestEditor(document, canvas)
        val mapper = DrawingCoordinateMapper(
            canvas = canvas,
            editorProvider = { editor.editor },
            minCodeClearancePx = 8
        )
        val upperLineEndX = document.lineEndColumn(1) * editor.charWidth
        val callLineEndX = document.lineEndColumn(2) * editor.charWidth
        val stroke = StrokePath(
            color = Color.MAGENTA,
            width = 3.5f,
            points = mutableListOf(
                mapper.viewPointToAnchor(Point(upperLineEndX + 32, 1 * editor.lineHeight + 18))!!,
                mapper.viewPointToAnchor(Point(callLineEndX + 70, 2 * editor.lineHeight + 6))!!,
                mapper.viewPointToAnchor(Point(callLineEndX + 92, 2 * editor.lineHeight + 18))!!
            )
        )

        val before = stroke.points.map { mapper.toContentPoint(it.copy()) }
        val reanchored = mapper.reanchorStrokeToObjectAnchor(stroke)
        val after = stroke.points.map { mapper.toContentPoint(it.copy()) }

        assertEquals(true, reanchored)
        assertEquals(true, stroke.rigidObjectAnchor)
        assertEquals(setOf(1), stroke.points.map { it.line }.toSet())
        assertEquals(setOf(document.lineEndOffset(1)), stroke.points.map { it.offset }.toSet())
        assertEquals(before, after, "Top-line base selection should not visually move the drawing")
    }

    @Test
    fun `selected drawing group moves by view delta without changing its shape`() {
        val document = EditableTestDocument(
            """
            before()
            target()
            middle()
            after()
            """.trimIndent()
        )
        val canvas = JPanel()
        val editor = TestEditor(document, canvas)
        val mapper = DrawingCoordinateMapper(
            canvas = canvas,
            editorProvider = { editor.editor },
            minCodeClearancePx = 8
        )
        val anchorLineEndX = document.lineEndColumn(1) * editor.charWidth
        val anchorLineBaseY = 1 * editor.lineHeight
        val objectGroupId = 77L

        mapper.beginObjectAnchor(Point(anchorLineEndX + 30, anchorLineBaseY + 5))
        val outline = StrokePath(
            color = Color.CYAN,
            width = 3.5f,
            points = mutableListOf(
                mapper.viewPointToObjectAnchor(Point(anchorLineEndX + 30, anchorLineBaseY + 5))!!,
                mapper.viewPointToObjectAnchor(Point(anchorLineEndX + 90, anchorLineBaseY + 5))!!,
                mapper.viewPointToObjectAnchor(Point(anchorLineEndX + 90, anchorLineBaseY + 42))!!
            ),
            kind = ShapeKind.RECTANGLE,
            objectGroupId = objectGroupId,
            rigidObjectAnchor = true
        )
        val label = StrokePath(
            color = Color.YELLOW,
            width = 3.5f,
            points = mutableListOf(
                mapper.viewPointToObjectAnchor(Point(anchorLineEndX + 42, anchorLineBaseY + 20))!!,
                mapper.viewPointToObjectAnchor(Point(anchorLineEndX + 76, anchorLineBaseY + 20))!!
            ),
            kind = ShapeKind.TEXT,
            objectGroupId = objectGroupId,
            rigidObjectAnchor = true
        )
        mapper.endObjectAnchor()

        val strokes = listOf(outline, label)
        val before = strokes.map { stroke -> stroke.points.map { mapper.toContentPoint(it.copy())!! } }

        val moved = mapper.moveStrokesByViewDelta(strokes, deltaX = 16, deltaY = editor.lineHeight)
        val after = strokes.map { stroke -> stroke.points.map { mapper.toContentPoint(it.copy())!! } }

        assertEquals(true, moved)
        assertEquals(
            before.map { strokePoints -> strokePoints.map { Point(it.x + 16, it.y + editor.lineHeight) } },
            after,
            "Selection move should translate the whole drawing group without reshaping it"
        )
        assertEquals(setOf(2), strokes.flatMap { stroke -> stroke.points.map { it.line } }.toSet())
        assertEquals(setOf(document.lineEndOffset(2)), strokes.flatMap { stroke -> stroke.points.map { it.offset } }.toSet())
        assertEquals(true, strokes.all { it.rigidObjectAnchor })
        assertEquals(setOf(objectGroupId), strokes.map { it.objectGroupId }.toSet())
    }

    @Test
    fun `freehand does not distort when typing on a lower non anchor line it occupies`() {
        val document = EditableTestDocument(
            """
            before()
            if __name__ == "__main__":
                Processar_pesquisa_ICEC()
            after()
            """.trimIndent()
        )
        val canvas = JPanel()
        val editor = TestEditor(document, canvas)
        val mapper = DrawingCoordinateMapper(
            canvas = canvas,
            editorProvider = { editor.editor },
            minCodeClearancePx = 8
        )
        val upperLineEndX = document.lineEndColumn(1) * editor.charWidth
        val callLineEndX = document.lineEndColumn(2) * editor.charWidth
        val stroke = StrokePath(
            color = Color.MAGENTA,
            width = 3.5f,
            points = mutableListOf(
                mapper.viewPointToAnchor(Point(upperLineEndX + 32, 1 * editor.lineHeight + 18))!!,
                mapper.viewPointToAnchor(Point(callLineEndX + 70, 2 * editor.lineHeight + 6))!!,
                mapper.viewPointToAnchor(Point(callLineEndX + 92, 2 * editor.lineHeight + 18))!!
            )
        )

        mapper.reanchorStrokeToObjectAnchor(stroke)
        val before = stroke.points.map { mapper.toContentPoint(it.copy()) }
        val originalDeltas = stroke.points.map { it.dx to it.dy }
        val event = document.insert(document.lineEndOffset(2), "  # typed on lower occupied line")

        mapper.remapAnchorsForDocumentChange(document.document, event, listOf(stroke))
        val after = stroke.points.map { mapper.toContentPoint(it.copy()) }

        assertEquals(setOf(1), stroke.points.map { it.line }.toSet())
        assertEquals(setOf(document.lineEndOffset(1)), stroke.points.map { it.offset }.toSet())
        assertEquals(originalDeltas, stroke.points.map { it.dx to it.dy })
        assertEquals(before, after, "Editing a non-anchor line covered by the drawing must not stretch it")
    }

    @Test
    fun `freehand shifts right as one object when lower occupied code grows underneath it`() {
        val document = EditableTestDocument(
            """
            before()
            top_anchor()
            short()
            after()
            """.trimIndent()
        )
        val canvas = JPanel()
        val editor = TestEditor(document, canvas)
        val mapper = DrawingCoordinateMapper(
            canvas = canvas,
            editorProvider = { editor.editor },
            minCodeClearancePx = 8
        )
        val topLineEndX = document.lineEndColumn(1) * editor.charWidth
        val lowerLineEndX = document.lineEndColumn(2) * editor.charWidth
        val stroke = StrokePath(
            color = Color.MAGENTA,
            width = 3.5f,
            points = mutableListOf(
                mapper.viewPointToAnchor(Point(topLineEndX + 120, 1 * editor.lineHeight + 4))!!,
                mapper.viewPointToAnchor(Point(lowerLineEndX + 190, 2 * editor.lineHeight + 12))!!
            )
        )
        mapper.reanchorStrokeToObjectAnchor(stroke)
        val store = DrawingStrokeStore(FloatBarDrawingStateService(testProject("C:/work/floatbar-project")))
        store.setStrokes(document.document, mutableListOf(stroke))
        val sync = DrawingDocumentSync(
            coordinateMapper = mapper,
            strokeStore = store,
            persistenceDebounceMs = 60_000,
            currentEditor = { editor.editor },
            currentFilePath = { "C:/work/floatbar-project/src/Main.py" },
            currentStrokes = { store.currentStrokes(document.document) },
            onDocumentStrokesRemapped = {},
            repaintCanvas = {}
        )
        val beforeMinX = stroke.points.minOf { mapper.toContentPoint(it.copy())!!.x }
        val beforeRelativeShape = relativeShape(stroke)

        sync.bindDocumentListener(document.document)
        document.insert(document.lineEndOffset(2), "_with_a_long_new_suffix_that_reaches_the_drawing")
        sync.cancelPendingPersistence()
        sync.unbindDocumentListener()

        val afterContentPoints = stroke.points.map { mapper.toContentPoint(it.copy())!! }
        val afterMinX = afterContentPoints.minOf { it.x }
        val requiredLeftX = document.lineEndColumn(2) * editor.charWidth + 8
        assertEquals(setOf(1), stroke.points.map { it.line }.toSet())
        assertEquals(setOf(document.lineEndOffset(1)), stroke.points.map { it.offset }.toSet())
        assertEquals(beforeRelativeShape, relativeShape(stroke))
        assertEquals(true, afterMinX > beforeMinX)
        assertEquals(true, afterMinX >= requiredLeftX)
    }

    @Test
    fun `text strokes with one object group shift together when lower occupied code grows underneath`() {
        val document = EditableTestDocument(
            """
            before()
            top_anchor()
            short()
            after()
            """.trimIndent()
        )
        val objectGroupId = 707L
        val first = groupedRigidStroke(document, line = 1, dx = 120, dy = 4, kind = ShapeKind.TEXT, objectGroupId = objectGroupId)
        val second = groupedRigidStroke(document, line = 1, dx = 190, dy = 10, kind = ShapeKind.TEXT, objectGroupId = objectGroupId)
        val canvas = JPanel()
        val editor = TestEditor(document, canvas)
        val mapper = DrawingCoordinateMapper(
            canvas = canvas,
            editorProvider = { editor.editor },
            minCodeClearancePx = 8
        )
        val store = DrawingStrokeStore(FloatBarDrawingStateService(testProject("C:/work/floatbar-project")))
        store.setStrokes(document.document, mutableListOf(first, second))
        val sync = documentSync(document, editor, mapper, store)
        val beforeDxs = listOf(first, second).map { stroke -> stroke.points.map { it.dx } }

        sync.bindDocumentListener(document.document)
        document.insert(document.lineEndOffset(2), "_with_a_long_new_suffix_that_reaches_the_text")
        sync.cancelPendingPersistence()
        sync.unbindDocumentListener()

        val afterDxs = listOf(first, second).map { stroke -> stroke.points.map { it.dx } }
        val firstShift = afterDxs.first().first() - beforeDxs.first().first()
        assertEquals(true, firstShift > 0)
        for (strokeIndex in afterDxs.indices) {
            for (pointIndex in afterDxs[strokeIndex].indices) {
                assertEquals(firstShift, afterDxs[strokeIndex][pointIndex] - beforeDxs[strokeIndex][pointIndex])
            }
        }
    }

    @Test
    fun `filled strokes with one object group shift together when lower occupied code grows underneath`() {
        val document = EditableTestDocument(
            """
            before()
            top_anchor()
            short()
            after()
            """.trimIndent()
        )
        val objectGroupId = 808L
        val first = groupedRigidStroke(document, line = 1, dx = 120, dy = 4, filled = true, objectGroupId = objectGroupId)
        val second = groupedRigidStroke(document, line = 1, dx = 190, dy = 12, filled = true, objectGroupId = objectGroupId)
        val canvas = JPanel()
        val editor = TestEditor(document, canvas)
        val mapper = DrawingCoordinateMapper(
            canvas = canvas,
            editorProvider = { editor.editor },
            minCodeClearancePx = 8
        )
        val store = DrawingStrokeStore(FloatBarDrawingStateService(testProject("C:/work/floatbar-project")))
        store.setStrokes(document.document, mutableListOf(first, second))
        val sync = documentSync(document, editor, mapper, store)
        val beforeDxs = listOf(first, second).map { stroke -> stroke.points.map { it.dx } }

        sync.bindDocumentListener(document.document)
        document.insert(document.lineEndOffset(2), "_with_a_long_new_suffix_that_reaches_the_fill")
        sync.cancelPendingPersistence()
        sync.unbindDocumentListener()

        val afterDxs = listOf(first, second).map { stroke -> stroke.points.map { it.dx } }
        val firstShift = afterDxs.first().first() - beforeDxs.first().first()
        assertEquals(true, firstShift > 0)
        for (strokeIndex in afterDxs.indices) {
            for (pointIndex in afterDxs[strokeIndex].indices) {
                assertEquals(firstShift, afterDxs[strokeIndex][pointIndex] - beforeDxs[strokeIndex][pointIndex])
            }
        }
    }

    @Test
    fun `right brace shape shifts right when lower occupied code grows underneath`() {
        val document = EditableTestDocument(
            """
            before()
            top_anchor()
            short()
            after()
            """.trimIndent()
        )
        val stroke = groupedRigidStroke(document, line = 1, dx = 120, dy = 4, kind = ShapeKind.RIGHT_BRACE, objectGroupId = 0L)
        val canvas = JPanel()
        val editor = TestEditor(document, canvas)
        val mapper = DrawingCoordinateMapper(
            canvas = canvas,
            editorProvider = { editor.editor },
            minCodeClearancePx = 8
        )
        val store = DrawingStrokeStore(FloatBarDrawingStateService(testProject("C:/work/floatbar-project")))
        store.setStrokes(document.document, mutableListOf(stroke))
        val sync = documentSync(document, editor, mapper, store)
        val beforeDx = stroke.points.first().dx

        sync.bindDocumentListener(document.document)
        document.insert(document.lineEndOffset(2), "_with_a_long_new_suffix_that_reaches_the_right_brace")
        sync.cancelPendingPersistence()
        sync.unbindDocumentListener()

        assertEquals(true, ShapeKind.RIGHT_BRACE.usesRigidObjectAnchoring())
        assertEquals(setOf(1), stroke.points.map { it.line }.toSet())
        assertEquals(setOf(document.lineEndOffset(1)), stroke.points.map { it.offset }.toSet())
        assertEquals(true, stroke.points.first().dx > beforeDx)
    }

    @Test
    fun `tall freehand drawing reanchors to the topmost occupied line as one rigid object`() {
        val document = EditableTestDocument(
            """
            before()
            top()
            second()
            third()
            fourth()
            bottom()
            after()
            """.trimIndent()
        )
        val canvas = JPanel()
        val editor = TestEditor(document, canvas)
        val mapper = DrawingCoordinateMapper(
            canvas = canvas,
            editorProvider = { editor.editor },
            minCodeClearancePx = 8
        )
        val topLineEndX = document.lineEndColumn(1) * editor.charWidth
        val bottomLineEndX = document.lineEndColumn(5) * editor.charWidth
        val stroke = StrokePath(
            color = Color.MAGENTA,
            width = 3.5f,
            points = mutableListOf(
                mapper.viewPointToAnchor(Point(topLineEndX + 28, 1 * editor.lineHeight + 6))!!,
                mapper.viewPointToAnchor(Point(topLineEndX + 64, 3 * editor.lineHeight + 8))!!,
                mapper.viewPointToAnchor(Point(bottomLineEndX + 90, 5 * editor.lineHeight + 16))!!
            )
        )

        val before = stroke.points.map { mapper.toContentPoint(it.copy()) }
        val shouldUseRigidAnchor = DrawingViewportTools.shouldUseRigidObjectAnchorForFreehand(
            stroke,
            mapper::toViewPoint
        )
        val reanchored = mapper.reanchorStrokeToObjectAnchor(stroke)
        val after = stroke.points.map { mapper.toContentPoint(it.copy()) }

        assertEquals(true, shouldUseRigidAnchor)
        assertEquals(true, reanchored)
        assertEquals(true, stroke.rigidObjectAnchor)
        assertEquals(setOf(1), stroke.points.map { it.line }.toSet())
        assertEquals(setOf(document.lineEndOffset(1)), stroke.points.map { it.offset }.toSet())
        assertEquals(before, after, "Tall freehand drawings should become one rigid top-line object")
    }

    @Test
    fun `rigid compact freehand drawing does not stretch when a new line is inserted through its visual span`() {
        val document = EditableTestDocument(
            """
            before()
            target()
            middle()
            after()
            """.trimIndent()
        )
        val first = document.anchorAtLineEnd(line = 1, dx = 24, dy = 4)
        val second = document.anchorAtLineEnd(line = 1, dx = 68, dy = 38)
        val stroke = StrokePath(
            color = Color.MAGENTA,
            width = 3.5f,
            points = mutableListOf(first, second),
            rigidObjectAnchor = true
        )
        val originalOffset = document.lineEndOffset(1)

        val event = document.insert(document.lineStartOffset(2), "inserted()\n")

        mapper.remapAnchorsForDocumentChange(document.document, event, listOf(stroke))

        assertEquals(listOf(originalOffset, originalOffset), stroke.points.map { it.offset })
        assertEquals(listOf(1, 1), stroke.points.map { it.line })
        assertEquals(listOf(4, 38), stroke.points.map { it.dy })
    }

    @Test
    fun `document sync migrates legacy saved compact freehand drawing on load`() {
        val filePath = "C:/work/floatbar-project/src/Main.py"
        val document = EditableTestDocument(
            """
            before()
            target()
            middle()
            after()
            """.trimIndent()
        )
        val canvas = JPanel()
        val editor = TestEditor(document, canvas)
        val mapper = DrawingCoordinateMapper(
            canvas = canvas,
            editorProvider = { editor.editor },
            minCodeClearancePx = 8
        )
        val service = FloatBarDrawingStateService(testProject("C:/work/floatbar-project"))
        service.setStrokes(
            filePath,
            listOf(
                SavedStroke(
                    color = Color.MAGENTA.rgb,
                    width = 3.5f,
                    points = mutableListOf(
                        savedAnchor(document, line = 1, dx = 24, dy = 4),
                        savedAnchor(document, line = 2, dx = 68, dy = 18)
                    )
                )
            )
        )
        val store = DrawingStrokeStore(service)
        val sync = DrawingDocumentSync(
            coordinateMapper = mapper,
            strokeStore = store,
            persistenceDebounceMs = 60_000,
            currentEditor = { editor.editor },
            currentFilePath = { filePath },
            currentStrokes = { store.currentStrokes(document.document) },
            onDocumentStrokesRemapped = {},
            repaintCanvas = {}
        )

        sync.loadPersistedStrokes()
        sync.cancelPendingPersistence()

        val loaded = store.currentStrokes(document.document).single()
        assertEquals(true, loaded.rigidObjectAnchor)
        assertEquals(setOf(1), loaded.points.map { it.line }.toSet())
        assertEquals(setOf(document.lineEndOffset(1)), loaded.points.map { it.offset }.toSet())
    }

    @Test
    fun `document sync migrates legacy compact freehand drawing before remapping code edit`() {
        val document = EditableTestDocument(
            """
            before()
            target()
            middle()
            after()
            """.trimIndent()
        )
        val canvas = JPanel()
        val editor = TestEditor(document, canvas)
        val mapper = DrawingCoordinateMapper(
            canvas = canvas,
            editorProvider = { editor.editor },
            minCodeClearancePx = 8
        )
        val service = FloatBarDrawingStateService(testProject("C:/work/floatbar-project"))
        val store = DrawingStrokeStore(service)
        val stroke = StrokePath(
            color = Color.MAGENTA,
            width = 3.5f,
            points = mutableListOf(
                document.anchorAtLineEnd(line = 1, dx = 24, dy = 4),
                document.anchorAtLineEnd(line = 2, dx = 68, dy = 18)
            )
        )
        store.setStrokes(document.document, mutableListOf(stroke))
        val sync = DrawingDocumentSync(
            coordinateMapper = mapper,
            strokeStore = store,
            persistenceDebounceMs = 60_000,
            currentEditor = { editor.editor },
            currentFilePath = { "C:/work/floatbar-project/src/Main.py" },
            currentStrokes = { store.currentStrokes(document.document) },
            onDocumentStrokesRemapped = {},
            repaintCanvas = {}
        )

        sync.bindDocumentListener(document.document)
        document.insert(document.lineStartOffset(2), "inserted()\n")
        sync.cancelPendingPersistence()
        sync.unbindDocumentListener()

        assertEquals(true, stroke.rigidObjectAnchor)
        assertEquals(setOf(1), stroke.points.map { it.line }.toSet())
        assertEquals(setOf(document.lineEndOffset(1)), stroke.points.map { it.offset }.toSet())
    }

    @Test
    fun `rigid object drawing moves as a whole when code is typed on its anchor line`() {
        val document = EditableTestDocument(
            """
            before()
            target()
            after()
            """.trimIndent()
        )
        val first = document.anchorAtLineEnd(line = 1, dx = 24, dy = 2)
        val second = document.anchorAtLineEnd(line = 1, dx = 72, dy = 38)
        val stroke = StrokePath(
            color = Color.CYAN,
            width = 3.5f,
            points = mutableListOf(first, second),
            kind = ShapeKind.ELLIPSE
        )
        val originalDeltas = stroke.points.map { it.dx to it.dy }

        val event = document.insert(document.lineEndOffset(1), " // expanded")

        mapper.remapAnchorsForDocumentChange(document.document, event, listOf(stroke))

        assertEquals(listOf(document.lineEndOffset(1), document.lineEndOffset(1)), stroke.points.map { it.offset })
        assertEquals(listOf(1, 1), stroke.points.map { it.line })
        assertEquals(originalDeltas, stroke.points.map { it.dx to it.dy })
    }

    @Test
    fun `rigid object drawing does not stretch when a new code line is inserted through its visual span`() {
        val document = EditableTestDocument(
            """
            before()
            target()
            middle()
            after()
            """.trimIndent()
        )
        val first = document.anchorAtLineEnd(line = 1, dx = 24, dy = 2)
        val second = document.anchorAtLineEnd(line = 1, dx = 72, dy = 44)
        val stroke = StrokePath(
            color = Color.CYAN,
            width = 3.5f,
            points = mutableListOf(first, second),
            kind = ShapeKind.ELLIPSE
        )
        val originalOffset = document.lineEndOffset(1)

        val event = document.insert(document.lineStartOffset(2), "inserted()\n")

        mapper.remapAnchorsForDocumentChange(document.document, event, listOf(stroke))

        assertEquals(listOf(originalOffset, originalOffset), stroke.points.map { it.offset })
        assertEquals(listOf(1, 1), stroke.points.map { it.line })
        assertEquals(listOf(2, 44), stroke.points.map { it.dy })
    }

    @Test
    fun `rigid object drawing line bounds cover its visual height for erasing`() {
        val stroke = StrokePath(
            color = Color.CYAN,
            width = 3.5f,
            points = mutableListOf(
                AnchorPoint(line = 5, column = 8, dx = 20, dy = -6, offset = 80),
                AnchorPoint(line = 5, column = 8, dx = 72, dy = 52, offset = 80)
            ),
            kind = ShapeKind.ELLIPSE
        )

        val bounds = DrawingViewportTools.computeStrokeLineBounds(stroke)

        requireNotNull(bounds)
        assertEquals(true, bounds.minLine < 5)
        assertEquals(true, bounds.maxLine > 5)
    }

    @Test
    fun `rigid text line bounds cover generated letters for erasing`() {
        val stroke = StrokePath(
            color = Color.BLUE,
            width = 3.5f,
            points = mutableListOf(
                AnchorPoint(line = 8, column = 12, dx = 10, dy = 0, offset = 120),
                AnchorPoint(line = 8, column = 12, dx = 90, dy = 36, offset = 120)
            ),
            kind = ShapeKind.TEXT
        )

        val bounds = DrawingViewportTools.computeStrokeLineBounds(stroke)

        requireNotNull(bounds)
        assertEquals(true, bounds.minLine <= 8)
        assertEquals(true, bounds.maxLine > 8)
    }

    @Test
    fun `rigid compact freehand line bounds cover its visual height for erasing`() {
        val stroke = StrokePath(
            color = Color.MAGENTA,
            width = 3.5f,
            points = mutableListOf(
                AnchorPoint(line = 6, column = 8, dx = 20, dy = 0, offset = 90),
                AnchorPoint(line = 6, column = 8, dx = 86, dy = 42, offset = 90)
            ),
            rigidObjectAnchor = true
        )

        val bounds = DrawingViewportTools.computeStrokeLineBounds(stroke)

        requireNotNull(bounds)
        assertEquals(true, bounds.minLine <= 6)
        assertEquals(true, bounds.maxLine > 6)
    }

    @Test
    fun `normal tall freehand line bounds stay line based`() {
        val stroke = StrokePath(
            color = Color.MAGENTA,
            width = 3.5f,
            points = mutableListOf(
                AnchorPoint(line = 6, column = 8, dx = 20, dy = 0, offset = 90),
                AnchorPoint(line = 12, column = 8, dx = 24, dy = 0, offset = 160)
            )
        )

        val bounds = DrawingViewportTools.computeStrokeLineBounds(stroke)

        requireNotNull(bounds)
        assertEquals(6, bounds.minLine)
        assertEquals(12, bounds.maxLine)
    }

    @Test
    fun `all shape kinds use rigid object anchoring`() {
        for (kind in ShapeKind.entries) {
            assertEquals(true, kind.usesRigidObjectAnchoring(), "$kind should use shared top-line object anchoring")
        }
    }

    @Test
    fun `document sync remaps existing drawing and requests bounds rebuild and repaint after code edit`() {
        val document = EditableTestDocument(
            """
            before()
            target()
            after()
            """.trimIndent()
        )
        val store = DrawingStrokeStore(
            FloatBarDrawingStateService(testProject("C:/work/floatbar-project"))
        )
        val anchor = document.anchorAtLineEnd(line = 1, dx = 22, dy = 6)
        store.setStrokes(document.document, mutableListOf(strokeWith(anchor)))

        var remapCallbacks = 0
        var repaintCallbacks = 0
        val sync = DrawingDocumentSync(
            coordinateMapper = mapper,
            strokeStore = store,
            persistenceDebounceMs = 60_000,
            currentEditor = { null },
            currentFilePath = { "C:/work/floatbar-project/src/Main.kt" },
            currentStrokes = { store.currentStrokes(document.document) },
            onDocumentStrokesRemapped = { changedDocument ->
                assertSame(document.document, changedDocument)
                remapCallbacks += 1
            },
            repaintCanvas = {
                repaintCallbacks += 1
            }
        )

        sync.bindDocumentListener(document.document)
        document.insert(document.lineStartOffset(1), "inserted()\n")
        sync.cancelPendingPersistence()
        sync.unbindDocumentListener()

        assertEquals(2, anchor.line)
        assertEquals(document.lineEndOffset(2), anchor.offset)
        assertEquals(1, remapCallbacks, "Canvas should rebuild bounds/cache after document edits")
        assertEquals(1, repaintCallbacks, "Canvas should repaint after existing drawings move with code")
    }

    private fun strokeWith(anchor: AnchorPoint): StrokePath {
        return StrokePath(
            color = Color.YELLOW,
            width = 3.5f,
            points = mutableListOf(anchor)
        )
    }

    private fun savedAnchor(document: EditableTestDocument, line: Int, dx: Int, dy: Int): SavedPoint {
        return SavedPoint(
            anchorStorageVersion = 3,
            line = line,
            column = document.lineEndColumn(line),
            dx = dx,
            dy = dy,
            offset = document.lineEndOffset(line)
        )
    }

    private fun groupedRigidStroke(
        document: EditableTestDocument,
        line: Int,
        dx: Int,
        dy: Int,
        kind: ShapeKind? = null,
        filled: Boolean = false,
        objectGroupId: Long
    ): StrokePath {
        val lineEnd = document.lineEndOffset(line)
        val column = document.lineEndColumn(line)
        return StrokePath(
            color = Color.MAGENTA,
            width = 3.5f,
            points = mutableListOf(
                AnchorPoint(line = line, column = column, dx = dx, dy = dy, offset = lineEnd),
                AnchorPoint(line = line, column = column, dx = dx + 32, dy = dy + 18, offset = lineEnd)
            ),
            filled = filled,
            kind = kind,
            objectGroupId = objectGroupId,
            rigidObjectAnchor = true
        )
    }

    private fun documentSync(
        document: EditableTestDocument,
        editor: TestEditor,
        mapper: DrawingCoordinateMapper,
        store: DrawingStrokeStore
    ): DrawingDocumentSync {
        return DrawingDocumentSync(
            coordinateMapper = mapper,
            strokeStore = store,
            persistenceDebounceMs = 60_000,
            currentEditor = { editor.editor },
            currentFilePath = { "C:/work/floatbar-project/src/Main.py" },
            currentStrokes = { store.currentStrokes(document.document) },
            onDocumentStrokesRemapped = {},
            repaintCanvas = {}
        )
    }

    private fun relativeShape(stroke: StrokePath): List<Pair<Int, Int>> {
        val minDx = stroke.points.minOf { it.dx }
        return stroke.points.map { point -> point.dx - minDx to point.dy }
    }

    private class EditableTestDocument(initialText: String) {
        private val text = StringBuilder(initialText)
        private val listeners = mutableListOf<DocumentListener>()

        val document: Document = proxyFor(Document::class.java) { methodName, returnType, args, proxy ->
            when (methodName) {
                "getText" -> text.toString()
                "getCharsSequence" -> text.toString()
                "getTextLength" -> text.length
                "getLineCount" -> lineCount()
                "getLineStartOffset" -> lineStartOffset(args?.get(0) as Int)
                "getLineEndOffset" -> lineEndOffset(args?.get(0) as Int)
                "getLineNumber" -> lineNumber(args?.get(0) as Int)
                "addDocumentListener" -> {
                    (args?.firstOrNull() as? DocumentListener)?.let { listeners += it }
                    null
                }
                "removeDocumentListener" -> {
                    (args?.firstOrNull() as? DocumentListener)?.let { listeners -= it }
                    null
                }
                "toString" -> "EditableTestDocument(${text.length} chars)"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> defaultReturnValue(returnType)
            }
        }

        fun anchorAtLineEnd(line: Int, dx: Int, dy: Int): AnchorPoint {
            return AnchorPoint(
                line = line,
                column = lineEndColumn(line),
                dx = dx,
                dy = dy,
                offset = lineEndOffset(line)
            )
        }

        fun insert(offset: Int, insertedText: String): DocumentEvent {
            val safeOffset = offset.coerceIn(0, text.length)
            val event = TestDocumentEvent(document, safeOffset, oldText = "", newText = insertedText)
            fireBeforeDocumentChange(event)
            text.insert(safeOffset, insertedText)
            fireDocumentChanged(event)
            return event
        }

        fun delete(startOffset: Int, endOffset: Int): DocumentEvent {
            val safeStart = startOffset.coerceIn(0, text.length)
            val safeEnd = endOffset.coerceIn(safeStart, text.length)
            val oldText = text.substring(safeStart, safeEnd)
            val event = TestDocumentEvent(document, safeStart, oldText = oldText, newText = "")
            fireBeforeDocumentChange(event)
            text.delete(safeStart, safeEnd)
            fireDocumentChanged(event)
            return event
        }

        fun replace(startOffset: Int, endOffset: Int, replacement: String): DocumentEvent {
            val safeStart = startOffset.coerceIn(0, text.length)
            val safeEnd = endOffset.coerceIn(safeStart, text.length)
            val oldText = text.substring(safeStart, safeEnd)
            val event = TestDocumentEvent(document, safeStart, oldText = oldText, newText = replacement)
            fireBeforeDocumentChange(event)
            text.replace(safeStart, safeEnd, replacement)
            fireDocumentChanged(event)
            return event
        }

        fun lineStartOffset(line: Int): Int {
            return lineStarts()[line]
        }

        fun lineEndOffset(line: Int): Int {
            val starts = lineStarts()
            return if (line < starts.lastIndex) {
                starts[line + 1] - 1
            } else {
                text.length
            }
        }

        fun lineEndColumn(line: Int): Int {
            return lineEndOffset(line) - lineStartOffset(line)
        }

        fun lineText(line: Int): String {
            return text.substring(lineStartOffset(line), lineEndOffset(line))
        }

        fun logicalPositionForOffset(offset: Int): LogicalPosition {
            val line = lineNumber(offset)
            return LogicalPosition(line, offset - lineStartOffset(line))
        }

        private fun fireDocumentChanged(event: DocumentEvent) {
            for (listener in listeners.toList()) {
                listener.documentChanged(event)
            }
        }

        private fun fireBeforeDocumentChange(event: DocumentEvent) {
            for (listener in listeners.toList()) {
                listener.beforeDocumentChange(event)
            }
        }

        private fun lineCount(): Int {
            return lineStarts().size
        }

        private fun lineNumber(offset: Int): Int {
            val safeOffset = offset.coerceIn(0, text.length)
            val starts = lineStarts()
            var line = 0
            for (index in starts.indices) {
                if (starts[index] <= safeOffset) {
                    line = index
                } else {
                    break
                }
            }
            return line.coerceIn(0, starts.lastIndex)
        }

        private fun lineStarts(): List<Int> {
            val starts = mutableListOf(0)
            for (index in 0 until text.length) {
                if (text[index] == '\n') {
                    starts += index + 1
                }
            }
            return starts
        }
    }

    private class TestEditor(
        private val documentFixture: EditableTestDocument,
        private val content: JPanel,
        val charWidth: Int = 8,
        val lineHeight: Int = 20
    ) {
        val editor: Editor = proxyFor(Editor::class.java) { methodName, returnType, args, proxy ->
            when (methodName) {
                "getDocument" -> documentFixture.document
                "getContentComponent" -> content
                "xyToLogicalPosition" -> {
                    val point = args?.firstOrNull() as Point
                    LogicalPosition(
                        (point.y / lineHeight).coerceAtLeast(0),
                        (point.x / charWidth).coerceAtLeast(0)
                    )
                }
                "logicalPositionToXY" -> {
                    val position = args?.firstOrNull() as LogicalPosition
                    Point(position.column * charWidth, position.line * lineHeight)
                }
                "offsetToLogicalPosition" -> {
                    documentFixture.logicalPositionForOffset(args?.firstOrNull() as Int)
                }
                "toString" -> "TestEditor"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> defaultReturnValue(returnType)
            }
        }
    }

    private class TestDocumentEvent(
        document: Document,
        private val eventOffset: Int,
        private val oldText: String,
        private val newText: String
    ) : DocumentEvent(document) {
        override fun getOffset(): Int = eventOffset

        override fun getOldLength(): Int = oldText.length

        override fun getNewLength(): Int = newText.length

        override fun getOldFragment(): CharSequence = oldText

        override fun getNewFragment(): CharSequence = newText

        override fun getOldTimeStamp(): Long = 0L
    }

    private fun testProject(basePath: String): Project {
        return proxyFor(Project::class.java) { methodName, returnType, args, proxy ->
            when (methodName) {
                "getBasePath" -> basePath
                "getName" -> "FloatBarDocumentInteractionTestProject"
                "isDisposed" -> false
                "toString" -> "FloatBarDocumentInteractionTestProject($basePath)"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> defaultReturnValue(returnType)
            }
        }
    }
}

private fun <T : Any> proxyFor(
    type: Class<T>,
    implementation: (methodName: String, returnType: Class<*>, args: Array<out Any?>?, proxy: Any) -> Any?
): T {
    val handler = InvocationHandler { proxy, method, args ->
        implementation(method.name, method.returnType, args, proxy)
    }
    @Suppress("UNCHECKED_CAST")
    return Proxy.newProxyInstance(type.classLoader, arrayOf(type), handler) as T
}

private fun defaultReturnValue(returnType: Class<*>): Any? {
    return when (returnType) {
        java.lang.Boolean.TYPE -> false
        java.lang.Byte.TYPE -> 0.toByte()
        java.lang.Short.TYPE -> 0.toShort()
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        java.lang.Character.TYPE -> 0.toChar()
        java.lang.Void.TYPE -> null
        else -> null
    }
}
