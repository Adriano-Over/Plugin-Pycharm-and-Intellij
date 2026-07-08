package com.drawing.performance

import com.drawing.AnchorPoint
import com.drawing.AnnotationKind
import com.drawing.AnnotationPath
import com.drawing.DrawingCoordinateMapper
import com.drawing.DrawingDocumentSync
import com.drawing.DrawingPerformanceDiagnostics
import com.drawing.DrawingStateService
import com.drawing.DrawingStrokeRenderer
import com.drawing.DrawingStrokeStore
import com.drawing.DrawingStrokeWorkspace
import com.drawing.PaintPerformanceStats
import com.drawing.RasterFillCodec
import com.drawing.RasterFillPath
import com.drawing.ShapeKind
import com.drawing.StrokePath
import com.drawing.persistence.testProject
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.Point
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import javax.swing.JPanel

class DrawingScalabilityTest {
    @Test
    fun `document sync batches rapid edits for large mixed drawing sets`() {
        val document = EditableTestDocument((0 until 2_000).joinToString("\n") { "line$it()" })
        val mapper = DrawingCoordinateMapper(
            canvas = JPanel(),
            editorProvider = { null },
            minCodeClearancePx = 8
        )
        val store = DrawingStrokeStore(DrawingStateService(testProject("C:/work/drawing-project")))
        val targetLine = 1_200
        val strokes = MutableList(1_000) { index ->
            val line = targetLine + index % 20
            StrokePath(
                color = Color.CYAN,
                width = 3.5f,
                points = mutableListOf(
                    document.anchorAtLineEnd(line = line, dx = 24, dy = 4),
                    document.anchorAtLineEnd(line = line, dx = 84, dy = 32)
                ),
                kind = ShapeKind.RECTANGLE,
                rigidObjectAnchor = true
            )
        }
        val fills = MutableList(400) { index ->
            val line = targetLine + index % 20
            RasterFillPath(
                color = Color.GREEN,
                anchor = document.anchorAtLineEnd(line = line, dx = 110, dy = 4),
                width = 12,
                height = 10,
                pngBase64 = onePixelPng()
            )
        }
        val annotations = MutableList(400) { index ->
            val line = targetLine + index % 20
            AnnotationPath(
                text = "Note $index",
                color = Color.ORANGE,
                anchor = document.anchorAtLineEnd(line = line, dx = 140, dy = 4),
                width = 80,
                height = 24,
                kind = AnnotationKind.TEXT
            )
        }
        store.setStrokes(document.document, strokes)
        store.setRasterFills(document.document, fills)
        store.setAnnotations(document.document, annotations)

        var remapCallbacks = 0
        var repaintCallbacks = 0
        val sync = DrawingDocumentSync(
            coordinateMapper = mapper,
            strokeStore = store,
            persistenceDebounceMs = 60_000,
            currentEditor = { null },
            currentFilePath = { "C:/work/drawing-project/src/Main.kt" },
            currentStrokes = { store.currentStrokes(document.document) },
            currentRasterFills = { store.currentRasterFills(document.document) },
            currentAnnotations = { store.currentAnnotations(document.document) },
            onDocumentStrokesRemapped = { remapCallbacks += 1 },
            repaintCanvas = { repaintCallbacks += 1 },
            documentChangeUiDebounceMs = 60_000
        )

        sync.bindDocumentListener(document.document)
        document.insert(document.lineStartOffset(0), "firstInserted()\n")
        document.insert(document.lineStartOffset(1), "secondInserted()\n")

        assertEquals(targetLine, strokes.first().points.first().line, "Queued edits should not remap synchronously")
        assertEquals(0, remapCallbacks)
        assertEquals(0, repaintCallbacks)

        sync.unbindDocumentListener()
        sync.cancelPendingPersistence()

        assertEquals(targetLine + 2, strokes.first().points.first().line)
        assertEquals(targetLine + 2, fills.first().anchor.line)
        assertEquals(targetLine + 2, annotations.first().anchor.line)
        assertEquals(1, remapCallbacks, "Many rapid edits should flush as one batch")
        assertEquals(1, repaintCallbacks)
    }

    @Test
    fun `visible stroke lookup builds geometry only for the visible range and reuses cache`() {
        val fixture = WorkspaceFixture(lineCount = 1_000)
        val strokes = MutableList(1_000) { line ->
            StrokePath(
                color = Color.CYAN,
                width = 3.5f,
                points = mutableListOf(
                    fixture.document.anchorAtLineEnd(line = line, dx = 30, dy = 4),
                    fixture.document.anchorAtLineEnd(line = line, dx = 80, dy = 18)
                )
            )
        }
        fixture.store.setStrokes(fixture.document.document, strokes)
        val visibleRange = 100..120
        val visibleClip = Rectangle(0, visibleRange.first * fixture.editor.lineHeight, 800, visibleRange.count() * fixture.editor.lineHeight)

        val firstStats = PaintPerformanceStats()
        DrawingPerformanceDiagnostics.beginPaint(firstStats)
        val firstVisible = try {
            fixture.workspace.visibleStrokes(visibleRange, visibleClip, emptyList())
        } finally {
            DrawingPerformanceDiagnostics.endPaint()
        }

        val secondStats = PaintPerformanceStats()
        DrawingPerformanceDiagnostics.beginPaint(secondStats)
        val secondVisible = try {
            fixture.workspace.visibleStrokes(visibleRange, visibleClip, emptyList())
        } finally {
            DrawingPerformanceDiagnostics.endPaint()
        }

        assertEquals(visibleRange.count(), firstVisible.size)
        assertEquals(visibleRange.count(), firstStats.geometryCacheMisses)
        assertEquals(0, firstStats.geometryCacheHits)
        assertEquals(firstVisible.map { it.id }, secondVisible.map { it.id })
        assertEquals(visibleRange.count(), secondStats.geometryCacheHits)
        assertEquals(0, secondStats.geometryCacheMisses)
    }

