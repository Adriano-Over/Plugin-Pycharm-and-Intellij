# Drawing

Drawing is a personal JetBrains IDE plugin for adding a floating drawing and annotation toolbar inside IntelliJ-based IDEs such as IntelliJ IDEA and PyCharm.

It is designed for quick visual notes, sketches, explanations, and temporary annotations directly over the editor.

## Features

- Floating toolbar inside the IDE
- Editor drawing overlay
- Freehand drawing
- Eraser tool with cursor preview
- Fill tool
- Shape tools
- Grid toggle
- Recent colors
- Current color preview
- Undo and redo
- Clear drawings with confirmation
- Tool window integration
- Per-document drawing persistence

## Project structure

```text
src/main/kotlin/com/drawing/
src/main/resources/META-INF/plugin.xml
build.gradle.kts
gradle.properties
settings.gradle.kts
```

Important areas:

```text
DrawingToolWindowPanel.kt       Tool window and floating toolbar lifecycle
DrawingToolbarPanel.kt          Drawing toolbar UI
EditorOverlayController.kt      Editor overlay visibility/control
DrawingCanvasPanel.kt           Drawing overlay panel
DrawingCanvasController.kt      Drawing operations and history coordination
DrawingInputController.kt       Mouse/tool input handling
DrawingCanvasPainter.kt         Canvas rendering
DrawingStateService.kt          Project-level persistent state
DrawingStrokeStore.kt           Per-document drawing storage
FillGeometryEngine.kt           Fill-region discovery
RasterFillEraseEngine.kt        Raster-fill erasing
AnnotationRenderer.kt           Semantic text rendering
RecentColorStore.kt             Recent color memory
```

## Requirements

- JDK 21
- IntelliJ IDEA / PyCharm compatible with platform build 253
- Gradle wrapper included in the project

## Run locally

From the project root:

```bash
./gradlew runIde
```

On Windows PowerShell:

```powershell
.\gradlew.bat runIde
```

## Build

```bash
./gradlew buildPlugin
```

The built plugin ZIP will be generated under:

```text
build/distributions/
```

## Verify compatibility

Run tests, build the distributable ZIP, and verify binary compatibility with the supported IntelliJ IDEA and PyCharm platform versions:

```bash
./gradlew test buildPlugin verifyPlugin
```

## Development notes

This project favors small, safe, reversible changes.

Avoid large rewrites unless necessary. In particular, be careful with:

```text
FillGeometryEngine.kt
EraseGeometryEngine.kt
PaintGeometryEngine.kt
```

Fill and erase behavior should be tested together because changes to fill geometry can easily affect erase behavior.

## Manual test checklist

After a change, run the plugin and check:

- Drawing opens and closes from the tool window
- Overlay button switches between ON and OFF correctly
- Draw mode works
- Erase mode works and shows the eraser preview
- Fill mode works
- Shapes can be previewed and committed
- Grid button toggles correctly
- Color picker updates the selected color
- Recent colors update and highlight the selected color
- Undo and redo update correctly
- Clear asks for confirmation before deleting drawings
- Drawings stay attached to the correct editor document

## Repository

https://github.com/Adriano-Over/Plugin-Pycharm-and-Intellij
