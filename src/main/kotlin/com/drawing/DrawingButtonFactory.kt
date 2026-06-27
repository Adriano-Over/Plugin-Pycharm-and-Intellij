package com.drawing

import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.Insets
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.JButton

internal object DrawingMetrics {
    const val BAR_WIDTH = 104
    const val BUTTON_HEIGHT = 28
    const val HALF_BUTTON_WIDTH = 49
    const val STATUS_HEIGHT = 24
    const val COLOR_STATUS_HEIGHT = 20
    const val HEADER_BUTTON_SIZE = 20
}

internal class DrawingButtonFactory(
    private val restoreToolbarStyles: () -> Unit
) {
    fun createButton(text: String, onClick: () -> Unit): JButton {
        return SolidColorButton(text).apply {
            preferredSize = Dimension(DrawingMetrics.BAR_WIDTH, DrawingMetrics.BUTTON_HEIGHT)
            maximumSize = Dimension(DrawingMetrics.BAR_WIDTH, DrawingMetrics.BUTTON_HEIGHT)
            font = Font("Dialog", Font.PLAIN, 12)
            isFocusPainted = false
            isOpaque = true
            background = Color(50, 50, 50)
            foreground = Color(220, 220, 220)
            border = BorderFactory.createLineBorder(Color(100, 100, 100), 1)
            installHoverFeedback(this)
            addActionListener { onClick() }
        }
    }

    fun createHalfButton(text: String, onClick: () -> Unit): JButton {
        return SolidColorButton(text).apply {
            preferredSize = Dimension(DrawingMetrics.HALF_BUTTON_WIDTH, DrawingMetrics.BUTTON_HEIGHT)
            minimumSize = Dimension(DrawingMetrics.HALF_BUTTON_WIDTH, DrawingMetrics.BUTTON_HEIGHT)
            maximumSize = Dimension(DrawingMetrics.HALF_BUTTON_WIDTH, DrawingMetrics.BUTTON_HEIGHT)
            font = Font("Dialog", Font.PLAIN, 11)
            isFocusPainted = false
            isOpaque = true
            background = Color(50, 50, 50)
            foreground = Color(220, 220, 220)
            border = BorderFactory.createLineBorder(Color(100, 100, 100), 1)
            installHoverFeedback(this)
            addActionListener { onClick() }
        }
    }

    fun createHeaderHideButton(onClick: () -> Unit): JButton {
        return SolidColorButton("x").apply {
            preferredSize = Dimension(DrawingMetrics.HEADER_BUTTON_SIZE, DrawingMetrics.HEADER_BUTTON_SIZE)
            minimumSize = Dimension(DrawingMetrics.HEADER_BUTTON_SIZE, DrawingMetrics.HEADER_BUTTON_SIZE)
            maximumSize = Dimension(DrawingMetrics.HEADER_BUTTON_SIZE, DrawingMetrics.HEADER_BUTTON_SIZE)
            margin = Insets(0, 0, 1, 0)
            font = Font("Dialog", Font.BOLD, 12)
            isFocusPainted = false
            isBorderPainted = true
            isOpaque = true
            background = Color(52, 52, 52)
            foreground = Color(220, 220, 220)
            border = BorderFactory.createLineBorder(Color(95, 95, 95), 1)
            toolTipText = "Hide Drawing. Use the Drawing sidebar button to show it again"
            installHoverFeedback(this)
            addActionListener { onClick() }
        }
    }

    fun installHoverFeedback(button: JButton) {
        button.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        button.addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) {
                if (!button.isEnabled) return
                button.border = BorderFactory.createLineBorder(Color(190, 190, 190), 1)
            }

            override fun mouseExited(e: MouseEvent) {
                restoreToolbarStyles()
            }
        })
    }
}
