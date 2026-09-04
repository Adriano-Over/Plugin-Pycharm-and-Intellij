package com.drawing.persistence

import com.drawing.DrawingState
import com.drawing.DrawingStateService
import com.drawing.DrawingToolMode
import com.drawing.SavedFileDrawing
import com.drawing.SavedPoint
import com.drawing.SavedStroke
import com.drawing.BalloonTextStyle
import com.drawing.ShapeKind
import com.drawing.UNSET_FOLD_HIDDEN_HEIGHT_ABOVE
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.awt.Color

class DrawingStateServiceTest {
    private val projectBasePath = "C:/work/drawing-project"
    private val filePath = "$projectBasePath/src/Main.kt"

    @Test
    fun `setStrokes stores project files with project macro and returns deep copies`() {
        val service = DrawingStateService(testProject(projectBasePath))
        val savedStroke = savedStroke(color = 0xFF336699.toInt(), line = 4)

        service.setStrokes(filePath, listOf(savedStroke))

        assertEquals("\$PROJECT_DIR$/src/Main.kt", service.state.files.single().filePath)
        assertEquals(savedStroke, service.getStrokes(filePath).single())
        assertEquals(savedStroke, service.getStrokes("\$PROJECT_DIR$/src/Main.kt").single())

        val loaded = service.getStrokes(filePath)
        loaded.single().points.single().line = 99

        assertEquals(4, service.getStrokes(filePath).single().points.single().line)
    }

    @Test
    fun `setStrokes keeps drawings isolated per file and removes an entry when strokes become empty`() {
        val service = DrawingStateService(testProject(projectBasePath))
        val firstFile = "$projectBasePath/src/First.kt"
        val secondFile = "$projectBasePath/src/Second.kt"

        service.setStrokes(firstFile, listOf(savedStroke(color = 0xFFFF0000.toInt(), line = 1)))
        service.setStrokes(secondFile, listOf(savedStroke(color = 0xFF0000FF.toInt(), line = 2)))

        assertEquals(2, service.state.files.size)
        assertEquals(0xFFFF0000.toInt(), service.getStrokes(firstFile).single().color)
        assertEquals(0xFF0000FF.toInt(), service.getStrokes(secondFile).single().color)

        service.setStrokes(firstFile, emptyList())

        assertEquals(emptyList<SavedStroke>(), service.getStrokes(firstFile))
        assertEquals(0xFF0000FF.toInt(), service.getStrokes(secondFile).single().color)
        assertEquals(listOf("\$PROJECT_DIR$/src/Second.kt"), service.state.files.map { it.filePath })
    }

    @Test
    fun `compactFileEntries keeps newest duplicate using normalized project path`() {
        val service = DrawingStateService(testProject(projectBasePath))
        service.loadState(
            DrawingState(
                files = mutableListOf(
                    SavedFileDrawing(
                        filePath = "$projectBasePath/src/Main.kt",
                        strokes = mutableListOf(savedStroke(color = 0xFFFF0000.toInt(), line = 1))
                    ),
                    SavedFileDrawing(
                        filePath = "\$PROJECT_DIR$/src/Main.kt",
                        strokes = mutableListOf(savedStroke(color = 0xFF00FF00.toInt(), line = 2))
                    )
                )
            )
        )

        service.compactFileEntries()

        assertEquals(1, service.state.files.size)
        assertEquals("\$PROJECT_DIR$/src/Main.kt", service.state.files.single().filePath)
        assertEquals(0xFF00FF00.toInt(), service.getStrokes(filePath).single().color)
        assertEquals(2, service.getStrokes(filePath).single().points.single().line)
    }

    @Test
    fun `project relative drawings survive project relocation`() {
        val original = DrawingStateService(testProject("C:/old/location/drawing-project"))
        original.setStrokes(
            "C:/old/location/drawing-project/src/Main.kt",
            listOf(savedStroke(color = 0xFF123456.toInt(), line = 8))
        )

        val relocated = DrawingStateService(testProject("D:/new/location/drawing-project"))
        relocated.loadState(original.state.copy())

        assertEquals(
            0xFF123456.toInt(),
            relocated.getStrokes("D:/new/location/drawing-project/src/Main.kt").single().color
        )
        assertEquals("\$PROJECT_DIR$/src/Main.kt", relocated.state.files.single().filePath)
    }

