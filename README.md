# Logic Sugar

<h1 align="center">
  <a href="https://github.com/DeterMination-Wind/LogicSugar/releases/latest"><img src="https://img.shields.io/github/v/release/DeterMination-Wind/LogicSugar?display_name=release&label=Latest%20Release&color=green"></a>
  <a href="https://github.com/DeterMination-Wind/LogicSugar/releases"><img src="https://img.shields.io/github/downloads/DeterMination-Wind/LogicSugar/total?label=Downloads&color=blue"></a>
  <a href="LICENSE"><img src="https://img.shields.io/github/license/DeterMination-Wind/LogicSugar?label=License"></a>
  <a href="https://github.com/DeterMination-Wind/LogicSugar"><img src="https://img.shields.io/github/stars/DeterMination-Wind/LogicSugar?style=flat&label=Star%20this%20mod!&color=yellow"></a>
</h1>

[中文](README_zh.md) | [English](README.md)

> Write logic around ideas and structure instead of a wall of jumps.

Logic Sugar improves the Mindustry logic editing experience for people who want programs that are easier to read, change, and share. It lets you express common control flow and calculations in a more structured way, while saving the result as vanilla-compatible mlog.

That compatibility is the important part: a program written with Logic Sugar can continue to work in ordinary Mindustry clients. The mod is useful both for learning logic and for maintaining larger processors where raw jump instructions become difficult to follow.

## Features

- **Structured control flow** — `if` / `elif` / `else`, `for`, `while`, `switch` / `case`, plus `break` / `continue`: written as blocks in the logic editor and compiled down to plain vanilla jump instructions.
- **Switch dispatch optimization** — in `auto` mode, integer-valued switches choose between the comparison chain and an `@counter` jump table using executable-instruction cost; repeated case values can share the first matching table slot. Set `chainOnly` to reproduce the legacy comparison-chain output. Jump tables use numeric guard semantics and fall back automatically for non-integer values or spans above 255.
- **Functions** — define functions with parameters, call them, and return values; two compile modes: **normal** (each function becomes one shared `@counter` subroutine) and **inline** (the body is copied to every call site).
- **Function library** — global functions shared by every logic processor, edited right inside the processor editor, validated and saved automatically, with self-repair if the library file gets corrupted.
- **Expression compiler** — infix expressions are automatically expanded into vanilla `op` instruction chains; since v2.3.0 the conditions of `if` / `elif` / `for` / `while` also accept full expressions.
- **Vanilla-compatible output** — the structured source travels inside the saved mlog as carrier statements (`set __ls_sugar "..."`), so programs run unchanged on ordinary clients and can be reopened later as editable sugar blocks.
- **Vanilla mlog recovery** — import ordinary mlog and recover verified `if` / `elif` / `else`, `for`, `while`, `switch` / `case`, and normal-mode function structures. Recovery is accepted only after recompilation matches the original instruction stream; uncertain code stays vanilla mlog.
- **Original/Sugar views** — switch between the original mlog and a verified Sugar view without losing edits: unsaved changes and failed saves are detected before a view change.
- **Editor helpers** — jump line coloring, hiding of internal compiler variables (`__ls_*`, expression temporaries) in the variable browser, Ctrl+Click / Ctrl+Drag statement copying, hover hints for every block, and search-box match highlighting.

## Install

Requires **Mindustry v155 or later** on desktop or Android. Download the universal JAR from Releases — one single file works on both platforms — and drop it into Mindustry's mods directory. The mod loads automatically and can be toggled from the in-game mods list like any other mod. Then open the logic editor to use the enhanced workflow.

## Build

Prerequisites:

- **Java 17+**
- A built copy of the game sources next to this repository: compilation depends on `../Mindustry-master/desktop/build/libs/Mindustry.jar`.
- For packaging, a local Android SDK with **D8** and at least one platform's `android.jar` (located via the `ANDROID_SDK_ROOT`/`ANDROID_HOME` or `D8_PATH` environment variable).

~~~powershell
.\gradlew.bat deploy
~~~

The deploy task merges the desktop classes and Android `classes.dex` into a single cross-platform JAR at `build/libs/LogicSugar-v<version>.jar`; the plain `build` task runs deploy as well.

## License

Licensed under the [GNU GPL v3](LICENSE).
