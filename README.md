# Logic Sugar

<h1 align="center">
  <a href="https://github.com/DeterMination-Wind/LogicSugar/releases/latest"><img src="https://img.shields.io/github/v/release/DeterMination-Wind/LogicSugar?display_name=release&label=Latest%20Release&color=green"></a>
  <a href="https://github.com/DeterMination-Wind/LogicSugar/releases"><img src="https://img.shields.io/github/downloads/DeterMination-Wind/LogicSugar/total?label=Downloads&color=blue"></a>
  <a href="LICENSE"><img src="https://img.shields.io/github/license/DeterMination-Wind/LogicSugar?label=License"></a>
  <a href="https://github.com/DeterMination-Wind/LogicSugar"><img src="https://img.shields.io/github/stars/DeterMination-Wind/LogicSugar?style=flat&label=Star%20this%20mod!&color=yellow"></a>
</h1>

[中文](README_zh.md) | [English](README.md)

> Turn Mlog into a high-level language.

Logic Sugar is tailored for players who are familiar with high-level languages such as Python and C++.

By wrapping basic mlog operations, Logic Sugar implements many features such as `for` and `Func`. Based on linked memory metadata, it can also create high-level data structures such as `vector` and `map`, and provides C++ STL-like built-in functions (`sort`, etc.) for structures such as `vector`.

Logic Sugar supports multiplayer, which means you can also efficiently understand the code of other players who use Logic Sugar.

Everything is aimed at making mlog editing more efficient.

## Features

### Structured Control Flow

Write common control flow as blocks; on save, everything compiles to plain vanilla mlog.

| Construct | Syntax | Description |
| --- | --- | --- |
| Branching | `if`, `elif`, `else` | Write conditionals as blocks. |
| Loops | `for`, `while` | Write loops as blocks. |
| Loop control | `break`, `continue` | Break out of or continue a loop. |
| Multi-branch | `switch`, `case` | Match a value against multiple branches. |

### Expressions and Functions

| Feature | Description |
| --- | --- |
| **Expressions in conditions** | Conditions of `if`, `elif`, `while`, and `for` (Expr mode) can directly use full expressions such as `hp < 25 && !shielded`. |
| **Expr card** | Write `result = (a + b) * 2`: you can either drag an Expr card or paste the line directly into the code text; it imports as an Expr card, expands to equivalent instructions on save, folds back automatically on reopen, and invalid expressions are marked red immediately. |
| **Expressions anywhere** | Expressions can be written anywhere a value is expected, such as assignments, function arguments, and `return` values, including member access like `@unit.@health`. |
| **Functions** | Define functions with parameters, call them, and return values; normal (subroutine) and inline modes can be switched in settings. |
| **Function library** | Global functions shared by all processors, reducing repeated typing. Edit directly in settings; up to 10,000 statements. |

### Data Structures

> [!note]
> This is vanilla-compatible, but the operation complexity of some data structures is not the same as C++ `STL`; see the [tutorial index](docs/tutorials/en/README.md) for details.
>
> Logic Sugar Code created by the Logic Sugar mod and containing special blocks is referred to below as “sugar code”. Compiled mlog, or mlog created by the vanilla editor, is referred to as “Mlog”.

| Declaration | Kind |
| --- | --- |
| `array` | Standard array |
| `matrix` | 2-D array |
| `record` | Record |
| `stack` | Stack |
| `queue` | Queue |
| `deque` | Double-ended queue |
| `bitset` | Bitset |
| `map` | Hash table |
| `uset` | Set |
| `list` | List |
| `heap` | Heap |
| `chain` | Linked list |

Subscript reads and writes such as `buf[i]` and `buf[i] = 5` are supported in Expr.

Each structure corresponds to **one operation card** in the *Add Block* interface, whose in-card button switches between all of that structure's operations — the stack card, for instance, offers Push, Pop, Peek, Size and Clear. Arguments get **one input box per parameter**, with the parameter name shown on hover, and a card whose arguments cannot compile turns red on the spot. Operation cards live in the same palette column as that structure's declaration card (the array/matrix operation card sits under "Array Operations").

Each data structure has an advanced tutorial chapter: declaration card, function quick-reference table, line-by-line explanation of the lowered mlog, complexity, and usage notes. Start from the [tutorial index](docs/tutorials/en/README.md).

#### Getter Sugar

| Structure | Supported spellings |
| --- | --- |
| `list` | `l[i]`, `l.get(i)`, `l.size()`, `l.find(v)` |
| `stack` | `s.top()`, `s.peek()`, `s.size()` |
| `queue` | `q.front()`, `q.peek()`, `q.size()` |
| `deque` | `d.front()`, `d.back()`, `d.size()` |
| `bitset` | `b[i]`, `b.test(i)`, `b.count()` |
| `map` | `m[k]`, `m.get(k)`, `m.has(k)`, `m.size()` |
| `uset` | `s.has(v)`, `s.size()` |
| `chain` | `c[i]`, `c.get(i)`, `c.head()`, `c.next(i)`, `c.len()` |

They are fully equivalent to the corresponding individual operation blocks (such as `vector_at(l, i)` and `stack_top(s)`).

