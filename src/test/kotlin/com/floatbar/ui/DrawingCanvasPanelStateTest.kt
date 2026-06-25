package com.floatbar.ui

import com.floatbar.BalloonTextStyle
import com.floatbar.DrawingCanvasPanel
import com.floatbar.FloatBarDrawingStateService
import com.floatbar.FloatBarToolMode
import com.floatbar.RecentColorStore
import com.floatbar.ShapeKind
import com.intellij.openapi.project.Project
import java.awt.Color
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DrawingCanvasPanelStateTest {
    private val projectBasePath = "C:/work/floatbar-project"

    @Test
    fun `changing selected color preserves the active and persisted tool in every mode`() {
        val fixture = panelFixture()
        val cases = listOf(
            ToolCase("select tool", FloatBarToolMode.SELECT) {
                setSelectMode()
            },
            ToolCase("draw tool", FloatBarToolMode.DRAW) {
                setDrawingMode()
            },
            ToolCase("erase tool", FloatBarToolMode.ERASE) {
                setErasingMode()
            },
            ToolCase("fill tool", FloatBarToolMode.FILL) {
                setFillMode()
            },
            ToolCase("document shape", FloatBarToolMode.SHAPES, expectedShape = ShapeKind.DOCUMENT) {
                setShapeMode(ShapeKind.DOCUMENT)
            },
            ToolCase("right brace shape", FloatBarToolMode.SHAPES, expectedShape = ShapeKind.RIGHT_BRACE) {
                setShapeMode(ShapeKind.RIGHT_BRACE)
            },
            ToolCase(
                label = "solid text",
                expectedTool = FloatBarToolMode.SHAPES,
                expectedShape = ShapeKind.TEXT,
                expectedTextStyle = BalloonTextStyle.SOLID
            ) {
                setTextStyleFor(ShapeKind.TEXT, BalloonTextStyle.SOLID)
                setShapeMode(ShapeKind.TEXT)
            },
            ToolCase(
                label = "outline balloon",
                expectedTool = FloatBarToolMode.SHAPES,
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

        FloatBarToolMode.entries.forEachIndexed { index, toolMode ->
            fixture.stateService.setSelectedToolMode(toolMode)
            recentColors.remember(Color((20 + index * 50) % 255, 80, 180))

            assertEquals(toolMode, fixture.stateService.getSelectedToolMode(), "$toolMode should survive recent color save")
        }
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

        assertEquals(FloatBarToolMode.SHAPES, fixture.panel.getCurrentToolMode())
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

    private fun panelFixture(): PanelFixture {
        var stateService: FloatBarDrawingStateService? = null
        val project = testProjectWithService {
            stateService ?: error("State service was requested before it was created")
        }
        stateService = FloatBarDrawingStateService(project)
        val recentColorStore = RecentColorStore(project)

        return PanelFixture(
            project = project,
            panel = DrawingCanvasPanel(project, recentColorStore),
            recentColorStore = recentColorStore,
            stateService = stateService
        )
    }

    private fun testProjectWithService(serviceProvider: () -> FloatBarDrawingStateService): Project {
        return proxyFor(Project::class.java) { methodName, returnType, args, proxy ->
            when (methodName) {
                "getBasePath" -> projectBasePath
                "getName" -> "FloatBarPanelStateTestProject"
                "isDisposed" -> false
                "getService", "getServiceIfCreated" -> {
                    val serviceClass = args?.firstOrNull() as? Class<*>
                    if (serviceClass == FloatBarDrawingStateService::class.java) {
                        serviceProvider()
                    } else {
                        defaultReturnValue(returnType)
                    }
                }
                "toString" -> "FloatBarPanelStateTestProject($projectBasePath)"
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
        val stateService: FloatBarDrawingStateService
    )

    private data class ToolCase(
        val label: String,
        val expectedTool: FloatBarToolMode,
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
