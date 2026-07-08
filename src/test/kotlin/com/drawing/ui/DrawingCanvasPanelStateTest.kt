package com.drawing.ui

import com.drawing.BalloonTextStyle
import com.drawing.DrawingCanvasPanel
import com.drawing.DrawingStateService
import com.drawing.DrawingToolMode
import com.drawing.RecentColorStore
import com.drawing.ShapeKind
import com.intellij.openapi.project.Project
import java.awt.Color
import java.awt.Cursor
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.ActionEvent
import java.awt.event.FocusEvent
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import javax.swing.JTextArea

class DrawingCanvasPanelStateTest {
    private val projectBasePath = "C:/work/drawing-project"

    @Test
    fun `changing selected color preserves the active and persisted tool in every mode`() {
        val fixture = panelFixture()
        val cases = listOf(
            ToolCase("select tool", DrawingToolMode.SELECT) {
                setSelectMode()
            },
            ToolCase("draw tool", DrawingToolMode.DRAW) {
                setDrawingMode()
            },
            ToolCase("erase tool", DrawingToolMode.ERASE) {
                setErasingMode()
            },
            ToolCase("fill tool", DrawingToolMode.FILL) {
                setFillMode()
            },
            ToolCase("document shape", DrawingToolMode.SHAPES, expectedShape = ShapeKind.DOCUMENT) {
                setShapeMode(ShapeKind.DOCUMENT)
            },
            ToolCase("right brace shape", DrawingToolMode.SHAPES, expectedShape = ShapeKind.RIGHT_BRACE) {
                setShapeMode(ShapeKind.RIGHT_BRACE)
            },
            ToolCase(
                label = "solid text",
                expectedTool = DrawingToolMode.SHAPES,
                expectedShape = ShapeKind.TEXT,
                expectedTextStyle = BalloonTextStyle.SOLID
            ) {
                setTextStyleFor(ShapeKind.TEXT, BalloonTextStyle.SOLID)
                setShapeMode(ShapeKind.TEXT)
            },
            ToolCase(
                label = "outline balloon",
                expectedTool = DrawingToolMode.SHAPES,
                expectedShape = ShapeKind.BALLOON,
                expectedTextStyle = BalloonTextStyle.OUTLINE
            ) {
                setTextStyleFor(ShapeKind.BALLOON, BalloonTextStyle.OUTLINE)
                setShapeMode(ShapeKind.BALLOON)
            }
        )

        cases.forEachIndexed { index, case ->
            case.activate(fixture.panel)

            val selected = Color(
                (40 + index * 27) % 255,
                (90 + index * 19) % 255,
                (150 + index * 31) % 255
            )
            fixture.panel.setSelectedColor(selected)

            assertEquals(case.expectedTool, fixture.panel.getCurrentToolMode(), "${case.label} should stay active")
            assertEquals(case.expectedTool, fixture.stateService.getSelectedToolMode(), "${case.label} should stay persisted")
            assertEquals(selected.rgb, fixture.panel.getSelectedColor().rgb, "${case.label} panel color should update")
            assertEquals(selected.rgb, fixture.stateService.getSelectedColorRgb(), "${case.label} persisted color should update")

            val reloadedPanel = DrawingCanvasPanel(fixture.project, RecentColorStore(fixture.project))
            assertEquals(case.expectedTool, reloadedPanel.getCurrentToolMode(), "${case.label} should reload as active")
            assertEquals(selected.rgb, reloadedPanel.getSelectedColor().rgb, "${case.label} color should reload")

            case.expectedShape?.let { expectedShape ->
                assertEquals(expectedShape, fixture.panel.getSelectedShapeKind(), "${case.label} shape should stay selected")
                assertEquals(expectedShape, fixture.stateService.getSelectedShapeKind(), "${case.label} shape should stay persisted")
                assertEquals(expectedShape, reloadedPanel.getSelectedShapeKind(), "${case.label} shape should reload")
            }
            case.expectedTextStyle?.let { expectedTextStyle ->
                val textShape = case.expectedShape ?: ShapeKind.TEXT
                assertEquals(
                    expectedTextStyle,
                    fixture.panel.getTextStyleFor(textShape),
                    "${case.label} text style should stay selected"
                )
                val persistedTextStyle = if (textShape == ShapeKind.BALLOON) {
                    fixture.stateService.getSelectedBalloonTextStyle()
                } else {
                    fixture.stateService.getSelectedTextStyle()
                }
                assertEquals(
                    expectedTextStyle,
                    persistedTextStyle,
                    "${case.label} text style should stay persisted"
                )
                assertEquals(
                    expectedTextStyle,
                    reloadedPanel.getTextStyleFor(textShape),
                    "${case.label} text style should reload"
                )
            }
        }
    }

