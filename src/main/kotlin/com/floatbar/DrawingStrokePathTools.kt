package com.floatbar

import java.awt.Point
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

class DrawingStrokePathTools(
    private val eraseRadius: Double,
    private val freehandSimplifyTolerancePx: Double,
    private val freehandSimplifyMinPoints: Int,
    private val toViewPoint: (AnchorPoint) -> Point?
) {
    fun buildEraseSamples(from: Point, to: Point): List<Point> {
        val spacing = max(6.0, eraseRadius * 1.35)
        val distance = from.distance(to)
        val steps = max(1, ceil(distance / spacing).toInt())
        val points = ArrayList<Point>(steps + 1)
        for (i in 0..steps) {
            val t = i.toDouble() / steps
            points += Point(
                (from.x + (to.x - from.x) * t).roundToInt(),
                (from.y + (to.y - from.y) * t).roundToInt()
            )
        }
        return points
    }

    fun buildDrawSamples(from: Point, to: Point): List<Point> {
        val distance = from.distance(to)
        val steps = max(1, ceil(distance / 2.5).toInt())
        val points = ArrayList<Point>(steps)
        for (i in 1..steps) {
            val t = i.toDouble() / steps
            points += Point(
                (from.x + (to.x - from.x) * t).roundToInt(),
                (from.y + (to.y - from.y) * t).roundToInt()
            )
        }
        return points
    }

    fun simplifyFreehandStrokeInPlace(stroke: StrokePath): Boolean {
        if (stroke.kind != null) return false
        if (stroke.filled) return false
        if (stroke.points.size < freehandSimplifyMinPoints) return false

        val simplified = simplifyFreehandAnchors(stroke.points, freehandSimplifyTolerancePx)
        if (simplified.size < 2 || simplified.size >= stroke.points.size) return false

        stroke.points.clear()
        stroke.points.addAll(simplified)
        return true
    }

    private fun simplifyFreehandAnchors(
        points: List<AnchorPoint>,
        tolerancePx: Double
    ): MutableList<AnchorPoint> {
        if (points.size < 3) {
            return points.map { it.copy() }.toMutableList()
        }

        val resolved = ArrayList<Pair<AnchorPoint, Point>>(points.size)
        for (anchor in points) {
            val view = toViewPoint(anchor) ?: return points.map { it.copy() }.toMutableList()
            resolved += anchor.copy() to view
        }

        val keep = BooleanArray(resolved.size)
        keep[0] = true
        keep[resolved.lastIndex] = true

        simplifySegmentRdp(resolved, 0, resolved.lastIndex, tolerancePx, keep)

        val result = mutableListOf<AnchorPoint>()
        for (i in resolved.indices) {
            if (keep[i]) result += resolved[i].first
        }

        return if (result.size >= 2) result else points.map { it.copy() }.toMutableList()
    }

    private fun simplifySegmentRdp(
        points: List<Pair<AnchorPoint, Point>>,
        start: Int,
        end: Int,
        tolerancePx: Double,
        keep: BooleanArray
    ) {
        if (end <= start + 1) return

        val a = points[start].second
        val b = points[end].second

        var maxDistance = -1.0
        var maxIndex = -1

        for (i in start + 1 until end) {
            val p = points[i].second
            val distance = perpendicularDistance(p, a, b)
            if (distance > maxDistance) {
                maxDistance = distance
                maxIndex = i
            }
        }

        if (maxIndex >= 0 && maxDistance > tolerancePx) {
            keep[maxIndex] = true
            simplifySegmentRdp(points, start, maxIndex, tolerancePx, keep)
            simplifySegmentRdp(points, maxIndex, end, tolerancePx, keep)
        }
    }

    private fun perpendicularDistance(p: Point, a: Point, b: Point): Double {
        val dx = (b.x - a.x).toDouble()
        val dy = (b.y - a.y).toDouble()

        if (dx == 0.0 && dy == 0.0) return p.distance(a)

        val t = (((p.x - a.x) * dx) + ((p.y - a.y) * dy)) / (dx * dx + dy * dy)
        val clamped = t.coerceIn(0.0, 1.0)
        val projX = a.x + clamped * dx
        val projY = a.y + clamped * dy
        return hypot(p.x - projX, p.y - projY)
    }
}
