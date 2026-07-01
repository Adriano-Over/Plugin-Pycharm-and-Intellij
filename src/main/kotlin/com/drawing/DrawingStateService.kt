package com.drawing

import com.intellij.openapi.components.SerializablePersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import java.nio.file.Paths
import java.util.Locale

private const val UNSET_FLOATING_BAR_POSITION = Int.MIN_VALUE
private const val PROJECT_DIR_MACRO = "\$PROJECT_DIR$"

data class SavedPoint(
    var anchorStorageVersion: Int = 0,
    var line: Int = 0,
    var column: Int = 0,
    var dx: Int = 0,
    var dy: Int = 0,
    var offset: Int = 0,
    var outsideCode: Boolean = false,
    var afterLineEndPx: Int = 0,
    var foldHiddenHeightAbove: Int = UNSET_FOLD_HIDDEN_HEIGHT_ABOVE,
    // Legacy field kept only so older XML can still deserialize.
    var x: Int = 0
)

data class SavedStroke(
    var color: Int = 0,
    var width: Float = 3.5f,
    var points: MutableList<SavedPoint> = mutableListOf(),
    var filled: Boolean = false,
    var kind: String? = null,
    var objectGroupId: Long = 0L,
    var rigidObjectAnchor: Boolean = false,
    var annotationText: String? = null,
    var annotationTextStyle: String? = null,
    var annotationBoundsX: Int = 0,
    var annotationBoundsY: Int = 0,
    var annotationBoundsWidth: Int = 0,
    var annotationBoundsHeight: Int = 0
)

data class SavedRasterFill(
    var id: Long = 0L,
    var color: Int = 0,
    var anchor: SavedPoint = SavedPoint(),
    var width: Int = 0,
    var height: Int = 0,
    var pngBase64: String = "",
    var objectGroupId: Long = 0L
)

data class SavedAnnotation(
    var id: Long = 0L,
    var text: String = "",
    var color: Int = 0,
    var anchor: SavedPoint = SavedPoint(),
    var width: Int = 0,
    var height: Int = 0,
    var kind: String = AnnotationKind.TEXT.name,
    var style: String = BalloonTextStyle.SOLID.name,
    var objectGroupId: Long = 0L
)

data class SavedFileDrawing(
    var filePath: String = "",
    var strokes: MutableList<SavedStroke> = mutableListOf(),
    var rasterFills: MutableList<SavedRasterFill> = mutableListOf(),
    var annotations: MutableList<SavedAnnotation> = mutableListOf()
)

data class DrawingState(
    var files: MutableList<SavedFileDrawing> = mutableListOf(),
    var recentColors: MutableList<Int> = mutableListOf(),
    var selectedColorRgb: Int = -65536,
    var gridEnabled: Boolean = true,
    var overlayEnabled: Boolean = true,
    var interactionPassThroughEnabled: Boolean = false,
    var drawingVisible: Boolean = true,
    var selectedToolMode: String = DrawingToolMode.DRAW.name,
    var selectedShapeKind: String = ShapeKind.RECTANGLE.name,
    var selectedDrawingShapeKind: String = ShapeKind.RECTANGLE.name,
    var selectedTextStyle: String = "",
    var selectedBalloonTextStyle: String = BalloonTextStyle.SOLID.name,
    var floatingBarX: Int = UNSET_FLOATING_BAR_POSITION,
    var floatingBarY: Int = UNSET_FLOATING_BAR_POSITION
)