    @Test
    fun `remembering recent colors preserves the persisted tool mode`() {
        val fixture = panelFixture()
        val recentColors = fixture.recentColorStore

        DrawingToolMode.entries.forEachIndexed { index, toolMode ->
            fixture.stateService.setSelectedToolMode(toolMode)
            recentColors.remember(Color((20 + index * 50) % 255, 80, 180))

            assertEquals(toolMode, fixture.stateService.getSelectedToolMode(), "$toolMode should survive recent color save")
        }
    }

    @Test
    fun `select tool uses the regular cursor until an object is being moved`() {
        val fixture = panelFixture()

        fixture.panel.setSelectMode()

        assertEquals(Cursor.DEFAULT_CURSOR, fixture.panel.cursor.type)
    }

    @Test
    fun `shape text and balloon buttons remember their last ready choice separately`() {
        val fixture = panelFixture()

        fixture.panel.setShapeMode(ShapeKind.RECTANGLE)
        fixture.panel.setShapeMode(ShapeKind.TEXT)

        assertEquals(ShapeKind.TEXT, fixture.panel.getSelectedShapeKind())
        assertEquals(ShapeKind.RECTANGLE, fixture.panel.getSelectedDrawingShapeKind())
        assertEquals(ShapeKind.RECTANGLE, fixture.stateService.getSelectedDrawingShapeKind())

        fixture.panel.activateLastDrawingShapeMode()

        assertEquals(DrawingToolMode.SHAPES, fixture.panel.getCurrentToolMode())
        assertEquals(ShapeKind.RECTANGLE, fixture.panel.getSelectedShapeKind())

        fixture.panel.setTextStyleFor(ShapeKind.TEXT, BalloonTextStyle.OUTLINE)
        fixture.panel.setTextStyleFor(ShapeKind.BALLOON, BalloonTextStyle.SOLID)
        fixture.panel.setShapeMode(ShapeKind.BALLOON)

        assertEquals(ShapeKind.BALLOON, fixture.panel.getSelectedShapeKind())
        assertEquals(BalloonTextStyle.OUTLINE, fixture.panel.getTextStyleFor(ShapeKind.TEXT))
        assertEquals(BalloonTextStyle.SOLID, fixture.panel.getTextStyleFor(ShapeKind.BALLOON))
        assertEquals(ShapeKind.RECTANGLE, fixture.panel.getSelectedDrawingShapeKind())

        fixture.panel.setTextStyleFor(ShapeKind.BALLOON, BalloonTextStyle.OUTLINE)

        assertEquals(BalloonTextStyle.OUTLINE, fixture.panel.getTextStyleFor(ShapeKind.TEXT))
        assertEquals(BalloonTextStyle.OUTLINE, fixture.panel.getTextStyleFor(ShapeKind.BALLOON))

        fixture.panel.setTextStyleFor(ShapeKind.TEXT, BalloonTextStyle.SOLID)

        assertEquals(BalloonTextStyle.SOLID, fixture.panel.getTextStyleFor(ShapeKind.TEXT))
        assertEquals(BalloonTextStyle.OUTLINE, fixture.panel.getTextStyleFor(ShapeKind.BALLOON))

        fixture.panel.activateLastDrawingShapeMode()

        assertEquals(ShapeKind.RECTANGLE, fixture.panel.getSelectedShapeKind())
        assertEquals(BalloonTextStyle.SOLID, fixture.panel.getTextStyleFor(ShapeKind.TEXT))
        assertEquals(BalloonTextStyle.OUTLINE, fixture.panel.getTextStyleFor(ShapeKind.BALLOON))
    }