    @Test
    fun `renaming a file migrates its drawing and replaces a stale destination`() {
        val service = DrawingStateService(testProject(projectBasePath))
        val oldPath = "$projectBasePath/src/OldName.kt"
        val newPath = "$projectBasePath/src/NewName.kt"
        service.setStrokes(oldPath, listOf(savedStroke(color = 0xFF112233.toInt(), line = 3)))
        service.setStrokes(newPath, listOf(savedStroke(color = 0xFF999999.toInt(), line = 9)))

        service.moveDrawing(oldPath, newPath)

        assertEquals(emptyList<SavedStroke>(), service.getStrokes(oldPath))
        assertEquals(0xFF112233.toInt(), service.getStrokes(newPath).single().color)
        assertEquals(listOf("\$PROJECT_DIR$/src/NewName.kt"), service.state.files.map { it.filePath })
    }

    @Test
    fun `deleted file state can be removed without affecting other drawings`() {
        val service = DrawingStateService(testProject(projectBasePath))
        val deletedPath = "$projectBasePath/src/Deleted.kt"
        val retainedPath = "$projectBasePath/src/Retained.kt"
        service.setStrokes(deletedPath, listOf(savedStroke(color = Color.RED.rgb, line = 1)))
        service.setStrokes(retainedPath, listOf(savedStroke(color = Color.BLUE.rgb, line = 2)))

        service.removeDrawing(deletedPath)

        assertEquals(emptyList<SavedStroke>(), service.getStrokes(deletedPath))
        assertEquals(Color.BLUE.rgb, service.getStrokes(retainedPath).single().color)
    }

    @Test
    fun `large drawing state is preserved without sharing mutable points`() {
        val service = DrawingStateService(testProject(projectBasePath))
        val strokes = (0 until 10_000).map { index ->
            savedStroke(color = 0xFF000000.toInt() or index, line = index)
        }

        service.setStrokes(filePath, strokes)
        val loaded = service.getStrokes(filePath)
        loaded.first().points.first().line = -1

        assertEquals(10_000, loaded.size)
        assertEquals(0, service.getStrokes(filePath).first().points.first().line)
        assertEquals(9_999, service.getStrokes(filePath).last().points.first().line)
    }

    @Test
    fun `ui state getters and setters preserve last plugin state`() {
        val service = DrawingStateService(testProject(projectBasePath))

        service.setRecentColors(listOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt()))
        service.setSelectedColorRgb(0xFF112233.toInt())
        service.setGridEnabled(false)
        service.setOverlayEnabled(false)
        service.setInteractionPassThroughEnabled(true)
        service.setDrawingVisible(false)
        service.setSelectedToolMode(DrawingToolMode.FILL)
        service.setSelectedShapeKind(ShapeKind.DOCUMENT)
        service.setSelectedDrawingShapeKind(ShapeKind.DOCUMENT)
        service.setSelectedTextStyle(BalloonTextStyle.OUTLINE)
        service.setSelectedBalloonTextStyle(BalloonTextStyle.SOLID)
        service.setDrawingLocation(320, 180)

