package com.drawing

import java.awt.Color
import java.awt.Cursor
import java.awt.GraphicsEnvironment
import java.awt.Robot
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JWindow
import javax.swing.SwingUtilities

class EyeDropperOverlay(
    private val onPicked: (Color) -> Unit,
    private val onClosed: (() -> Unit)? = null,
    private val onError: ((Throwable) -> Unit)? = null
) : JWindow() {

    init {
        background = Color(0, 0, 0, 1)
        isAlwaysOnTop = true
        cursor = Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR)

        val allBounds = GraphicsEnvironment.getLocalGraphicsEnvironment()
            .screenDevices
            .map { it.defaultConfiguration.bounds }
            .reduce { acc, rectangle -> acc.union(rectangle) }

        bounds = allBounds

        addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                try {
                    val color = Robot().getPixelColor(e.xOnScreen, e.yOnScreen)
                    closeOverlay()
                    onPicked(color)
                } catch (t: Throwable) {
                    closeOverlay()
                    onError?.invoke(t)
                }
            }
        })

        addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ESCAPE) {
                    closeOverlay()
                }
            }
        })
    }

    fun showOverlay() {
        isVisible = true
        toFront()
        requestFocus()
    }

    private fun closeOverlay() {
        if (isDisplayable) {
            dispose()
        }
        SwingUtilities.invokeLater {
            onClosed?.invoke()
        }
    }
}
