package com.floatbar

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.JPanel
import kotlin.math.ceil

class PalettePanel(
    initialColor: Color,
    private val onColorChanged: (Color) -> Unit
) : JPanel() {

    private val columns = 24
    private val rows = 10
    private var selectedColor: Color = initialColor

    init {
        preferredSize = Dimension(460, 260)
        minimumSize = Dimension(280, 180)
        border = BorderFactory.createLineBorder(Color(180, 180, 180), 1)
        isOpaque = true
        background = Color(60, 60, 60)

        val handler = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                updateFromPoint(e.point)
            }

            override fun mouseDragged(e: MouseEvent) {
                updateFromPoint(e.point)
            }
        }

        addMouseListener(handler)
        addMouseMotionListener(handler)
    }

    fun setSelectedColor(color: Color) {
        selectedColor = color
        repaint()
    }

    private fun updateFromPoint(point: Point) {
        val color = colorAt(point.x, point.y)
        selectedColor = color
        onColorChanged(color)
        repaint()
    }

    private fun colorAt(x: Int, y: Int): Color {
        val cellWidth = width.toDouble() / columns.toDouble()
        val cellHeight = height.toDouble() / rows.toDouble()

        val col = (x / cellWidth).toInt().coerceIn(0, columns - 1)
        val row = (y / cellHeight).toInt().coerceIn(0, rows - 1)

        return paletteColor(col, row)
    }

    private fun paletteColor(col: Int, row: Int): Color {
        if (col == 0) {
            val gray = (255 - ((row.toDouble() / (rows - 1)) * 255.0).toInt()).coerceIn(0, 255)
            return Color(gray, gray, gray)
        }

        val hue = (col - 1).toFloat() / (columns - 2).toFloat()
        val saturation = (0.15f + (row.toFloat() / (rows - 1).toFloat()) * 0.85f).coerceIn(0f, 1f)
        val brightness = (1.0f - (row.toFloat() / (rows - 1).toFloat()) * 0.45f).coerceIn(0f, 1f)

        return Color.getHSBColor(hue, saturation, brightness)
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)

        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        val cellWidth = width.toDouble() / columns.toDouble()
        val cellHeight = height.toDouble() / rows.toDouble()

        for (row in 0 until rows) {
            for (col in 0 until columns) {
                val x = (col * cellWidth).toInt()
                val y = (row * cellHeight).toInt()
                val w = ceil(cellWidth).toInt()
                val h = ceil(cellHeight).toInt()

                g2.color = paletteColor(col, row)
                g2.fillRect(x, y, w, h)
                g2.color = Color(255, 255, 255, 70)
                g2.drawRect(x, y, w, h)
            }
        }

        val selected = findSelectedCell()
        if (selected != null) {
            val x = (selected.first * cellWidth).toInt()
            val y = (selected.second * cellHeight).toInt()
            val w = ceil(cellWidth).toInt()
            val h = ceil(cellHeight).toInt()

            g2.color = Color.WHITE
            g2.stroke = BasicStroke(2f)
            g2.drawRect(x + 1, y + 1, w - 3, h - 3)
            g2.color = Color.BLACK
            g2.stroke = BasicStroke(1f)
            g2.drawRect(x + 3, y + 3, w - 7, h - 7)
        }
    }

    private fun findSelectedCell(): Pair<Int, Int>? {
        for (row in 0 until rows) {
            for (col in 0 until columns) {
                val c = paletteColor(col, row)
                if (c.red == selectedColor.red && c.green == selectedColor.green && c.blue == selectedColor.blue) {
                    return col to row
                }
            }
        }
        return null
    }
}