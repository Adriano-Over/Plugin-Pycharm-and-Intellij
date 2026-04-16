package com.floatbar

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.WindowManager
import java.awt.Frame
import javax.swing.JOptionPane
import javax.swing.SwingUtilities

@Service(Service.Level.PROJECT)
class FloatBarService(
    private val project: Project
) : Disposable {

    private var bar: FloatingBar? = null
    private val visibilityListeners = linkedMapOf<Int, (Boolean) -> Unit>()
    private var nextListenerId = 0

    private fun getOrCreateBar(): FloatingBar {
        val existing = bar
        if (existing != null) return existing

        val owner = (WindowManager.getInstance().suggestParentWindow(project) as? Frame)
            ?: JOptionPane.getRootFrame()

        return FloatingBar(owner, project).also { created ->
            visibilityListeners.values.forEach(created::addVisibilityListener)
            bar = created
        }
    }

    fun toggle() {
        SwingUtilities.invokeLater {
            getOrCreateBar().toggle()
        }
    }

    fun addVisibilityListener(listener: (Boolean) -> Unit): VisibilitySubscription {
        val id = nextListenerId++
        visibilityListeners[id] = listener
        bar?.addVisibilityListener(listener)
        return VisibilitySubscription {
            val removed = visibilityListeners.remove(id)
            if (removed != null) {
                bar?.removeVisibilityListener(removed)
            }
        }
    }

    override fun dispose() {
        bar?.dispose()
        bar = null
        visibilityListeners.clear()
    }
}
