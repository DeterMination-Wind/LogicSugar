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

### Structured control flow

Write common control flow as blocks in the editor. On save, everything compiles to plain vanilla mlog.

| Construct | Syntax | What it does |
| --- | --- | --- |
| Branching | `if`, `elif`, `else` | Structured conditionals. |
| Loops | `for`, `while` | Structured loops. |
| Loop control | `break`, `continue` | Break out of or continue a loop. |
| Multi-branch | `switch`, `case` | Match a value against cases. |

### Expressions and functions

| Feature | Details |
| --- | --- |
| **Expressions as conditions** | Conditions of `if`, `elif`, `while`, and `for` (Expr mode) accept full expressions such as `hp < 25 && !shielded`. |
| **One-line expression statements** | Write `result = (a + b) * 2`; it expands to equivalent instructions on save, folds back on reopen, and invalid expressions are marked red on the spot. |
| **Expressions anywhere a value goes** | Assignments, function arguments, `return` values, and member access such as `@unit.@health`. |
| **Functions** | Define functions with parameters, call them, and return values. Switch between normal (subroutine) and inline modes in settings. |
| **Global function library** | Shared by every processor and edited inside the processor editor. It holds up to 10,000 statements (it is not part of any processor's 1000-instruction budget), is validated and saved automatically on close, and self-repairs if the file gets corrupted. |

### Data structures

Declarations name structured memory regions. They are metadata only: every operation lowers to plain vanilla instructions, so saved programs stay vanilla-compatible and reopen through the Sugar carrier.

| Declaration | Kind |
| --- | --- |
| `array` | Range of a memory block (base + size) |
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

Array expressions can use subscripts such as `buf[i]` and `buf[i] = 5`; they compile to plain vanilla `read` and `write` instructions and fold back into the expression card on reopen.

Every array and container intrinsic has its own persistent operation card, grouped into matching categories (Stack Operations, Queue Operations, Array Algorithms, and so on): `fill`, `sum`, `reverse`, `spush`, `qpop`, `dpushf`, `btest`, `mapset`, `uadd`, `lappend`, `hpush`, `cinit`, `cnew` and the rest. The old eight-slot `arrayinit` token remains load-compatible only. All operations still lower to plain vanilla instructions and reopen through the Sugar carrier. `sortasc` / `sortdesc` now use an in-place Shell sort, which is markedly faster than the previous insertion sort on random or reversed data.

Advanced tutorial: one chapter per structure, with declaration card, function table, lowered mlog walkthrough, complexity, and caveats. See [docs/tutorials/en/README.md](docs/tutorials/en/README.md).

### Data-structure getter sugar

In Expr mode, a declared structure can use subscripts or method spellings for its getters. Index sugar is read-only — use `lset`, `bset`, or `cset` to write.

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

They compile exactly like the corresponding intrinsic (`lget(l, i)`, `speek(s)`, and so on) and still lower to plain vanilla instructions.

### Editor, debugging, and views

| Feature | Details |
| --- | --- |
| **Structure recovery** | Reopening a saved processor restores the structured blocks you edited (`if`, `for`, `while`, `switch`, functions) and data-declaration cards such as arrays, stacks, and records when they were part of the original program. Only fully verified parts come back; everything else stays vanilla. Plain hand-written mlog without Logic Sugar source recovers control flow only — it will not invent data-structure cards. |
| **Original and Sugar views** | Switch between the generated vanilla mlog and the editable Sugar view at any time, with unsaved changes protected before switching. |
| **Editor helpers** | Colored jump lines, `__ls_*` internals hidden from the variable list, Ctrl+Click and Ctrl+Drag statement copying, hover hints, search highlighting, undo and redo (Ctrl+Z and Ctrl+Y on desktop, buttons on mobile), and a live compiled-instruction count against the processor limit. |
| **Assertions** | Eight runtime-check cards: out-of-range array indexes, wrong data types, values that drift from expectations, and print-output comparisons stop the program on the offending line with a message above the processor. A breakpoint freezes the whole game, centers the camera on the processor, and reports the failing line; a log statement writes to the game log. Assertions live only in the editor by default and never enter saved code. The single-player "Debug Assert Build" toggle makes them run for real, and multiplayer saves always stay vanilla-compatible. Settings can disable breakpoints, turn failed assertions into breakpoints, and keep the camera detached while paused. |
| **Processor status on the map** | Stopped processors show which line they stopped on, long waits draw a progress ring, and failures show their message in place (with expected and actual values when available). Threshold, scan rate, and warning effects are adjustable in settings, and processors outside the viewport are skipped. |
| **Unit flags on the map** | Optional setting draws each unit's logic flag above it with a distinct vivid color per flag; flag 0 stays hidden by default. Display only: saves and multiplayer are unaffected. |
| **Copy variables and print buffer** | Dump all variables of the processor being edited as a name-sorted, full-precision table ready for spreadsheets, or copy the program's current print output. |

## Install

This **v5.1.0** release requires **Mindustry v160.1 or later** (desktop or Android). Download the universal JAR from [Releases](https://github.com/DeterMination-Wind/LogicSugar/releases) — a single file for both platforms — drop it into Mindustry's mods directory, enable it in the in-game mods list, then open the logic processor editor.

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

## Acknowledgments

Parts of Logic Sugar build on the work of these projects — thank you to their authors:

- [MlogAssertions](https://github.com/cardillan/MlogAssertions) (MIT) — the assertion system is ported from this project with a compatible statement format, so Mindcode-generated assertion code opens directly in Logic Sugar.
- [Mindcode](https://github.com/cardillan/mindcode) (MIT) — parts of the expression subsystem (Expr) draw on its ideas.
- [mindustry_logic_bang_lang](https://github.com/A4-Tacks/mindustry_logic_bang_lang) (GPL-3.0) — ideas for the decompiler and static checking (always-jump-chain threading, logic_lint-style checks).
- [logic-assist](https://github.com/nosbhghggg/logic-assist) (GPL-3.0) — the idea of coloring jump lines by their destination.
- [MI2-Utilities](https://github.com/BlackDeluxeCat/MI2-Utilities) (GPL-3.0) — a source of inspiration during development.

## License

Licensed under the [GNU GPL v3](LICENSE).
