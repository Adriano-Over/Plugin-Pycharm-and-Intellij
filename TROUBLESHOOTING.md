# Troubleshooting FloatBar

This guide covers common local development problems for the FloatBar IntelliJ/PyCharm plugin.

## Gradle cannot download dependencies

If Gradle fails while downloading the wrapper, IntelliJ Platform artifacts, or plugins, first check your internet connection and proxy/firewall settings.

Try:

```bash
./gradlew --refresh-dependencies
```

Then run:

```bash
./gradlew runIde
```

Avoid changing the IntelliJ Platform configuration to a hardcoded local IDE path unless you intentionally want to debug against a specific installed IDE.

## `runIde` opens but the plugin does not appear

Check these files first:

```text
src/main/resources/META-INF/plugin.xml
build.gradle.kts
gradle.properties
```

Confirm that `plugin.xml` still registers:

- startup activity
- tool window
- status bar widget
- toggle action

Then rebuild:

```bash
./gradlew clean runIde
```

## Tool window does not appear

Confirm the tool window ID and factory registration in:

```text
src/main/resources/META-INF/plugin.xml
```

Also check the implementation file:

```text
src/main/kotlin/com/floatbar/FloatBarToolWindowFactory.kt
```

## Status bar widget does not appear

Check:

```text
src/main/kotlin/com/floatbar/FloatBarStatusBarWidget.kt
src/main/kotlin/com/floatbar/FloatBarStatusBarWidgetFactory.kt
src/main/resources/META-INF/plugin.xml
```

If the widget was recently changed, restart the sandbox IDE from `runIde` rather than relying on hot reload.

## FloatBar opens but drawing overlay is missing

Check:

```text
src/main/kotlin/com/floatbar/FloatBarService.kt
src/main/kotlin/com/floatbar/EditorOverlayController.kt
src/main/kotlin/com/floatbar/DrawingCanvasPanel.kt
```

Things to verify:

- FloatBar is visible.
- Overlay button says `Overlay ON`.
- An editor document is open.
- The drawing canvas is attached to the active editor.

## Eraser preview appears but erase behavior feels wrong

Do not start by changing the UI preview code.

Check whether these files were modified recently:

```text
src/main/kotlin/com/floatbar/EraseGeometryEngine.kt
src/main/kotlin/com/floatbar/FillGeometryEngine.kt
src/main/kotlin/com/floatbar/PaintGeometryEngine.kt
```

The fill/erase behavior is sensitive. Prefer reverting suspicious geometry changes before redesigning the erase model.

## Fill performance or erase-after-fill behavior regresses

Be very careful with fill internals.

Known safe baseline:

- dense stroke-based fill model
- no `FillAreaBuilder.kt`
- max fill segment width around `9 px`
- erase feel preserved over fill optimization

Avoid replacing fill with large merged areas, row strips, chunks, or tiled retessellation unless erase behavior is redesigned and tested together.

## Recent colors do not update

Check:

```text
src/main/kotlin/com/floatbar/RecentColorStore.kt
src/main/kotlin/com/floatbar/FloatingBar.kt
```

Verify that the store capacity and toolbar rendering count match. The current intended count is **6 recent colors**.

## Undo / redo buttons look wrong

Check:

```text
src/main/kotlin/com/floatbar/FloatingBar.kt
src/main/kotlin/com/floatbar/DrawingHistoryStore.kt
```

Verify that the toolbar refreshes button state after drawing, clearing, undo, and redo.

## Plugin works locally but GitHub contains IDE/cache files

Make sure `.gitignore` exists at the project root.

If `.gradle` or `.idea` were already committed, remove them from Git tracking without deleting local copies:

```bash
git rm -r --cached .gradle .idea
git add .gitignore
git commit -m "Stop tracking local IDE and Gradle cache files"
```

## Safe manual test after any change

After any plugin change, run:

```bash
./gradlew runIde
```

Then test:

1. FloatBar opens and closes.
2. Overlay toggles ON/OFF correctly.
3. Draw works.
4. Erase works and preview follows the cursor.
5. Fill works.
6. Shape preview and commit work.
7. Undo and redo update correctly.
8. Clear asks for confirmation.
9. Grid toggles ON/OFF correctly.
10. Recent colors update and highlight the selected color.
11. Status bar widget toggles FloatBar correctly.

## General rule

When something breaks, identify the smallest recent change first. Revert or isolate that change before editing lower-level drawing, fill, erase, or geometry code.
