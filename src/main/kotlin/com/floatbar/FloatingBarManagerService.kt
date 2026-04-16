package com.floatbar

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.WindowManager
import java.awt.Frame
import javax.swing.SwingUtilities

@Service(Service.Level.PROJECT)
class FloatingBarManagerService(
    private val project: Project
) {
    private var floatingBar: FloatingBar? = null

    fun getOrCreate(): FloatingBar? {
        val existing = floatingBar
        if (existing != null) return existing

        val frame = WindowManager.getInstance().getFrame(project) as? Frame ?: return null
        val created = FloatingBar(frame, project)
        floatingBar = created
        return created
    }

    fun showDefault() {
        SwingUtilities.invokeLater {
            val bar = getOrCreate() ?: return@invokeLater
            bar.activateByDefault()
            bar.isVisible = true
        }
    }

    fun showBar() {
        SwingUtilities.invokeLater {
            getOrCreate()?.showBar()
        }
    }

    fun hideBar() {
        SwingUtilities.invokeLater {
            floatingBar?.hideBar()
        }
    }

    fun toggleBar() {
        SwingUtilities.invokeLater {
            val bar = getOrCreate() ?: return@invokeLater
            if (bar.isVisible) bar.hideBar() else bar.showBar()
        }
    }
}
