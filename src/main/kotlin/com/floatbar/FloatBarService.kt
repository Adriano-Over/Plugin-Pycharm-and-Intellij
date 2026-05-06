package com.floatbar

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
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
    private val stateService = project.service<FloatBarDrawingStateService>()
    private val visibilityListeners = linkedMapOf<Int, (Boolean) -> Unit>()
    private var nextListenerId = 0
    private var visible = stateService.isFloatingBarVisible()

    private fun handleVisibilityChanged(isVisible: Boolean) {
        if (visible == isVisible) return
        visible = isVisible
        stateService.setFloatingBarVisible(isVisible)
        visibilityListeners.values.forEach { it(isVisible) }
    }

    private fun getOrCreateBar(): FloatingBar {
        val existing = bar
        if (existing != null) return existing

        val owner = (WindowManager.getInstance().suggestParentWindow(project) as? Frame)
            ?: JOptionPane.getRootFrame()

        return FloatingBar(owner, project).also { created ->
            bar = created
            if (visible) {
                created.showBar()
            } else {
                created.hideBar()
            }
            created.addVisibilityListener(::handleVisibilityChanged)
        }
    }

    fun isVisible(): Boolean = visible

    fun showDefault() {
        SwingUtilities.invokeLater {
            getOrCreateBar().activateByDefault()
        }
    }

    fun showBar() {
        SwingUtilities.invokeLater {
            getOrCreateBar().showBar()
        }
    }

    fun hideBar() {
        SwingUtilities.invokeLater {
            val existing = bar
            if (existing != null) {
                existing.hideBar()
            } else {
                handleVisibilityChanged(false)
            }
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
        listener(visible)
        return VisibilitySubscription {
            visibilityListeners.remove(id)
        }
    }

    override fun dispose() {
        bar?.dispose()
        bar = null
        visibilityListeners.clear()
    }
}
