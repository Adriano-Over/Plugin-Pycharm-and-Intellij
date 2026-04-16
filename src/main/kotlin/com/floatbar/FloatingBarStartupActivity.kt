package com.floatbar

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity

class FloatingBarStartupActivity : StartupActivity.DumbAware {
    override fun runActivity(project: Project) {
        project.getService(FloatingBarManagerService::class.java).showDefault()
    }
}