        assertEquals(listOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt()), service.getRecentColors())
        assertEquals(0xFF112233.toInt(), service.getSelectedColorRgb())
        assertEquals(false, service.isGridEnabled())
        assertEquals(false, service.isOverlayEnabled())
        assertEquals(true, service.isInteractionPassThroughEnabled())
        assertEquals(false, service.isDrawingVisible())
        assertEquals(DrawingToolMode.FILL, service.getSelectedToolMode())
        assertEquals(ShapeKind.DOCUMENT, service.getSelectedShapeKind())
        assertEquals(ShapeKind.DOCUMENT, service.getSelectedDrawingShapeKind())
        assertEquals(BalloonTextStyle.OUTLINE, service.getSelectedTextStyle())
        assertEquals(BalloonTextStyle.SOLID, service.getSelectedBalloonTextStyle())
        assertEquals(320 to 180, service.getDrawingLocation())
    }

    @Test
    fun `last drawing shape is remembered separately from text and balloon`() {
        val service = DrawingStateService(testProject(projectBasePath))

        service.setSelectedShapeKind(ShapeKind.RECTANGLE)
        service.setSelectedDrawingShapeKind(ShapeKind.RECTANGLE)
        service.setSelectedShapeKind(ShapeKind.TEXT)
        service.setSelectedDrawingShapeKind(ShapeKind.TEXT)

        assertEquals(ShapeKind.TEXT, service.getSelectedShapeKind())
        assertEquals(ShapeKind.RECTANGLE, service.getSelectedDrawingShapeKind())

        service.setSelectedDrawingShapeKind(ShapeKind.ARROW)
        service.setSelectedShapeKind(ShapeKind.BALLOON)

        assertEquals(ShapeKind.BALLOON, service.getSelectedShapeKind())
        assertEquals(ShapeKind.ARROW, service.getSelectedDrawingShapeKind())
    }

    @Test
    fun `text and balloon styles are remembered separately`() {
        val service = DrawingStateService(testProject(projectBasePath))

        service.setSelectedTextStyle(BalloonTextStyle.OUTLINE)
        service.setSelectedBalloonTextStyle(BalloonTextStyle.SOLID)

        assertEquals(BalloonTextStyle.OUTLINE, service.getSelectedTextStyle())
        assertEquals(BalloonTextStyle.SOLID, service.getSelectedBalloonTextStyle())

        service.setSelectedTextStyle(BalloonTextStyle.SOLID)
        service.setSelectedBalloonTextStyle(BalloonTextStyle.OUTLINE)

        assertEquals(BalloonTextStyle.SOLID, service.getSelectedTextStyle())
        assertEquals(BalloonTextStyle.OUTLINE, service.getSelectedBalloonTextStyle())
    }

    @Test
    fun `color state changes do not overwrite the selected tool`() {
        val service = DrawingStateService(testProject(projectBasePath))

        DrawingToolMode.entries.forEachIndexed { index, toolMode ->
            service.setSelectedToolMode(toolMode)
            service.setSelectedColorRgb(0xFF000000.toInt() + index)
            service.setRecentColors(listOf(0xFFAA0000.toInt() + index, 0xFF00AA00.toInt() + index))

            assertEquals(toolMode, service.getSelectedToolMode())
        }
    }

    @Test
    fun `selected tool changes do not overwrite color state`() {
        val service = DrawingStateService(testProject(projectBasePath))
        val selectedColor = 0xFF3366CC.toInt()
        val recentColors = listOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt())

        service.setSelectedColorRgb(selectedColor)
        service.setRecentColors(recentColors)

        DrawingToolMode.entries.forEach { toolMode ->
            service.setSelectedToolMode(toolMode)

            assertEquals(toolMode, service.getSelectedToolMode())
            assertEquals(selectedColor, service.getSelectedColorRgb())
            assertEquals(recentColors, service.getRecentColors())
        }
    }

    @Test
    fun `invalid stored enum values fall back to safe defaults`() {
        val service = DrawingStateService(testProject(projectBasePath))
        service.loadState(
            DrawingState(
                selectedToolMode = "BROKEN_TOOL",
                selectedShapeKind = "BROKEN_SHAPE",
                selectedDrawingShapeKind = "BROKEN_DRAWING_SHAPE",
                selectedTextStyle = "BROKEN_TEXT_STYLE",
                selectedBalloonTextStyle = "BROKEN_STYLE"
            )
        )

        assertEquals(DrawingToolMode.DRAW, service.getSelectedToolMode())
        assertEquals(ShapeKind.RECTANGLE, service.getSelectedShapeKind())
        assertEquals(ShapeKind.RECTANGLE, service.getSelectedDrawingShapeKind())
        assertEquals(BalloonTextStyle.SOLID, service.getSelectedTextStyle())
        assertEquals(BalloonTextStyle.SOLID, service.getSelectedBalloonTextStyle())
        assertNull(service.getDrawingLocation())
    }

    private fun savedStroke(color: Int, line: Int): SavedStroke {
        return SavedStroke(
            color = color,
            width = 5.5f,
            points = mutableListOf(
                SavedPoint(
                    line = line,
                    column = 7,
                    dx = 11,
                    dy = 13,
                    offset = 17,
                    outsideCode = true,
                    afterLineEndPx = 19,
                    foldHiddenHeightAbove = UNSET_FOLD_HIDDEN_HEIGHT_ABOVE
                )
            ),
            filled = true,
            kind = ShapeKind.ELLIPSE.name
        )
    }
}
