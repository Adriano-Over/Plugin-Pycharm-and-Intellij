package com.drawing

import java.awt.Color
import java.util.concurrent.atomic.AtomicLong

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
 *
 * foldLayoutBaseY is intentionally treated as a runtime layout baseline. It lets a new
 * drawing stay visually stable when unrelated code above it is folded/unfolded during the
 * same editing session. It is not the drawing's real document anchor; the real anchor is
 * still line/offset/dx/dy.
 */
const val UNSET_FOLD_HIDDEN_HEIGHT_ABOVE = Int.MIN_VALUE
const val UNSET_FOLD_LAYOUT_BASE_Y = Int.MIN_VALUE
const val UNSET_STROKE_FOLD_LAYOUT_ANCHOR_LINE = Int.MIN_VALUE

data class AnchorPoint(
    var line: Int,
    var column: Int,
    var dx: Int,
    var dy: Int,
    var offset: Int = 0,
    var outsideCode: Boolean = false,
    var afterLineEndPx: Int = 0,
    var foldHiddenHeightAbove: Int = UNSET_FOLD_HIDDEN_HEIGHT_ABOVE,
    var foldLayoutBaseY: Int = UNSET_FOLD_LAYOUT_BASE_Y
)

private val strokeIdSequence = AtomicLong(1L)

private fun nextStrokeId(): Long = strokeIdSequence.getAndIncrement()

fun nextStrokeObjectGroupId(): Long = nextStrokeId()

data class StrokePath(
    val id: Long = nextStrokeId(),
    val color: Color,
    val width: Float,
    val points: MutableList<AnchorPoint> = mutableListOf(),
    val filled: Boolean = false,
    val kind: ShapeKind? = null,
    var objectGroupId: Long = 0L,
    var rigidObjectAnchor: Boolean = false,
    /**
     * Runtime-only baseline used to keep a whole stroke visually stable when unrelated
     * folded code above it expands/collapses. This avoids correcting each point by a
     * different amount and keeps freehand drawings from bending or drifting.
     */
    var foldLayoutAnchorLine: Int = UNSET_STROKE_FOLD_LAYOUT_ANCHOR_LINE,
    var foldLayoutAnchorBaseY: Int = UNSET_FOLD_LAYOUT_BASE_Y
) {
    fun deepCopy(): StrokePath = StrokePath(
        id = id,
        color = Color(color.red, color.green, color.blue, color.alpha),
        width = width,
        points = points.map { it.copy() }.toMutableList(),
        filled = filled,
        kind = kind,
        objectGroupId = objectGroupId,
        rigidObjectAnchor = rigidObjectAnchor,
        foldLayoutAnchorLine = foldLayoutAnchorLine,
        foldLayoutAnchorBaseY = foldLayoutAnchorBaseY
    )
}

fun StrokePath.usesRigidObjectAnchoring(): Boolean {
    return rigidObjectAnchor || kind?.usesRigidObjectAnchoring() == true
}

data class RasterFillPath(
    val id: Long = nextStrokeObjectGroupId(),
    val color: Color,
    val anchor: AnchorPoint,
    val width: Int,
    val height: Int,
    val pngBase64: String,
    var objectGroupId: Long = 0L
) {
    fun deepCopy(): RasterFillPath = RasterFillPath(
        id = id,
        color = Color(color.red, color.green, color.blue, color.alpha),
        anchor = anchor.copy(),
        width = width,
        height = height,
        pngBase64 = pngBase64,
        objectGroupId = objectGroupId
    )
}

enum class AnnotationKind {
    TEXT,
    BALLOON
}

data class AnnotationPath(
    val id: Long = nextStrokeObjectGroupId(),
    var text: String,
    var color: Color,
    var anchor: AnchorPoint,
    var width: Int,
    var height: Int,
    var kind: AnnotationKind,
    var style: BalloonTextStyle = BalloonTextStyle.SOLID,
    var objectGroupId: Long = 0L
) {
    fun deepCopy(): AnnotationPath = AnnotationPath(
        id = id,
        text = text,
        color = Color(color.red, color.green, color.blue, color.alpha),
        anchor = anchor.copy(),
        width = width,
        height = height,
        kind = kind,
        style = style,
        objectGroupId = objectGroupId
    )
}

data class CollapsedFoldRegionSnapshot(
    val startOffset: Int,
    val endOffset: Int,
    val placeholderPoint: java.awt.Point,
    val placeholderWidth: Int
)
