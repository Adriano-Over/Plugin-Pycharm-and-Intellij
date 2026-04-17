package com.floatbar

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

@Deprecated("Use FloatBarService directly; this facade only delegates for compatibility.")
class FloatingBarManagerService(
    private val project: Project
) {
    fun showDefault() {
        project.service<FloatBarService>().showDefault()
    }

    fun showBar() {
        project.service<FloatBarService>().showBar()
    }

    fun hideBar() {
        project.service<FloatBarService>().hideBar()
    }

    fun toggleBar() {
        project.service<FloatBarService>().toggle()
    }
}
