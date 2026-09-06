# Logic Sugar

<h1 align="center">
  <a href="https://github.com/DeterMination-Wind/LogicSugar/releases/latest"><img src="https://img.shields.io/github/v/release/DeterMination-Wind/LogicSugar?display_name=release&label=Latest%20Release&color=green"></a>
  <a href="https://github.com/DeterMination-Wind/LogicSugar/releases"><img src="https://img.shields.io/github/downloads/DeterMination-Wind/LogicSugar/total?label=Downloads&color=blue"></a>
  <a href="LICENSE"><img src="https://img.shields.io/github/license/DeterMination-Wind/LogicSugar?label=License"></a>
  <a href="https://github.com/DeterMination-Wind/LogicSugar"><img src="https://img.shields.io/github/stars/DeterMination-Wind/LogicSugar?style=flat&label=Star%20this%20mod!&color=yellow"></a>
</h1>

[中文](README_zh.md) | [English](README.md)

> Write logic around ideas and structure instead of a wall of jumps.

Logic Sugar improves the Mindustry logic editing experience for people who want programs that are easier to read, change, and share. It lets you express common control flow and calculations as clear, structured blocks in the editor, while saving the result as vanilla-compatible mlog — so your program runs on any ordinary client and can be reopened for editing later.

## Features

- **Structured control flow** — `if` / `elif` / `else`, `for`, `while`, `switch` / `case` and `break` / `continue` written as blocks, compiled into plain vanilla mlog on save.
- **Expressions as conditions** — the condition of `if` / `elif` / `while` / `for` (Expr mode) accepts a full expression like `hp < 25 && !shielded`.
- **One-line expression statements** — write `result = (a + b) * 2`; it expands to equivalent instructions on save, folds back on reopen, and invalid expressions are marked red on the spot.
- **Expressions anywhere a value goes** — assignments, function arguments, `return` values, and member access like `@unit.@health`.
- **Functions** — define functions with parameters, call them and return values; normal (subroutine) and inline modes switchable in settings.
- **Global function library** — shared by every processor, edited inside the processor editor, validated and saved automatically on close, and self-repairing if the file gets corrupted.
- **Structure recovery** — open a plain mlog program and recover its `if` / `for` / `while` / `switch` / function structures automatically; only fully verified parts are recovered, everything else stays untouched.
- **Original / Sugar views** — switch between the generated vanilla mlog and the editable Sugar view any time, with unsaved changes protected before switching.
- **Editor helpers** — colored jump lines, `__ls_*` internals hidden from the variable list, Ctrl+Click / Ctrl+Drag statement copying, hover hints, and search highlighting.
- **Assertions** — seven runtime-check cards: out-of-range array indexes, values that drift from expectations, and print-output comparisons stop the program on the offending line with a message above the processor; a breakpoint freezes the whole game for inspection; a log statement writes to the game log. Assertions live only in the editor by default and never enter saved code; the single-player "Debug Assert Build" toggle makes them run for real, and multiplayer saves always stay vanilla-compatible.
- **Processor status on the map** — stopped processors show which line they stopped on, long waits draw a progress ring, and failures show their message in place; threshold, scan rate and warning effects are adjustable in settings.
- **Copy variables / print buffer** — dump all variables of the processor being edited as a name-sorted, full-precision table ready for spreadsheets, or copy the program's current print output.

## Install

Requires **Mindustry v155 or later** (desktop or Android). Download the universal JAR from [Releases](https://github.com/DeterMination-Wind/LogicSugar/releases) — a single file for both platforms — drop it into Mindustry's mods directory, enable it in the in-game mods list, then open the logic processor editor.

## Build

Prerequisites:

- **Java 17+**
- A built copy of the game sources next to this repository (compilation depends on `../Mindustry-master/desktop/build/libs/Mindustry.jar`)
- For packaging the Android side, a local Android SDK with **D8** and at least one platform's `android.jar` (located via the `ANDROID_SDK_ROOT`, `ANDROID_HOME` or `D8_PATH` environment variable)

~~~powershell
.\gradlew.bat deploy
~~~

Produces `build/libs/LogicSugar-v<version>.jar`, a cross-platform JAR for desktop and Android; the plain `build` task runs deploy as well.

## Docs

Classified project documentation (architecture, development, release, testing, glossary) lives in [docs/README.md](docs/README.md).

## License

Licensed under the [GNU GPL v3](LICENSE).