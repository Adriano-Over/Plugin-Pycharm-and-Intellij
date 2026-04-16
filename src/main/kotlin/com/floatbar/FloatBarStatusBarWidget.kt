package com.floatbar

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.CustomStatusBarWidget
import com.intellij.openapi.wm.StatusBar
import java.awt.Cursor
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
        toolTipText = "Toggle FloatBar"
    }

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
