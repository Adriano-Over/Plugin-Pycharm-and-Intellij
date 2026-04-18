package com.floatbar

import java.awt.Color

/** Framework-agnostic regression checks for stable stroke identity semantics. */
object StrokePathIdentityRegressionChecks {

    fun runAll() {
        deepCopyPreservesStableStrokeId()
        newStrokesReceiveDistinctIds()
        deepCopyDetachesMutablePointList()
    }

    fun deepCopyPreservesStableStrokeId() {
        val original = StrokePath(
            color = Color.RED,
            width = 2f,
            points = mutableListOf(AnchorPoint(0, 0, 10, 10))
        )

        val copy = original.deepCopy()

        check(original.id == copy.id)
    }

    fun newStrokesReceiveDistinctIds() {
        val first = StrokePath(color = Color.RED, width = 2f)
        val second = StrokePath(color = Color.BLUE, width = 2f)

        check(first.id != second.id)
    }

    fun deepCopyDetachesMutablePointList() {
        val original = StrokePath(
            color = Color.RED,
            width = 2f,
            points = mutableListOf(AnchorPoint(0, 0, 10, 10))
        )
        val copy = original.deepCopy()

        copy.points += AnchorPoint(0, 0, 20, 20)

        check(original.points.size == 1)
        check(copy.points.size == 2)
    }
}
