# Logic Sugar v2.3.0

## 中文

本次更新为条件语句带来 **Expr 表达式模式**，并修好了拖拽布局的多处手感问题。

**新功能**

- `if / elif / for / while` 条件语句支持完整表达式（如 `a > b && ready`）：用按钮在「三段式条件」和「表达式」两种模式间一键切换，按钮显示当前模式（op / Expr）。
- 表达式复用现有 Expr 编辑器，支持运算符、括号、嵌套与函数调用；编译时自动 lower 为 op 链 + 临时布尔变量，再输出原生 jump，产物仍是标准 mlog，旧存档完全兼容。
- 编辑器中无效的 Expr 条件会实时标红。

**修复**

- 拖拽积木的间距与布局几何统一：拖动时分得更开、滚动条同步变长，插入位置判定与视觉空隙同帧一致，不再出现"积木被顶出可视区而滚动条不动"的情况。
- 布局切换时修正锚点偏移：`blockend` 等高小的积木拖动时不再偏离鼠标。
- 表达式输入框保证最小可点击宽度；输入框/表达式编辑器不再被拖拽状态机吞掉焦点（修复了点击无法聚焦输入的回归）。
- 缩进深度封顶 3 层，深嵌套时行尾按钮（EXPR/OP/折叠）不再被顶出屏幕；折叠按钮改为可见样式。
- 拖拽后兜底恢复紧凑布局，各种退出路径（删除/取消/关闭）都不会留下 10f 间距。

**兼容性**

- `expr "..."` 序列化格式仅在新格式下启用；变量名恰好叫 `expr` 的旧存档（如 `ifbegin expr lessThan 5 4`）仍按三段式解析，不会被误判。

## English

This release adds an **Expr expression mode** to condition statements and fixes drag-layout behavior.

**New features**

- `if / elif / for / while` conditions now support full expressions (e.g. `a > b && ready`): toggle between the classic three-part condition and the expression editor with a button that shows the current mode (op / Expr).
- Expressions reuse the existing Expr editor and support operators, parentheses, nesting, and function calls. At compile time they lower to an op chain + compiler-private temporary boolean, then native jumps — output stays standard mlog and old saves stay compatible.
- Invalid Expr conditions are marked red live in the editor.

**Fixes**

- Drag spacing is now consistent with layout geometry: blocks spread out while dragging and the scrollbar grows in sync; insertion-position detection matches the visual gap in the same frame.
- Anchor compensation on layout switch: short blocks like `blockend` no longer drift away from the mouse.
- The expression input keeps a clickable minimum width, and inputs/expression editors are never swallowed by the drag state machine (focus regression fixed).
- Indentation depth is capped at 3 levels so end-of-row buttons (EXPR/OP/fold) stay on screen in deep nesting; the fold button now has a visible style.
- Compact layout is restored on every drag exit path (move/copy/cancel/delete), so no stray 10f spacing remains.

**Compatibility**

- The `expr "..."` serialization format is only used in the new mode; legacy saves whose variable is literally named `expr` (e.g. `ifbegin expr lessThan 5 4`) still parse as the three-part form.