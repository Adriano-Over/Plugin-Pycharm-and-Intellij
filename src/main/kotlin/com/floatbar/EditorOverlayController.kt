package com.floatbar

import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.VisibleAreaEvent
import com.intellij.openapi.editor.event.VisibleAreaListener
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import java.awt.Component
import java.awt.Rectangle
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.JLayeredPane
import javax.swing.JRootPane
import javax.swing.SwingUtilities

class EditorOverlayController(
    private val project: Project,
    private val canvasPanel: DrawingCanvasPanel,
    private val onOverlayChanged: (Boolean) -> Unit = {}
) : Disposable {

    private var overlayEnabled = false
    private var overlayInstalled = false
    private var currentEditor: Editor? = null
    private var currentRootPane: JRootPane? = null
    private var currentLayeredPane: JLayeredPane? = null
    private var currentEditorComponent: Component? = null

    private var editorComponentListener: ComponentAdapter? = null
    private var rootComponentListener: ComponentAdapter? = null
    private var visibleAreaListener: VisibleAreaListener? = null
    private var documentListener: DocumentListener? = null

    private val messageBusConnection = project.messageBus.connect(this)

    init {
        messageBusConnection.subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) {
                    if (overlayEnabled) {
                        bindToSelectedEditor()
                    }
                }
            }
        )
    }

    fun isEnabled(): Boolean = overlayEnabled

    fun isInstalled(): Boolean = overlayInstalled

    private fun setOverlayInstalled(installed: Boolean) {
        if (overlayInstalled == installed) return
        overlayInstalled = installed
        onOverlayChanged(overlayEnabled)
    }

    fun toggle() {
        setEnabled(!overlayEnabled)
    }

    fun setEnabled(enabled: Boolean) {
        overlayEnabled = enabled
        if (enabled) {
            bindToSelectedEditor()
        } else {
            uninstallOverlay()
        }
    }

    fun uninstallOverlay() {
        overlayEnabled = false
        detachOverlayFromEditor()
        onOverlayChanged(false)
    }

    private fun detachOverlayFromEditor() {
        setOverlayInstalled(false)
        detachListeners()

        canvasPanel.parent?.let { parent ->
            parent.remove(canvasPanel)
            parent.revalidate()
            parent.repaint()
        }

        canvasPanel.unbindEditor()
        currentEditor = null
        currentRootPane = null
        currentLayeredPane = null
        currentEditorComponent = null
    }

    private fun bindToSelectedEditor() {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: run {
            detachOverlayFromEditor()
            return
        }
        bindToEditor(editor)
    }

    private fun bindToEditor(editor: Editor) {
        if (currentEditor === editor && overlayInstalled) {
            updateOverlayBounds()
            canvasPanel.repaint()
            return
        }

        detachListeners()
        canvasPanel.parent?.remove(canvasPanel)

        val editorComponent = editor.contentComponent
        val rootPane = SwingUtilities.getRootPane(editorComponent) ?: run {
            detachOverlayFromEditor()
            return
        }
        val layeredPane = rootPane.layeredPane ?: run {
            detachOverlayFromEditor()
            return
        }

        currentEditor = editor
        currentRootPane = rootPane
        currentLayeredPane = layeredPane
        currentEditorComponent = editorComponent

        canvasPanel.bindEditor(editor)
        canvasPanel.isOpaque = false
        canvasPanel.background = java.awt.Color(0, 0, 0, 0)
        layeredPane.setLayer(canvasPanel, JLayeredPane.PALETTE_LAYER)
        layeredPane.add(canvasPanel)
        setOverlayInstalled(true)
        attachListeners(editor, rootPane)
        updateOverlayBounds()
    }

    private fun attachListeners(editor: Editor, rootPane: JRootPane) {
        visibleAreaListener = VisibleAreaListener { _: VisibleAreaEvent ->
            if (overlayInstalled && currentEditor === editor) {
                updateOverlayBounds()
                canvasPanel.repaint()
            }
        }.also { editor.scrollingModel.addVisibleAreaListener(it) }

        editorComponentListener = object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) = refresh(editor)
            override fun componentMoved(e: ComponentEvent) = refresh(editor)
        }.also { editor.contentComponent.addComponentListener(it) }

        rootComponentListener = object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) = refresh(editor)
        }.also { rootPane.addComponentListener(it) }

        documentListener = object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                if (overlayInstalled && currentEditor === editor) {
                    updateOverlayBounds()
                    canvasPanel.repaint()
                }
            }
        }.also { editor.document.addDocumentListener(it) }
    }

    private fun detachListeners() {
        val editor = currentEditor
        val rootPane = currentRootPane
        val editorComponent = currentEditorComponent

        if (editor != null) {
            visibleAreaListener?.let { editor.scrollingModel.removeVisibleAreaListener(it) }
            documentListener?.let { editor.document.removeDocumentListener(it) }
        }
        if (editorComponent != null) {
            editorComponentListener?.let { editorComponent.removeComponentListener(it) }
        }
        if (rootPane != null) {
            rootComponentListener?.let { rootPane.removeComponentListener(it) }
        }

        visibleAreaListener = null
        documentListener = null
        editorComponentListener = null
        rootComponentListener = null
    }

    private fun refresh(editor: Editor) {
        if (overlayInstalled && currentEditor === editor) {
            updateOverlayBounds()
        }
    }

    private fun updateOverlayBounds() {
        val editor = currentEditor ?: return
        val layeredPane = currentLayeredPane ?: return
        val editorComponent = currentEditorComponent ?: return

        val visibleArea = editor.scrollingModel.visibleArea
        val editorOnLayered = SwingUtilities.convertRectangle(editorComponent, visibleArea, layeredPane)
        val inset = 0
        val bounded = Rectangle(
            editorOnLayered.x + inset,
            editorOnLayered.y + inset,
            (editorOnLayered.width - inset * 2).coerceAtLeast(1),
            (editorOnLayered.height - inset * 2).coerceAtLeast(1)
        )

        canvasPanel.setBounds(bounded.x, bounded.y, bounded.width, bounded.height)
        layeredPane.revalidate()
        layeredPane.repaint()
    }

    override fun dispose() {
        uninstallOverlay()
    }
}
