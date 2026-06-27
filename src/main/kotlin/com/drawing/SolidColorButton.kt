package com.drawing

import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.JButton

internal class SolidColorButton(text: String = "") : JButton(text) {
    init {
        isOpaque = false
        isContentAreaFilled = false
    }

    override fun paintComponent(graphics: Graphics) {
        val g2 = graphics.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = background
            g2.fillRoundRect(0, 0, width, height, 6, 6)
        } finally {
            g2.dispose()
        }
        super.paintComponent(graphics)
    }
}