    @Test
    fun `pass through mode is persisted so drawings stay visible while editing code`() {
        val fixture = panelFixture()

        assertEquals(false, fixture.panel.isInteractionPassThroughEnabled())
        assertEquals(false, fixture.stateService.isInteractionPassThroughEnabled())

        fixture.panel.setInteractionPassThroughEnabled(true)

        assertEquals(true, fixture.panel.isInteractionPassThroughEnabled())
        assertEquals(true, fixture.stateService.isInteractionPassThroughEnabled())

        val reloadedPanel = DrawingCanvasPanel(fixture.project, fixture.recentColorStore)
        assertEquals(true, reloadedPanel.isInteractionPassThroughEnabled())
    }

    @Test
    fun `text editor commits typed text on outside click`() {
        val fixture = panelFixture()
        val commits = mutableListOf<String?>()
        val openTextEditor = fixture.panel.javaClass.declaredMethods.firstOrNull { method ->
            method.name == "openTextEditor" &&
                method.parameterTypes.size == 2 &&
                method.parameterTypes[0] == Rectangle::class.java
        } ?: error("openTextEditor not found")
        openTextEditor.isAccessible = true

        openTextEditor.invoke(
            fixture.panel,
            Rectangle(20, 20, 120, 60),
            { text: String? -> commits += text } as (String?) -> Unit
        )

        val editor = fixture.panel.components.filterIsInstance<JTextArea>().single()
        editor.text = "Posted from click"

        val finishOutsideClick = fixture.panel.javaClass.declaredMethods.firstOrNull { method ->
            method.name == "finishActiveTextEditorFromOutsideClick"
        } ?: error("finishActiveTextEditorFromOutsideClick not found")
        finishOutsideClick.isAccessible = true

        val handled = finishOutsideClick.invoke(fixture.panel, Point(200, 200))

        assertEquals(true, handled)
        assertEquals(listOf("Posted from click"), commits, "Clicking outside should commit typed text")
        assertNull(fixture.panel.components.filterIsInstance<JTextArea>().firstOrNull(), "Editor should be removed after commit")
    }

    @Test
    fun `text editor commits typed text on focus loss and cancels blank text`() {
        val fixture = panelFixture()
        val commits = mutableListOf<String?>()
        val openTextEditor = fixture.panel.javaClass.declaredMethods.firstOrNull { method ->
            method.name == "openTextEditor" &&
                method.parameterTypes.size == 2 &&
                method.parameterTypes[0] == Rectangle::class.java
        } ?: error("openTextEditor not found")
        openTextEditor.isAccessible = true

        openTextEditor.invoke(
            fixture.panel,
            Rectangle(20, 20, 120, 60),
            { text: String? -> commits += text } as (String?) -> Unit
        )

        val editor = fixture.panel.components.filterIsInstance<JTextArea>().single()
        editor.text = "Posted from focus"
        editor.focusListeners.forEach { listener ->
            listener.focusLost(FocusEvent(editor, FocusEvent.FOCUS_LOST))
        }

        openTextEditor.invoke(
            fixture.panel,
            Rectangle(20, 20, 120, 60),
            { text: String? -> commits += text } as (String?) -> Unit
        )
        val blankEditor = fixture.panel.components.filterIsInstance<JTextArea>().single()
        blankEditor.text = "   "
        blankEditor.focusListeners.forEach { listener ->
            listener.focusLost(FocusEvent(blankEditor, FocusEvent.FOCUS_LOST))
        }

        assertEquals(
            listOf("Posted from focus", null),
            commits,
            "Focus loss should commit typed text but keep blank text as cancel"
        )
        assertNull(fixture.panel.components.filterIsInstance<JTextArea>().firstOrNull(), "Editor should be removed after finish")
    }

