# Drawing Development Guide

This guide describes the safe development workflow for the Drawing IntelliJ/PyCharm plugin.

Drawing is currently in a stable, personal-use phase. The main goal is to keep improving the plugin with small, reversible changes while protecting the working drawing, fill, erase, and persistence behavior.

## Development Principles

1. **Prefer small changes**
   - Change one feature or polish area at a time.
   - Avoid large rewrites unless there is a clear bug that cannot be fixed safely.

2. **Protect working geometry behavior**
   - Be careful with these files:
     - `FillGeometryEngine.kt`
     - `EraseGeometryEngine.kt`
     - `PaintGeometryEngine.kt`
     - `GeometryAreaUtils.kt`
   - Do not redesign fill or erase behavior casually.
   - If these files must change, test draw, fill, erase, undo, redo, and clear carefully.

3. **Treat working changes as baselines**
   - After a change is tested and confirmed working, commit it before starting the next one.
   - This makes it easier to roll back if a later step breaks behavior.

4. **Keep UI polish separate from engine changes**
   - Toolbar, status bar, tooltip, cursor, and metadata changes should not modify drawing geometry.
   - Prefer isolated UI changes when improving the plugin feel.

## Local Run

Run the plugin in a sandbox IDE:

```bash
./gradlew runIde
```

## Build

Build the plugin package:

```bash
./gradlew build
```

## Recommended Manual Test Checklist

After each change, test the plugin manually in the IDE sandbox.

### Startup and visibility

- Drawing loads without startup errors.
- Floating bar can be shown and hidden.
- Overlay button correctly shows `Overlay ON` and `Overlay OFF`.

### Drawing tools

- Draw mode creates strokes normally.
- Erase mode shows the eraser preview circle.
- Erase mode removes only the expected area.
- Fill mode fills as expected.
- Shape tools preview and commit correctly.

### UI state

- Tool label updates correctly.
- Color label updates correctly.
- Color button shows the selected hex value.
- Recent colors show 6 slots.
- Selected recent color is highlighted.
- Grid button shows `Grid ON` and `Grid OFF` correctly.
- Undo and Redo buttons enable/disable correctly.

### History and destructive actions

- Undo works after drawing.
- Redo works after undo.
- Clear asks for confirmation.
- Clear removes drawings only after confirmation.

### Editor/document behavior

- Drawings stay tied to the correct editor document.
- Switching files does not mix drawings between documents.
- Closing and reopening editors does not create duplicate overlays.

## Suggested Commit Flow

Use small commits with clear messages, for example:

```bash
git add src/main/kotlin/com/drawing/DrawingToolbarPanel.kt
git commit -m "Polish toolbar color status"
```

For documentation-only changes:

```bash
git add README.md CHANGELOG.md DEVELOPMENT.md
git commit -m "Add project documentation"
```

## Files That Are Usually Safe for UI Polish

These files are usually safer places for toolbar/status/cursor polish:

- `DrawingToolbarPanel.kt`
- `DrawingToolWindowPanel.kt`
- `DrawingCanvasPanel.kt`
- `DrawingCanvasPainter.kt`
- `DrawingInputController.kt`
- `EditorOverlayController.kt`

Still test carefully after every change.

## Files That Need Extra Care

These files affect core behavior and should be changed only when necessary:

- `FillGeometryEngine.kt`
- `EraseGeometryEngine.kt`
- `PaintGeometryEngine.kt`
- `DrawingStrokeStore.kt`
- `DrawingHistoryStore.kt`
- `DrawingDocumentSync.kt`
- `DrawingViewportTools.kt`

## Current Direction

The current safest direction is:

1. Keep core draw/fill/erase behavior stable.
2. Continue small UX polish steps.
3. Improve project documentation and repository hygiene.
4. Add tests or lightweight verification helpers only after the current behavior is well understood.
