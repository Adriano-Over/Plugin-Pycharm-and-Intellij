package com.floatbar

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import java.awt.Color

class RecentColorStore(
    private val project: Project,
    defaultColors: List<Color> = listOf(
        Color(255, 0, 0),
        Color(0, 120, 255),
        Color(0, 180, 120),
        Color(255, 180, 0)
    )
) {
    private val colors = mutableListOf<Color>()

    init {
        val saved = project.service<FloatBarDrawingStateService>().getRecentColors()
        if (saved.isNotEmpty()) {
            colors += saved.map { rgb ->
                val value = Color(rgb, true)
                Color(value.red, value.green, value.blue)
            }.take(MAX_COLORS)
        } else {
            colors += defaultColors.take(MAX_COLORS)
        }
    }

    fun snapshot(): List<Color> = colors.toList()

    fun remember(color: Color) {
        val opaque = Color(color.red, color.green, color.blue)
        colors.removeAll { it.red == opaque.red && it.green == opaque.green && it.blue == opaque.blue }
        colors.add(0, opaque)
        while (colors.size > MAX_COLORS) {
            colors.removeAt(colors.lastIndex)
        }
        save()
    }

    private fun save() {
        project.service<FloatBarDrawingStateService>().setRecentColors(colors.map { it.rgb })
    }

    private companion object {
        const val MAX_COLORS = 6
    }
}
