package com.floatbar.model

import com.floatbar.AnchorPoint
import com.floatbar.StrokePath
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import java.awt.Color

class StrokePathIdentityTest {
    @Test
    fun `deep copy preserves stable stroke id`() {
        val original = StrokePath(
            color = Color.RED,
            width = 2f,
            points = mutableListOf(AnchorPoint(0, 0, 10, 10))
        )

        val copy = original.deepCopy()

        assertEquals(original.id, copy.id)
    }

    @Test
    fun `new strokes receive distinct ids`() {
        val first = StrokePath(color = Color.RED, width = 2f)
        val second = StrokePath(color = Color.BLUE, width = 2f)

        assertNotEquals(first.id, second.id)
    }

    @Test
    fun `deep copy detaches mutable point list`() {
        val original = StrokePath(
            color = Color.RED,
            width = 2f,
            points = mutableListOf(AnchorPoint(0, 0, 10, 10))
        )
        val copy = original.deepCopy()

        copy.points += AnchorPoint(0, 0, 20, 20)

        assertEquals(1, original.points.size)
        assertEquals(2, copy.points.size)
    }
}