    @Test
    fun `text editor keyboard actions commit cancel and insert multiline text`() {
        val fixture = panelFixture()
        val commits = mutableListOf<String?>()
        val openTextEditor = fixture.panel.javaClass.declaredMethods.firstOrNull { method ->
            method.name == "openTextEditor" &&
                method.parameterTypes.size == 2 &&
                method.parameterTypes[0] == Rectangle::class.java
        } ?: error("openTextEditor not found")
        openTextEditor.isAccessible = true

        openTextEditor.invoke(
            fixture.panel,
            Rectangle(20, 20, 120, 60),
            { text: String? -> commits += text } as (String?) -> Unit
        )
        val multilineEditor = fixture.panel.components.filterIsInstance<JTextArea>().single()
        multilineEditor.text = "Line one"
        multilineEditor.caretPosition = multilineEditor.text.length
        multilineEditor.actionMap.get("newLineBalloonText").actionPerformed(
            ActionEvent(multilineEditor, ActionEvent.ACTION_PERFORMED, "newLineBalloonText")
        )
        multilineEditor.text += "Line two"
        multilineEditor.actionMap.get("commitBalloonText").actionPerformed(
            ActionEvent(multilineEditor, ActionEvent.ACTION_PERFORMED, "commitBalloonText")
        )

        openTextEditor.invoke(
            fixture.panel,
            Rectangle(20, 20, 120, 60),
            { text: String? -> commits += text } as (String?) -> Unit
        )
        val cancelEditor = fixture.panel.components.filterIsInstance<JTextArea>().single()
        cancelEditor.text = "Should not post"
        cancelEditor.actionMap.get("cancelBalloonText").actionPerformed(
            ActionEvent(cancelEditor, ActionEvent.ACTION_PERFORMED, "cancelBalloonText")
        )

        assertEquals(listOf("Line one\nLine two", null), commits)
        assertNull(fixture.panel.components.filterIsInstance<JTextArea>().firstOrNull(), "Editor should be removed after keyboard action")
    }

    private fun panelFixture(): PanelFixture {
        var stateService: DrawingStateService? = null
        val project = testProjectWithService {
            stateService ?: error("State service was requested before it was created")
        }
        stateService = DrawingStateService(project)
        val recentColorStore = RecentColorStore(project)

        return PanelFixture(
            project = project,
            panel = DrawingCanvasPanel(project, recentColorStore),
            recentColorStore = recentColorStore,
            stateService = stateService
        )
    }

    private fun testProjectWithService(serviceProvider: () -> DrawingStateService): Project {
        return proxyFor(Project::class.java) { methodName, returnType, args, proxy ->
            when (methodName) {
                "getBasePath" -> projectBasePath
                "getName" -> "DrawingPanelStateTestProject"
                "isDisposed" -> false
                "getService", "getServiceIfCreated" -> {
                    val serviceClass = args?.firstOrNull() as? Class<*>
                    if (serviceClass == DrawingStateService::class.java) {
                        serviceProvider()
                    } else {
                        defaultReturnValue(returnType)
                    }
                }
                "toString" -> "DrawingPanelStateTestProject($projectBasePath)"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> defaultReturnValue(returnType)
            }
        }
    }

    private data class PanelFixture(
        val project: Project,
        val panel: DrawingCanvasPanel,
        val recentColorStore: RecentColorStore,
        val stateService: DrawingStateService
    )

    private data class ToolCase(
        val label: String,
        val expectedTool: DrawingToolMode,
        val expectedShape: ShapeKind? = null,
        val expectedTextStyle: BalloonTextStyle? = null,
        val activate: DrawingCanvasPanel.() -> Unit
    )

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