@Service(Service.Level.PROJECT)
@State(
    name = "DrawingState",
    storages = [Storage("drawing-drawings.xml")]
)
class DrawingStateService(
    private val project: Project
) : SerializablePersistentStateComponent<DrawingState>(DrawingState()) {

    fun getStrokes(filePath: String): MutableList<SavedStroke> {
        val lookupKey = fileComparisonKey(filePath)
        if (lookupKey.isEmpty()) return mutableListOf()

        val matches = state.files.filter { fileComparisonKey(it.filePath) == lookupKey }
        if (matches.size > 1) {
            DrawingDiagnosticLog.warn(
                category = "STATE",
                message = "getStrokes found duplicate file entries count=${matches.size} key=$lookupKey; loading newest"
            )
            compactFileEntries()
        }

        return matches.lastOrNull()
            ?.strokes
            ?.map { it.deepCopy() }
            ?.toMutableList()
            ?: mutableListOf()
    }

    fun getRasterFills(filePath: String): MutableList<SavedRasterFill> {
        val lookupKey = fileComparisonKey(filePath)
        if (lookupKey.isEmpty()) return mutableListOf()

        val matches = state.files.filter { fileComparisonKey(it.filePath) == lookupKey }
        if (matches.size > 1) {
            DrawingDiagnosticLog.warn(
                category = "STATE",
                message = "getRasterFills found duplicate file entries count=${matches.size} key=$lookupKey; loading newest"
            )
            compactFileEntries()
        }

        return matches.lastOrNull()
            ?.rasterFills
            ?.map { it.deepCopy() }
            ?.toMutableList()
            ?: mutableListOf()
    }

    fun getAnnotations(filePath: String): MutableList<SavedAnnotation> {
        val lookupKey = fileComparisonKey(filePath)
        if (lookupKey.isEmpty()) return mutableListOf()

        val matches = state.files.filter { fileComparisonKey(it.filePath) == lookupKey }
        if (matches.size > 1) {
            DrawingDiagnosticLog.warn(
                category = "STATE",
                message = "getAnnotations found duplicate file entries count=${matches.size} key=$lookupKey; loading newest"
            )
            compactFileEntries()
        }

        return matches.lastOrNull()
            ?.annotations
            ?.map { it.deepCopy() }
            ?.toMutableList()
            ?: mutableListOf()
    }

    fun setStrokes(filePath: String, strokes: List<SavedStroke>) {
        setDrawing(filePath, strokes, getRasterFills(filePath), getAnnotations(filePath))
    }

    fun setDrawing(
        filePath: String,
        strokes: List<SavedStroke>,
        rasterFills: List<SavedRasterFill>,
        annotations: List<SavedAnnotation> = emptyList()
    ) {
        val storagePath = stableStoragePath(filePath)
        val storageKey = fileComparisonKey(storagePath)
        if (storageKey.isEmpty()) return

        updateState { oldState ->
            val updatedFiles = oldState.files
                .filterNot { fileComparisonKey(it.filePath) == storageKey }
                .map { it.deepCopy() }
                .toMutableList()

            if (strokes.isNotEmpty() || rasterFills.isNotEmpty() || annotations.isNotEmpty()) {
                updatedFiles.add(
                    SavedFileDrawing(
                        filePath = storagePath,
                        strokes = strokes.map { it.deepCopy() }.toMutableList(),
                        rasterFills = rasterFills.map { it.deepCopy() }.toMutableList(),
                        annotations = annotations.map { it.deepCopy() }.toMutableList()
                    )
                )
            }

            oldState.copy(files = updatedFiles)
        }
    }

    fun compactFileEntries() {
        val compactedFiles = compactFiles(state.files)
        val currentFiles = state.files
        if (currentFiles.size == compactedFiles.size &&
            currentFiles.zip(compactedFiles).all { (current, compacted) ->
                current.filePath == compacted.filePath &&
                    fileComparisonKey(current.filePath) == fileComparisonKey(compacted.filePath)
            }
        ) {
            return
        }

        DrawingDiagnosticLog.warn(
            category = "STATE",
            message = "compacting drawing file entries from=${currentFiles.size} to=${compactedFiles.size}"
        )
        updateState { it.copy(files = compactedFiles) }
    }

    fun getRecentColors(): MutableList<Int> = state.recentColors.toMutableList()

    fun setRecentColors(colors: List<Int>) {
        updateState { it.copy(recentColors = colors.toMutableList()) }
    }

    fun getSelectedColorRgb(): Int = state.selectedColorRgb

    fun setSelectedColorRgb(rgb: Int) {
        updateState { it.copy(selectedColorRgb = rgb) }
    }

    fun isGridEnabled(): Boolean = state.gridEnabled

    fun setGridEnabled(enabled: Boolean) {
        updateState { it.copy(gridEnabled = enabled) }
    }

    fun isOverlayEnabled(): Boolean = state.overlayEnabled

    fun setOverlayEnabled(enabled: Boolean) {
        updateState { it.copy(overlayEnabled = enabled) }
    }

    fun isInteractionPassThroughEnabled(): Boolean = state.interactionPassThroughEnabled

    fun setInteractionPassThroughEnabled(enabled: Boolean) {
        updateState { it.copy(interactionPassThroughEnabled = enabled) }
    }

    fun isDrawingVisible(): Boolean = state.drawingVisible

    fun setDrawingVisible(visible: Boolean) {
        updateState { it.copy(drawingVisible = visible) }
    }

    fun getSelectedToolMode(): DrawingToolMode {
        return DrawingToolMode.entries.firstOrNull { it.name == state.selectedToolMode }
            ?: DrawingToolMode.DRAW
    }

    fun setSelectedToolMode(toolMode: DrawingToolMode) {
        updateState { it.copy(selectedToolMode = toolMode.name) }
    }

    fun getSelectedShapeKind(): ShapeKind {
        return ShapeKind.entries.firstOrNull { it.name == state.selectedShapeKind }
            ?: ShapeKind.RECTANGLE
    }

    fun setSelectedShapeKind(shapeKind: ShapeKind) {
        updateState { it.copy(selectedShapeKind = shapeKind.name) }
    }

    fun getSelectedDrawingShapeKind(): ShapeKind {
        return ShapeKind.entries.firstOrNull { it.name == state.selectedDrawingShapeKind }
            ?.takeUnless(::isTextOrBalloonShape)
            ?: getSelectedShapeKind().takeUnless(::isTextOrBalloonShape)
            ?: ShapeKind.RECTANGLE
    }

    fun setSelectedDrawingShapeKind(shapeKind: ShapeKind) {
        if (isTextOrBalloonShape(shapeKind)) return
        updateState { it.copy(selectedDrawingShapeKind = shapeKind.name) }
    }

    fun getSelectedTextStyle(): BalloonTextStyle {
        return parseBalloonTextStyle(state.selectedTextStyle)
            ?: parseBalloonTextStyle(state.selectedBalloonTextStyle)
            ?: BalloonTextStyle.SOLID
    }

    fun setSelectedTextStyle(style: BalloonTextStyle) {
        updateState { it.copy(selectedTextStyle = style.name) }
    }

    fun getSelectedBalloonTextStyle(): BalloonTextStyle {
        return parseBalloonTextStyle(state.selectedBalloonTextStyle)
            ?: BalloonTextStyle.SOLID
    }

    fun setSelectedBalloonTextStyle(style: BalloonTextStyle) {
        updateState { it.copy(selectedBalloonTextStyle = style.name) }
    }

    fun getDrawingLocation(): Pair<Int, Int>? {
        val x = state.floatingBarX
        val y = state.floatingBarY
        return if (x == UNSET_FLOATING_BAR_POSITION || y == UNSET_FLOATING_BAR_POSITION) {
            null
        } else {
            x to y
        }
    }

    fun setDrawingLocation(x: Int, y: Int) {
        updateState { it.copy(floatingBarX = x, floatingBarY = y) }
    }

    private fun compactFiles(files: List<SavedFileDrawing>): MutableList<SavedFileDrawing> {
        val compactedByKey = linkedMapOf<String, SavedFileDrawing>()
        for (file in files) {
            val key = fileComparisonKey(file.filePath)
            if (key.isEmpty()) continue
            compactedByKey[key] = file.deepCopy().copy(filePath = stableStoragePath(file.filePath))
        }
        return compactedByKey.values.toMutableList()
    }

    private fun stableStoragePath(filePath: String): String {
        val trimmed = filePath.trim()
        if (trimmed.isEmpty()) return ""

        val expandedPath = expandProjectMacro(trimmed)
        val normalizedPath = normalizePathText(expandedPath)
        val projectBasePath = project.basePath?.let(::normalizePathText) ?: return normalizedPath
        val normalizedPathKey = normalizeCase(normalizedPath)
        val projectBasePathKey = normalizeCase(projectBasePath)

        return when {
            normalizedPathKey == projectBasePathKey -> PROJECT_DIR_MACRO
            normalizedPathKey.startsWith("$projectBasePathKey/") -> {
                val relativePath = normalizedPath.substring(projectBasePath.length).trimStart('/')
                "$PROJECT_DIR_MACRO/$relativePath"
            }
            else -> normalizedPath
        }
    }

    private fun fileComparisonKey(filePath: String): String {
        return normalizeCase(stableStoragePath(filePath))
    }

    private fun expandProjectMacro(filePath: String): String {
        val projectBasePath = project.basePath?.let(::normalizePathText) ?: return filePath
        val normalizedInput = filePath.replace('\\', '/')
        return when {
            normalizedInput.equals(PROJECT_DIR_MACRO, ignoreCase = SystemInfo.isWindows) -> projectBasePath
            normalizedInput.startsWith("$PROJECT_DIR_MACRO/", ignoreCase = SystemInfo.isWindows) ->
                projectBasePath + normalizedInput.substring(PROJECT_DIR_MACRO.length)
            else -> filePath
        }
    }

    private fun normalizePathText(filePath: String): String {
        val withForwardSlashes = filePath.trim().replace('\\', '/')
        val normalized = runCatching {
            Paths.get(withForwardSlashes).normalize().toString().replace('\\', '/')
        }.getOrElse {
            withForwardSlashes
        }
        return normalized.trimEnd('/')
    }

    private fun normalizeCase(value: String): String {
        return if (SystemInfo.isWindows) value.lowercase(Locale.ROOT) else value
    }

    private fun isTextOrBalloonShape(shapeKind: ShapeKind): Boolean {
        return shapeKind == ShapeKind.TEXT || shapeKind == ShapeKind.BALLOON
    }

    private fun parseBalloonTextStyle(value: String): BalloonTextStyle? {
        return BalloonTextStyle.entries.firstOrNull { it.name == value }
    }

    private fun SavedFileDrawing.deepCopy(): SavedFileDrawing = SavedFileDrawing(
        filePath = filePath,
        strokes = strokes.map { it.deepCopy() }.toMutableList(),
        rasterFills = rasterFills.map { it.deepCopy() }.toMutableList(),
        annotations = annotations.map { it.deepCopy() }.toMutableList()
    )

    private fun SavedStroke.deepCopy(): SavedStroke = SavedStroke(
        color = color,
        width = width,
        points = points.map { it.copy() }.toMutableList(),
        filled = filled,
        kind = kind,
        objectGroupId = objectGroupId,
        rigidObjectAnchor = rigidObjectAnchor,
        annotationText = annotationText,
        annotationTextStyle = annotationTextStyle,
        annotationBoundsX = annotationBoundsX,
        annotationBoundsY = annotationBoundsY,
        annotationBoundsWidth = annotationBoundsWidth,
        annotationBoundsHeight = annotationBoundsHeight
    )

    private fun SavedRasterFill.deepCopy(): SavedRasterFill = SavedRasterFill(
        id = id,
        color = color,
        anchor = anchor.copy(),
        width = width,
        height = height,
        pngBase64 = pngBase64,
        objectGroupId = objectGroupId
    )

    private fun SavedAnnotation.deepCopy(): SavedAnnotation = SavedAnnotation(
        id = id,
        text = text,
        color = color,
        anchor = anchor.copy(),
        width = width,
        height = height,
        kind = kind,
        style = style,
        objectGroupId = objectGroupId
    )
}
