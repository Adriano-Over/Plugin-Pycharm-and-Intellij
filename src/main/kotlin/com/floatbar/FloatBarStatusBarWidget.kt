package com.floatbar

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.CustomStatusBarWidget
import com.intellij.openapi.wm.StatusBar
import java.awt.Color
import java.awt.Cursor
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.JComponent
import javax.swing.JLabel

class FloatBarStatusBarWidget(
    private val project: Project
) : CustomStatusBarWidget {

    private val label = JLabel("FloatBar OFF").apply {
        border = BorderFactory.createEmptyBorder(0, 6, 0, 6)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        isOpaque = false
        toolTipText = "Show FloatBar"
    }
    private val baseFont = label.font

    private var statusBar: StatusBar? = null
    private var visible = false
    private val subscription: VisibilitySubscription

    init {
        label.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                project.service<FloatBarService>().toggle()
            }
        })

        subscription = project.service<FloatBarService>().addVisibilityListener { isVisible ->
            visible = isVisible
            updateText()
            statusBar?.updateWidget(ID())
        }
    }

    private fun updateText() {
        label.text = if (visible) "FloatBar ON" else "FloatBar OFF"
        label.toolTipText = if (visible) {
            "FloatBar is visible. Click to hide the floating toolbar."
        } else {
            "FloatBar is hidden. Click to show the floating toolbar."
        }
        label.foreground = if (visible) {
            Color(70, 145, 85)
        } else {
            Color(150, 150, 150)
        }
        label.font = baseFont.deriveFont(if (visible) Font.BOLD else Font.PLAIN)
    }

    override fun ID(): String = "FloatBarStatusBarWidget"

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
    }

    override fun dispose() {
        subscription.unsubscribe()
        statusBar = null
    }

    override fun getComponent(): JComponent = label
}
