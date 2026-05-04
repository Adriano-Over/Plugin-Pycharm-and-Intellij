package com.floatbar

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

private const val UNSET_FLOATING_BAR_POSITION = Int.MIN_VALUE

data class SavedPoint(
    var line: Int = 0,
    var column: Int = 0,
    var dx: Int = 0,
    var dy: Int = 0,
    var offset: Int = 0,
    var outsideCode: Boolean = false,
    var afterLineEndPx: Int = 0,
    // Legacy field kept only so older XML can still deserialize.
    var x: Int = 0
)

data class SavedStroke(
    var color: Int = 0,
    var width: Float = 3.5f,
    var points: MutableList<SavedPoint> = mutableListOf(),
    var filled: Boolean = false,
    var kind: String? = null
)

data class SavedFileDrawing(
    var filePath: String = "",
    var strokes: MutableList<SavedStroke> = mutableListOf()
)

data class DrawingState(
    var files: MutableList<SavedFileDrawing> = mutableListOf(),
    var recentColors: MutableList<Int> = mutableListOf(),
    var selectedColorRgb: Int = -65536,
    var gridEnabled: Boolean = true,
    var floatingBarX: Int = UNSET_FLOATING_BAR_POSITION,
    var floatingBarY: Int = UNSET_FLOATING_BAR_POSITION
)

@Service(Service.Level.PROJECT)
@State(
    name = "FloatBarDrawingState",
    storages = [Storage("floatbar-drawings.xml")]
)
class FloatBarDrawingStateService : PersistentStateComponent<DrawingState> {

    private var state = DrawingState()

    override fun getState(): DrawingState = state

    override fun loadState(state: DrawingState) {
        this.state = state
    }

    fun getStrokes(filePath: String): MutableList<SavedStroke> {
        return state.files.firstOrNull { it.filePath == filePath }?.strokes ?: mutableListOf()
    }

    fun setStrokes(filePath: String, strokes: List<SavedStroke>) {
        val existing = state.files.firstOrNull { it.filePath == filePath }

        if (strokes.isEmpty()) {
            if (existing != null) {
                state.files.remove(existing)
            }
            return
        }

        if (existing != null) {
            existing.strokes = strokes.toMutableList()
        } else {
            state.files.add(
                SavedFileDrawing(
                    filePath = filePath,
                    strokes = strokes.toMutableList()
                )
            )
        }
    }

    fun getRecentColors(): MutableList<Int> = state.recentColors

    fun setRecentColors(colors: List<Int>) {
        state.recentColors = colors.toMutableList()
    }

    fun getSelectedColorRgb(): Int = state.selectedColorRgb

    fun setSelectedColorRgb(rgb: Int) {
        state.selectedColorRgb = rgb
    }

    fun isGridEnabled(): Boolean = state.gridEnabled

    fun setGridEnabled(enabled: Boolean) {
        state.gridEnabled = enabled
    }

    fun getFloatingBarLocation(): Pair<Int, Int>? {
        val x = state.floatingBarX
        val y = state.floatingBarY
        return if (x == UNSET_FLOATING_BAR_POSITION || y == UNSET_FLOATING_BAR_POSITION) {
            null
        } else {
            x to y
        }
    }

    fun setFloatingBarLocation(x: Int, y: Int) {
        state.floatingBarX = x
        state.floatingBarY = y
    }
}
