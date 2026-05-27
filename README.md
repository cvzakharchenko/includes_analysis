# Project Include Hierarchy

CLion plugin that adds a project-aware include hierarchy view for C/C++/Objective-C files.

## Why

CLion's built-in include hierarchy can expand into the standard library, toolchain SDKs,
and system folders such as `C:\Program Files`. This plugin provides a separate hierarchy
view that reuses IDE scopes, so expansion can stay inside project files or any custom
scope configured in Settings.

## What It Does

Open any C/C++/Objective-C file and choose **Navigate | Project Include Hierarchy**
or use the default shortcut `Ctrl+Alt+Shift+I`.

- **Scope dropdown**: reuse built-in or custom IntelliJ scopes.
  Use **All Files (Including Excluded)** to bypass IDE scope checks entirely.
- **Show Includers toggle**: switch between files included by the current file and files
  that include the current file.
- **Flat List toggle**: show a deduplicated alphabetical list for either direction.
- **Filter field**: filter by file name by default, or by file path when enabled.
- **Options menu**: show direct out-of-scope leaves, hide repeated includes, show paths,
  show unique descendant counts, and autoload the hierarchy in the background.

The result is rendered in the standard Hierarchy tool window, so standard hierarchy
actions such as refresh, export, and navigation continue to work.

## Build

```powershell
.\gradlew.bat buildPlugin
```

The resulting ZIP is in `build/distributions/`.

To try it in a sandbox CLion:

```powershell
.\gradlew.bat runIde
```

## Compatibility

Requires CLion with the bundled Nova/Radler C++ include graph support.