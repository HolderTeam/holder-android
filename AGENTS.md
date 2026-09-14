# Coding-agent instructions

# Icons

Don't add the `androidx.compose.material:material-icons-extended` dependency to get a single missing Material icon. It pulls in the whole icon set unconditionally — `material-icons-extended`'s `classes.jar` is ~36MB / 11,105 compiled classes, vs `material-icons-core`'s ~850KB / 293 — and this project has no `minifyEnabled`/R8 shrinking configured for release builds, so none of that gets tree-shaken back out; nearly all of it ships. This has already slipped in and been removed twice (commits `b4ab295`, `a05e6c9`) — check `app/build.gradle.kts` for `material.icons.extended` before assuming it's still absent.

Instead, copy the needed glyph's exact path data into a new `res/drawable/ic_<name>.xml` vector drawable (24dp viewport, single `<path android:fillColor="#FF000000" android:pathData="..."/>`), and reference it via `Icon(painterResource(R.drawable.ic_<name>), contentDescription = "...")` — `Icon()` tints it automatically via `LocalContentColor`, so the hardcoded fill color doesn't matter. See `ic_undo.xml`, `ic_redo.xml`, `ic_fullscreen.xml`, `ic_account_tree.xml`, `ic_folder.xml`, `ic_link.xml` for existing examples.

To get exact path data without hand-tracing an SVG: write a throwaway JVM unit test under `app/src/test` that imports the icon from wherever it currently resolves, walks `ImageVector.root` recursively collecting each `VectorPath.pathData: List<PathNode>`, and renders each `PathNode` variant to its SVG command-letter equivalent (M/L/H/V/C/S/Q/T/A/Z, lowercase for the Relative* variants) — vector drawable `pathData` uses identical syntax, so the output pastes straight in. Run via `./gradlew :app:testDebugUnitTest --tests "..."`, write the result to a file (Gradle doesn't surface test stdout directly), then delete the test.

# Submodules

Do not modify holder-core through the submodule checkout inside holder-android (`submodules/holder-core`).
Make holder-core changes only in the canonical workspace repository at ../holder-core,
commit and test them there, then update holder-android's submodule pointer to that commit.

If a task requires changes to both holder-core and holder-android:

In the standalone holder-core repository, make a new branch off main and change, test, and commit holder-core.
Return to holder-android.
Advance its holder-core submodule to the committed revision.
Test holder-android against that revision.

Never treat holder-android's submodule checkout as the working copy of holder-core. If you ever find the submodule checked out at a commit that doesn't match what the current branch actually records (e.g. after switching branches without updating submodules), run `git submodule update` to reset it — don't leave it pointing at a stray commit, and don't commit that drift into holder-android's tree.
