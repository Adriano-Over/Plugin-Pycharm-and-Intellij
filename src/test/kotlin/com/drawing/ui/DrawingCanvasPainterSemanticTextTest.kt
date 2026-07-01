package com.drawing.ui

import com.drawing.AnchorPoint
import com.drawing.BalloonTextStyle
import com.drawing.DrawingCanvasPainter
import com.drawing.DrawingCoordinateMapper
import com.drawing.DrawingStrokeRenderer
import com.drawing.DrawingStrokeStore
import com.drawing.DrawingStrokeWorkspace
import com.drawing.DrawingStateService
import com.drawing.ShapeKind
import com.drawing.StrokeGeometryContent
import com.drawing.StrokePath
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.ScrollingModel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.Graphics2D
import java.awt.Point
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import javax.swing.JPanel

class DrawingCanvasPainterSemanticTextTest {
    @Test
    fun `legacy semantic text fallback stays visible without normal stroke painting`() {
        val canvas = JPanel().apply {
            setSize(320, 180)
        }
        val content = JPanel().apply {
            setSize(320, 180)
        }
        val document = testDocument()
        val editor = testEditor(document, content)
        val mapper = DrawingCoordinateMapper(
            canvas = canvas,
            editorProvider = { editor },
            minCodeClearancePx = 8
        )
        val semanticStroke = StrokePath(
            color = Color.BLACK,
            width = 3.5f,
            points = mutableListOf(
                AnchorPoint(line = 0, column = 0, dx = 40, dy = 32, offset = 0),
                AnchorPoint(line = 0, column = 0, dx = 180, dy = 72, offset = 0)
            ),
            kind = ShapeKind.TEXT,
            objectGroupId = 42L,
            annotationText = "Hello world",
            annotationTextStyle = BalloonTextStyle.SOLID,
            annotationBounds = Rectangle(500, 500, 140, 40)
        )
        val service = DrawingStateService(testProject("C:/work/drawing-project"))
        val store = DrawingStrokeStore(service)
        store.setStrokes(document, mutableListOf(semanticStroke))
        val renderer = CountingStrokeRenderer(canvasPadding = 12, gridExtendLeftPx = 0)
        val workspace = DrawingStrokeWorkspace(
            currentDocument = { document },
            strokeStore = store,
            coordinateMapper = mapper,
            strokeRenderer = renderer
        )
        val painter = DrawingCanvasPainter(
            canvas = canvas,
            editorProvider = { editor },
            currentStrokesProvider = { store.currentStrokes(document) },
            shapePreviewProvider = { null },
            gridEnabledProvider = { false },
            currentToolProvider = { com.drawing.DrawingToolMode.DRAW },
            toolPreviewPointProvider = { null },
            eraseRadiusProvider = { 10.0 },
            strokeRenderer = renderer,
            strokeWorkspace = workspace
        )

        val image = BufferedImage(320, 180, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try {
            painter.paintEditorContent(g)
        } finally {
            g.dispose()
        }

        val paintCalls = renderer.paintStrokeCalls
        assertEquals(0, paintCalls, "Legacy text strokes should not go through the normal stroke renderer")
        val firstPaintBounds = paintedPixelBounds(image)
        assertEquals(true, firstPaintBounds.drawnPixels > 0, "Legacy text fallback should stay visible if migration fails")
        assertEquals(true, firstPaintBounds.width >= 40, "Legacy text fallback should not collapse horizontally")
        assertEquals(true, firstPaintBounds.height >= 12, "Legacy text fallback should not collapse vertically")
    }

    private data class PixelBounds(
        val drawnPixels: Int,
        val minX: Int,
        val minY: Int,
        val maxX: Int,
        val maxY: Int
    ) {
        val width: Int get() = maxX - minX
        val height: Int get() = maxY - minY
    }

    private fun paintedPixelBounds(image: BufferedImage): PixelBounds {
        var drawnPixels = 0
        var minX = image.width
        var minY = image.height
        var maxX = -1
        var maxY = -1
        for (x in 0 until image.width) {
            for (y in 0 until image.height) {
                if (image.getRGB(x, y) ushr 24 != 0) {
                    drawnPixels += 1
                    minX = minOf(minX, x)
                    minY = minOf(minY, y)
                    maxX = maxOf(maxX, x)
                    maxY = maxOf(maxY, y)
                }
            }
        }
        return PixelBounds(drawnPixels, minX, minY, maxX, maxY)
    }

    private class CountingStrokeRenderer(
        canvasPadding: Int,
        gridExtendLeftPx: Int
    ) : DrawingStrokeRenderer(canvasPadding, gridExtendLeftPx) {
        var paintStrokeCalls = 0

        override fun paintStroke(
            g: Graphics2D,
            stroke: StrokePath,
            geometry: StrokeGeometryContent,
            preview: Boolean,
            visibleContentClip: Rectangle?
        ) {
            paintStrokeCalls += 1
        }
    }

    private fun testDocument(): Document {
        return proxyFor(Document::class.java) { methodName, returnType, args, proxy ->
            when (methodName) {
                "getLineCount" -> 1
                "getLineStartOffset" -> 0
                "getLineEndOffset" -> 0
                "getLineNumber" -> 0
                "getTextLength" -> 0
                "getText" -> ""
                "getCharsSequence" -> ""
                "toString" -> "PainterSemanticTextDocument"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> defaultReturnValue(returnType)
            }
        }
    }

    private fun testEditor(document: Document, content: JPanel): Editor {
        val scrollingModel = proxyFor(ScrollingModel::class.java) { methodName, returnType, args, proxy ->
            when (methodName) {
                "getVisibleArea" -> Rectangle(0, 0, 320, 180)
                "toString" -> "PainterSemanticScrollingModel"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> defaultReturnValue(returnType)
            }
        }

        return proxyFor(Editor::class.java) { methodName, returnType, args, proxy ->
            when (methodName) {
                "getDocument" -> document
                "getContentComponent" -> content
                "getScrollingModel" -> scrollingModel
                "getLineHeight" -> 18
                "offsetToLogicalPosition" -> LogicalPosition(0, 0)
                "logicalPositionToXY" -> Point(0, 0)
                "xyToLogicalPosition" -> LogicalPosition(0, 0)
                "toString" -> "PainterSemanticEditor"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> defaultReturnValue(returnType)
            }
        }
    }

    private fun testProject(basePath: String): com.intellij.openapi.project.Project {
        return proxyFor(com.intellij.openapi.project.Project::class.java) { methodName, returnType, args, proxy ->
            when (methodName) {
                "getBasePath" -> basePath
                "getName" -> "PainterSemanticTextProject"
                "isDisposed" -> false
                "toString" -> "PainterSemanticTextProject($basePath)"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> defaultReturnValue(returnType)
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
}
