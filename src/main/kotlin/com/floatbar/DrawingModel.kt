package com.floatbar

import java.awt.Color

/**
 * Document-anchored point.
 *
 * Primary anchor modes:
 * - outsideCode = false: point is tied to a concrete document offset.
 * - outsideCode = true: point is tied to the visual end of a line and extends to the right
 *   into the empty area after the rendered code.
 *
 * line/column are cached for persistence/debugging.
 * dx/dy are pixel offsets from the inline anchor base position.
 * afterLineEndPx is used only when outsideCode = true.
 */
data class AnchorPoint(
    var line: Int,
    var column: Int,
    var dx: Int,
    var dy: Int,
    var offset: Int = 0,
    var outsideCode: Boolean = false,
    var afterLineEndPx: Int = 0
)

data class StrokePath(
    val color: Color,
    val width: Float,
    val points: MutableList<AnchorPoint> = mutableListOf(),
    val filled: Boolean = false,
    val kind: ShapeKind? = null
) {
    fun deepCopy(): StrokePath = StrokePath(
        color = Color(color.red, color.green, color.blue, color.alpha),
        width = width,
        points = points.map { it.copy() }.toMutableList(),
        filled = filled,
        kind = kind
    )
}
