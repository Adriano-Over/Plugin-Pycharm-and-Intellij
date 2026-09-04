# Changelog

All notable changes to Drawing will be documented in this file.

This project follows a simple changelog format focused on practical development history rather than formal release management.

## Unreleased

## 6.0.3 - 2026-09-04

### Build and maintenance

- Added CI for tests, plugin packaging, and IntelliJ Plugin Verifier checks.
- Updated the IntelliJ Platform Gradle Plugin to 2.18.1.
- Added explicit IntelliJ IDEA and PyCharm verifier targets.
- Removed the upper IDE build restriction so verified future IDE releases are not blocked automatically.
- Aligned the Gradle artifact version with the 6.0.3 project history.
- Removed generated IntelliJ Platform cache files from version control.
- Updated project documentation to match the current tool-window, raster-fill, and semantic-text architecture.

### Added

- Added editor tool cursor feedback for Draw, Erase, Fill, and Shape modes.
- Added an eraser preview circle that follows the mouse while Erase mode is active.
- Added a compact active tool label in the floating toolbar.
- Added clearer toolbar tooltips for the main actions and color controls.
- Added visible Overlay ON/OFF state in the floating toolbar.
- Added clearer Drawing ON/OFF state in the IDE status bar widget.
- Added a confirmation dialog before clearing drawings from the current editor document.
- Added clearer Undo and Redo availability states.
- Added visible Grid ON/OFF state in the floating toolbar.
- Added a selected color status label.
- Added selected color preview styling to the color status label.
- Added selected hex color text directly on the Color button.
- Added selected recent-color highlighting.
- Increased recent colors from 5 to 6.
- Added project `.gitignore`.
- Added project `README.md`.

### Changed

- Polished plugin metadata in `plugin.xml`.
- Standardized the visible plugin name as `Drawing`.
- Improved plugin description and basic change notes.
- Updated tool window naming to `Drawing`.
- Updated toggle action description.

### Preserved

- Drawing behavior was kept stable during UI polish work.
- Fill, erase, and paint geometry engines were intentionally left untouched.
- The restored Gradle configuration was preserved.

## Development Notes

### Stable behavior to protect

The fill and erase behavior is currently considered stable. Avoid redesigning these areas unless a specific bug requires it:

- `FillGeometryEngine.kt`
- `EraseGeometryEngine.kt`
- `PaintGeometryEngine.kt`

### Local testing checklist

After each change, test at least:

1. Start the plugin with `./gradlew runIde`.
2. Toggle Drawing ON/OFF.
3. Toggle the editor overlay ON/OFF.
4. Draw a stroke.
5. Erase part of a stroke.
6. Use fill.
7. Use undo and redo.
8. Toggle the grid.
9. Switch tools and confirm toolbar state updates.
10. Confirm existing drawings persist as expected.
