package com.floatbar

import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.Insets
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.JButton

internal object ColorDialogButtonFactory {
    fun createActionButton(
        text: String,
        backgroundColor: Color = ColorDialogTheme.button,
        hoverBackgroundColor: Color = ColorDialogTheme.buttonHover,
        borderColor: Color = ColorDialogTheme.border,
        hoverBorderColor: Color = ColorDialogTheme.accentHover,
        onClick: () -> Unit
    ): JButton {
        return createButton(
            text = text,
            size = Dimension(108, 28),
            fontSize = 12,
            margin = Insets(2, 10, 2, 10),
            backgroundColor = backgroundColor,
            hoverBackgroundColor = hoverBackgroundColor,
            borderColor = borderColor,
            hoverBorderColor = hoverBorderColor,
            onClick = onClick
        )
    }

    fun createCompactActionButton(text: String, onClick: () -> Unit): JButton {
        return createButton(
            text = text,
            size = Dimension(55, 24),
            fontSize = 9,
            margin = Insets(1, 3, 1, 3),
            backgroundColor = ColorDialogTheme.button,
            hoverBackgroundColor = ColorDialogTheme.buttonHover,
            borderColor = ColorDialogTheme.border,
            hoverBorderColor = ColorDialogTheme.accentHover,
            onClick = onClick
        )
    }

    private fun createButton(
        text: String,
        size: Dimension,
        fontSize: Int,
        margin: Insets,
        backgroundColor: Color,
        hoverBackgroundColor: Color,
        borderColor: Color,
        hoverBorderColor: Color,
        onClick: () -> Unit
    ): JButton {
        return SolidColorButton(text).apply {
            preferredSize = size
            maximumSize = size
            font = Font("Dialog", Font.PLAIN, fontSize)
            isFocusPainted = false
            isOpaque = false
            isContentAreaFilled = false
            background = backgroundColor
            foreground = ColorDialogTheme.text
            border = BorderFactory.createLineBorder(borderColor, 1)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            this.margin = margin
            addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) {
                    if (!isEnabled) return
                    background = hoverBackgroundColor
                    border = BorderFactory.createLineBorder(hoverBorderColor, 1)
                }

                override fun mouseExited(e: MouseEvent) {
                    background = backgroundColor
                    border = BorderFactory.createLineBorder(borderColor, 1)
                }
            })
            addActionListener { onClick() }
        }
    }
}