    @Test
    fun `raster fill visibility scan does not decode offscreen images and reuses bounds cache`() {
        val fixture = WorkspaceFixture(lineCount = 1_000)
        val fills = MutableList(1_000) { line ->
            RasterFillPath(
                color = Color.GREEN,
                anchor = fixture.document.anchorAtLineEnd(line = line, dx = 30, dy = 2),
                width = 16,
                height = 12,
                pngBase64 = onePixelPng()
            )
        }
        fixture.store.setRasterFills(fixture.document.document, fills)
        val visibleRange = 40..50
        val visibleClip = Rectangle(0, visibleRange.first * fixture.editor.lineHeight, 800, visibleRange.count() * fixture.editor.lineHeight)

        val firstStats = PaintPerformanceStats()
        DrawingPerformanceDiagnostics.beginPaint(firstStats)
        val firstVisible = try {
            fixture.workspace.visibleRasterFills(visibleClip, emptyList())
        } finally {
            DrawingPerformanceDiagnostics.endPaint()
        }

        val secondStats = PaintPerformanceStats()
        DrawingPerformanceDiagnostics.beginPaint(secondStats)
        val secondVisible = try {
            fixture.workspace.visibleRasterFills(visibleClip, emptyList())
        } finally {
            DrawingPerformanceDiagnostics.endPaint()
        }

        assertTrue(firstVisible.size in 10..12)
        assertEquals(0, firstStats.rasterFillImageCacheMisses, "Visibility filtering should not decode raster images")
        assertEquals(0, firstStats.rasterFillImageCacheHits)
        assertEquals(1_000, firstStats.boundsCacheMisses)
        assertEquals(firstVisible.map { it.id }, secondVisible.map { it.id })
        assertEquals(1_000, secondStats.boundsCacheHits)
        assertEquals(0, secondStats.boundsCacheMisses)
    }

    private class WorkspaceFixture(lineCount: Int) {
        val canvas = JPanel()
        val document = EditableTestDocument((0 until lineCount).joinToString("\n") { "line$it()" })
        val editor = TestEditor(document, canvas)
        val mapper = DrawingCoordinateMapper(
            canvas = canvas,
            editorProvider = { editor.editor },
            minCodeClearancePx = 8
        )
        val store = DrawingStrokeStore(DrawingStateService(testProject("C:/work/drawing-project")))
        val workspace = DrawingStrokeWorkspace(
            currentDocument = { document.document },
            strokeStore = store,
            coordinateMapper = mapper,
            strokeRenderer = DrawingStrokeRenderer(canvasPadding = 12, gridExtendLeftPx = 0)
        )
    }

    private class EditableTestDocument(initialText: String) {
        private val text = StringBuilder(initialText)
        private val listeners = mutableListOf<DocumentListener>()

        val document: Document = proxyFor(Document::class.java) { methodName, returnType, args, proxy ->
            when (methodName) {
                "getText" -> text.toString()
                "getCharsSequence" -> text.toString()
                "getTextLength" -> text.length
                "getLineCount" -> lineStarts().size
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
            text.insert(safeOffset, insertedText)
            for (listener in listeners.toList()) {
                listener.documentChanged(event)
            }
            return event
        }

        fun lineStartOffset(line: Int): Int = lineStarts()[line]

        private fun lineEndColumn(line: Int): Int = lineEndOffset(line) - lineStartOffset(line)

        private fun lineEndOffset(line: Int): Int {
            val starts = lineStarts()
            return if (line < starts.lastIndex) starts[line + 1] - 1 else text.length
        }

        fun logicalPositionForOffset(offset: Int): LogicalPosition {
            val line = lineNumber(offset)
            return LogicalPosition(line, offset - lineStartOffset(line))
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
                "getLineHeight" -> lineHeight
                "xyToLogicalPosition" -> {
                    val point = args?.firstOrNull() as Point
                    LogicalPosition((point.y / lineHeight).coerceAtLeast(0), (point.x / charWidth).coerceAtLeast(0))
                }
                "logicalPositionToXY" -> {
                    val position = args?.firstOrNull() as LogicalPosition
                    Point(position.column * charWidth, position.line * lineHeight)
                }
                "offsetToLogicalPosition" -> documentFixture.logicalPositionForOffset(args?.firstOrNull() as Int)
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

    private fun onePixelPng(): String {
        val image = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
        image.setRGB(0, 0, Color.GREEN.rgb)
        return RasterFillCodec.encodePngBase64(image)
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
