package com.drawing

import java.awt.Color
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

internal object ColorClipboardActions {
    fun hexText(color: Color): String = ColorDialogTheme.toHexColor(color)

    fun rgbText(color: Color): String = ColorDialogTheme.toRgbText(color)

    fun copyToSystemClipboard(value: String) {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(value), null)
    }
}
