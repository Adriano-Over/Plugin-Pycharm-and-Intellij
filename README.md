# FloatBar

FloatBar is a personal JetBrains IDE plugin for adding a floating drawing and annotation toolbar inside IntelliJ-based IDEs such as IntelliJ IDEA and PyCharm.

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
- Status bar toggle
- Tool window integration
- Per-document drawing persistence

## Project structure

```text
src/main/kotlin/com/floatbar/
src/main/resources/META-INF/plugin.xml
build.gradle.kts
gradle.properties
settings.gradle.kts
```

Important areas:

```text
FloatingBar.kt                 Floating toolbar UI
FloatBarService.kt             Main plugin/window lifecycle service
EditorOverlayController.kt     Editor overlay visibility/control
DrawingCanvasPanel.kt          Drawing overlay panel
DrawingInputController.kt      Mouse/tool input handling
DrawingCanvasPainter.kt        Canvas rendering
FillGeometryEngine.kt          Fill behavior
EraseGeometryEngine.kt         Erase behavior
PaintGeometryEngine.kt         Paint geometry behavior
FloatBarStatusBarWidget.kt     Bottom status bar widget
RecentColorStore.kt            Recent color memory
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

- FloatBar opens and closes from the status bar widget
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
