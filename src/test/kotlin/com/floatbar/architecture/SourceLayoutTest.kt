package com.floatbar.architecture

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class SourceLayoutTest {
    @Test
    fun `manual regression checks do not live in production sources`() {
        val productionSourceRoot = Path.of("src", "main", "kotlin")
        if (!Files.exists(productionSourceRoot)) return

        val regressionChecks = Files.walk(productionSourceRoot).use { paths ->
            paths
                .filter { Files.isRegularFile(it) }
                .filter { it.fileName.toString().endsWith("RegressionChecks.kt") }
                .map { productionSourceRoot.relativize(it).toString() }
                .toList()
        }

        assertTrue(
            regressionChecks.isEmpty(),
            "Move manual regression checks out of src/main: $regressionChecks"
        )
    }

    @Test
    fun `drawing is exposed through the side tool window only`() {
        val pluginXml = Files.readString(Path.of("src", "main", "resources", "META-INF", "plugin.xml"))

        assertTrue(pluginXml.contains("""<toolWindow"""))
        assertTrue(pluginXml.contains("""<name>Drawing</name>"""))
        assertTrue(pluginXml.contains("""factoryClass="com.floatbar.FloatingBarToolWindowFactory""""))
        assertTrue(pluginXml.contains("""icon="/icons/floatbar-pencil.svg""""))
        assertTrue(Files.exists(Path.of("src", "main", "resources", "icons", "floatbar-pencil.svg")))
        assertFalse(pluginXml.contains("statusBarWidgetFactory"))
        assertFalse(pluginXml.contains("FloatBarStatusBarWidget"))
        assertFalse(pluginXml.contains("com.floatbar.ToggleFloatBar"))
        assertFalse(pluginXml.contains("FloatingBarStartupActivity"))
    }
}
