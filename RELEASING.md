# Drawing Release and Recovery Guide

Use this procedure for patch releases and emergency rollback. Release tags are immutable: fix or revert a bad release in a newer version instead of moving an existing tag.

## Release checklist

1. Start from a clean `main` branch and synchronize it with `origin/main`.
2. Update `pluginVersion` in `gradle.properties` and move the completed changes from `Unreleased` into a dated section in `CHANGELOG.md`.
3. Run the complete validation command:

   ```powershell
   .\gradlew.bat clean test buildPlugin verifyPlugin
   ```

4. Manually smoke-test the generated ZIP in both supported IDE families. At minimum, verify drawing, fill, erase, text, undo/redo, file switching, and restart persistence.
5. Commit the release, push `main`, and confirm that the Build workflow succeeds and uploads `build/distributions/drawing-plugin-<version>.zip`.
6. Create and push an annotated tag only after the release commit and CI result are confirmed:

   ```powershell
   git tag -a v<version> -m "Drawing <version>"
   git push origin v<version>
   ```

## Recovery checks

Project-local drawing paths are saved with `$PROJECT_DIR$`, so moving the whole project should preserve them. If saved state is suspected to be damaged:

1. Close the IDE and copy `.idea/drawing-drawings.xml` to a safe location before changing it.
2. Reopen the project and check whether valid drawings load. Invalid strokes, annotations, and raster payloads are ignored independently so valid entries can still be recovered.
3. If the IDE cannot parse the XML itself, restore the backup or remove only the malformed file entry from a copy, then retry.
4. Keep the original backup until every affected file has been inspected.

Removing `drawing-drawings.xml` resets every persisted drawing and toolbar preference for that project. Treat that as a last resort.

## Rollback

For a bad commit that has already been pushed, create a revert instead of rewriting shared history:

```powershell
git revert <bad-commit>
.\gradlew.bat clean test buildPlugin verifyPlugin
git push origin main
```

If a tagged version was distributed, increment the patch version, document the revert or corrective fix, validate again, and publish a new tag. Do not delete or repoint the old release tag.

For an unpublished local commit, keep any useful work on a branch before changing direction. Avoid destructive resets when uncommitted work or unique history exists.
