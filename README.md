# Logic Sugar

[中文](README_zh.md) | [English](README.md)

<p align="center"><img src="assets/LOGO.jpg" width="500" alt="Logic Sugar Logo"></p>

A Mindustry mod that adds structured programming control flow — `for`, `while`, `switch`, and `break` — to the logic editor, along with jump-line coloring, box-select batch operations, and an expression editor. Everything compiles to vanilla-compatible `mlog`.

## Features

### Structured Statements

Write readable loops and branches inside the logic processor. These compile to standard `jump`/`op` instructions — players without the mod see normal vanilla logic.

<details>
<summary><b>for</b> — loop with initializer, step, and end condition</summary>

```
for i = 0; i < 10; i += 2
  ...
end
```
</details>

<details>
<summary><b>while</b> — conditional loop</summary>

```
while signal == 1
  ...
end
```
</details>

<details>
<summary><b>switch</b> — multi-branch selection</summary>

```
switch state
  case 0
    ...
    break
  case 1
    ...
    break
end
```
</details>

- Auto-indentation, folding, and structural guidelines
- Errors are preserved in-editor for easy fixing

### Jump Line Coloring

Colorizes `jump` curves by target so different branches are visually distinguishable. Toggle via in-game mod settings.

- **Off**: all lines white (vanilla)
- **Scattered**: golden-angle HSV color per target index
- **Block-tinted**: target block's category color, brightened 1.4×

### Box Select & Batch Operations

Select, move, copy, and delete logic blocks in bulk.

- Drag on empty canvas to box-select; **blue** = move, **green** = copy (toggle via the copy icon on selected blocks)
- `Ctrl` + click → copy-drag a single block; `Delete`/`Backspace` → delete selected; Right-click/`Esc` → cancel
- After selection, buttons on selected blocks become batch ops: trash → delete all, `+` → duplicate, copy icon → toggle mode

### Expression Editor (`Expr` block)

Write math expressions that compile to `op` chains, and fold `op` chains back into readable expressions.

**Compile**: `result = cos(a) * 10 + x` →

```
op cos _0 a 0
op mul _0 _0 10
op add x _0 x
```

- **Fold**: opening the editor folds consecutive `op` chains back into expression form
- **Save**: expressions unfold to standard `op` instructions — vanilla-compatible
- **Syntax highlighting**: numbers (gold), functions (coral), variables (white), operators (light gray)
- **Error reporting**: syntax errors shown in red beneath the expression with the specific reason

**Supported operators**

| Category | Operators |
|---|---|
| Unary functions | `not abs sign log log10 floor ceil round sqrt rand sin cos tan asin acos atan` |
| Binary functions | `max(a,b) min(a,b) angle(a,b) angleDiff(a,b) len(a,b) noise(a,b) logn(a,b)` |
| Logical | `\|\|` `&&` ` xor ` |
| Equality | `==` `!=` `===` `<` `>` `<=` `>=` |
| Bitwise | `&` `<<` `>>` `>>>` |
| Arithmetic | `+` `-` `*` `/` `//` `%` `%%` `^` |

### Functions (`Func Def` / `Func Call` / `Return` blocks)

Wrap logic into functions with parameters (full expressions), optional return values, void functions, early `return`, and mutual calls (no recursion). Functions can be defined inside a processor (**local functions**) or in a global **function library** shared by every processor.

```
funcdef f a,b          # define f with parameters a, b
  op add s a b
  return "s * 2"
blockend
set x 3
funccall f "x, 4" out  # call: out = (3+4)*2
```

- **Parameters** are full expressions (e.g. `funccall f "cos(x) * 2"`), evaluated at the call site and bound to the parameters; the argument count must match
- **Return values**: `return "<expression>"` produces a value received by the `=` field of the call block; requesting a result from a function that never returns a value is a compile error
- **Early return**: `return` (void) or `return "<expression>"` exits the function from anywhere
- **Mutual calls**: functions may call other functions (including library ones); direct or indirect recursion is a compile error that names the cycle
- **Jump limits**: `jump` may not cross a function boundary — jumps inside a body must stay inside it (a jump to the function's own `blockend` is an early exit); `break` only applies to loops/switches inside the body

**Compile mode** (Settings → Logic Sugar → Function Mode):

- `normal` (default): each function compiles to one shared subroutine; call sites save the return address via `@counter`. Cost ≈ body + 2~3 instructions per call; cheaper than inline once a function is called 2~3 times
- `inline`: every call site gets its own copy of the body (labels namespaced per call site). Cost ≈ N × body; many calls can hit the 1000-instruction limit (the error suggests switching to normal)

**Function library** (global functions):

- Stored at `<game data>/mods/config/LogicSugar/functions.txt`, edited from Settings → Logic Sugar → Open Function Library (reuses the visual block canvas, validated on save)
- Library functions **cannot modify caller variables**: every name the body writes is mangled to `__ls_func_<name>_<original>`; read-only names stay untouched (the caller's globals remain readable); `@` system variables and `cell1`/`bank1`/`memory1` storage devices are exempt
- Library functions can only call other library functions; processor calls resolve local functions first, then the library (a local function shadows a library one)
- Undefined calls and damaged library files produce clear compile errors; unreachable function bodies emit zero instructions

**Reserved prefixes**: `__ls_`, `__ls_func_`, `__ls_f_`, `__ls_i_` are reserved for the compiler — do not use them for function names, parameter names, or variables.

### Scrollbar Enhancement

Colored scrollbar (each segment tinted by its block's category color), click-to-jump, and hover-jump preview.

## Build

```
gradlew deploy
```

Output: `build/libs/LogicSugar.jar` (universal JAR for both desktop and Android). Drop into Mindustry's `mods/` folder.

## Acknowledgements

- [logic-assist](https://github.com/nosbhghggg/logic-assist) — foundation for jump-line coloring, box-select, and expression editor features
- [MI2-utilities](https://github.com/BlackDeluxeCat/MI2-Utilities-Java) — drag-move and jump-index translation logic
- [mindcode](https://github.com/cardillan/mindcode) — op-chain decompilation, operator classification, optimization rules
- [MindustryX](https://github.com/TinyLake/MindustryX/) — JUMP button reference

## License

GPL-3.0-or-later. See [LICENSE](LICENSE).