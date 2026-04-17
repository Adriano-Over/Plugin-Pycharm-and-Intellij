package com.floatbar

import java.awt.Color

/**
 * Document-anchored point.
 *
 * line/column identify the nearest logical document position.
 * dx/dy are pixel offsets from editor.logicalPositionToXY(line, column).
 */
data class AnchorPoint(
    var line: Int,
    var column: Int,
    var dx: Int,
    var dy: Int
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
