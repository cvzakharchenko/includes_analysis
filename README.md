# Project Include Hierarchy

CLion plugin that adds a project-aware include hierarchy view for C/C++/Objective-C files.

## Why

CLion's built-in **Imports Hierarchy** has no way to stop "Expand All" before it walks into the C++ standard library, the toolchain SDK, and `C:\Program Files\...`. There is no scope dropdown in the Hierarchy tool window for includes. This plugin provides an alternative view that reuses the IDE's existing scope system.

## What it does

Open any C/C++/Objective-C file and pick **Navigate → Project Include Hierarchy** (default shortcut: <kbd>Ctrl</kbd>+<kbd>Alt</kbd>+<kbd>Shift</kbd>+<kbd>I</kbd>). The Hierarchy tool window opens with three extra controls on the toolbar:

- **Scope dropdown** — any IntelliJ `SearchScope` works: built-in (Project Files, Project Source Files, Production, Tests), or custom scopes you defined in <kbd>Settings</kbd> → <kbd>Appearance & Behavior</kbd> → <kbd>Scopes</kbd>. Defaults to **Project Files**, so "Expand All" stops at the project boundary out of the box.
- **Flat List toggle** — collapses the tree into a deduplicated alphabetical list of every unique header reachable through the current scope.
- **Filter field** — type to keep only nodes whose file name matches; parents of matches stay visible so context isn't lost.

Everything else on the Hierarchy toolbar (Refresh, Sort Alphabetically, Export to Text File, Navigate with Single Click, Pin Tab) keeps working — they operate on the filtered tree, so for example exporting the flat view with scope = Project Files gives you a portable text dump of your project's include closure.

## Build

```
./gradlew buildPlugin
```

The resulting ZIP is in `build/distributions/`. Install it via <kbd>Settings</kbd> → <kbd>Plugins</kbd> → <kbd>⚙</kbd> → <kbd>Install plugin from disk...</kbd>.

To try it in a sandbox CLion:

```
./gradlew runIde
```

## Compatibility

Requires CLion 2024.3 or later. Depends on the bundled CIDR PSI (`com.intellij.modules.clion`).