### Editor, Debugging, and Views

| Feature | Description |
| --- | --- |
| **Rebuild from source** | When opening a saved processor, it tries to rebuild Mlog into sugar code (conservative; it only attempts to recover control flow, not data structures). |
| **Editor helpers** | Colored jump lines, Ctrl+Click and Ctrl+Drag block copying, hover hints (a hint wider than the screen wraps instead of overflowing it), search highlighting, undo and redo (`Ctrl+Z` and `Ctrl+Y` on desktop, bottom buttons on mobile), and a live display of the compiled instruction count against the limit. |
| **Cross-processor copy and paste** | *Copy Selection* / *Paste Selection* in the edit menu, or `Ctrl+C` / `Ctrl+V` on desktop. The clipboard holds **sugar code itself**, so a selection pasted into another processor comes back as editable blocks; a selection containing a jump that leaves it is rejected, because a numeric target means nothing in another program. |
| **Assertions** | Provides some statements that can be used for debugging and displaying error messages above the processor; see the [upstream README](https://github.com/cardillan/MlogAssertions/blob/main/README.md) for details. |
| **Processor status indicator** | Stopped processors show above them which line they stopped on; long-waiting processors draw a progress ring; runtime errors show their message in place, with expected and actual values shown together when available. |
| **Unit flag display** | Optional in settings: show each unit's logic flag above it, with different vivid colors for different flags; flag value 0 is hidden by default. |
| **Copy variables and print buffer** | In the **edit menu**: copy all variables of the current processor to the clipboard as a table organized by name and preserving full precision (ready to paste into a spreadsheet), or copy the mlog output buffer. These two buttons used to occupy fixed-width slots in the bottom bar and have moved to the edit menu, so the bar no longer overflows a narrow window. |
| **Logic editor conflict** | A setting. Other mods (for example 逻辑工具) also replace `Vars.ui.logic` to take the logic editor over, and only one mod can own it. Choose between **ask each launch** (the default: one prompt at startup; answering applies that choice for the session, and the setting stays on ask so the next launch asks again), **take over** (keep LogicSugar's editor and replace the other mod's UI), **step aside** (keep the other mod's editor, which also disables LogicSugar's editor and sugar language) and **coexist** (keep the other mod's whole editor UI and run LogicSugar's canvas inside it, so both work at once). Dismissing the prompt without answering steps aside - it never takes over by accident. Switching takes effect immediately. |

> [!note]
> So that the multi-KB sugar carrier never executes, compiled output appends one `set @counter 0` at the end of main. It is a real instruction counted alongside the carrier, so a processor's **effective instruction limit is one below the limit (999)**; an existing program already at the limit reports the overflow when it is saved again.

## Install

See the **Latest Release** badge at the top for the current version. It requires **Mindustry v160.1 or later** (desktop or Android). Download the universal JAR from [Releases](https://github.com/DeterMination-Wind/LogicSugar/releases), drop it into Mindustry's mods directory, enable it in the in-game mods list, then open the logic processor editor.

> [!note]
> If your network environment does not support high-speed GitHub downloads, you can join the [QQ group](https://qm.qq.com/q/QjHwsXMQ48), or use the [game launcher](https://github.com/DeterMination-Wind/Xenon) to get mirror downloads from a domestic server.

## Build from Source

Prerequisites:

- **Java 17+**
- A built copy of the Mindustry sources next to this repository (compilation depends on `../Mindustry-master/desktop/build/libs/Mindustry.jar`)
- For packaging the Android side, a local Android SDK with **D8** and at least one platform's `android.jar` (specified via the `ANDROID_SDK_ROOT`, `ANDROID_HOME`, or `D8_PATH` environment variable)

~~~powershell
.\gradlew.bat deploy
~~~

The output `build/libs/LogicSugar-v<version>.jar` is a cross-platform JAR supporting both desktop and Android; the plain `build` task also triggers deploy.

## Acknowledgments

Parts of the design and implementation of Logic Sugar benefit from the following projects; thanks to their authors for their work:

- [MlogAssertions](https://github.com/cardillan/MlogAssertions) (MIT) — The assertion system is directly ported from this project, and the statement format remains compatible with it. Currently, assertion code generated by Mindcode can be opened directly in Logic Sugar.
- [Mindcode](https://github.com/cardillan/mindcode) (MIT) — Part of the ideas for the expression subsystem (Expr) come from this project.
- [mindustry_logic_bang_lang](https://github.com/A4-Tacks/mindustry_logic_bang_lang) (GPL-3.0) — Reference for ideas on the decompiler and static checking (always-jump-chain threading, logic_lint-style checks).
- [logic-assist](https://github.com/nosbhghggg/logic-assist) (GPL-3.0) — Source of the idea of coloring jump lines by destination; this project was initially developed based on this mod.
- [MI2-Utilities](https://github.com/BlackDeluxeCat/MI2-Utilities) (GPL-3.0) — logic-assist's acknowledgments include Mi2U ~though I don't know why either~

## License

This project is open source under the [GNU GPL v3](LICENSE) license.