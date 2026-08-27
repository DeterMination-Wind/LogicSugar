# Logic Sugar v3.0.0

## 中文

本次重大更新让 Logic Sugar 不仅能编译结构化逻辑，还能从原版 mlog 安全恢复结构，并为大型 `switch` 程序提供更紧凑的分派方式。

**新功能**

- `switch` 在 `auto` 策略下会按实际可执行指令成本，在传统比较链和 `@counter` 跳转表之间自动选择；整数 case 值、重复 case 值和稠密值域可获得更紧凑的输出，非整数值或跨度超过 255 时自动回退到比较链。
- 新增 `logicsugar.switchStrategy` 设置；使用 `chainOnly` 可以复现旧版比较链输出。
- 编译后增加无条件跳转链穿线，减少结构化控制流生成的冗余跳转，同时保留循环和自跳转语义。
- 新增 vanilla mlog 反编译器，可恢复 `if / elif / else`、`while`、`for`、`switch / case`、normal 函数、函数调用和返回值。
- 反编译恢复结果必须经过重新编译并与原始指令流归一化比较；无法确认的部分继续以原版 mlog 显示，不会擅自改写程序。
- 编辑器支持在原始 mlog 视图和经过验证的 Sugar 视图之间切换，并在存在未保存修改或保存失败时阻止误切换。

**修复**

- 修复嵌套函数调用参数拆分、函数名检查、返回值表达式和负数字面量处理问题。
- 修复表达式和成员访问高亮、非法函数表达式实时标红，以及紧凑条件控件在窄布局中的显示问题。
- 修复 Mindustry mod classloader 下访问受保护编辑器成员可能触发 `IllegalAccessError` 的问题。
- 增加编译器、反编译器和跨 classloader 回归测试，并将其接入 `check`。

**兼容性**

- 生成结果仍为 vanilla-compatible mlog；旧程序可以继续运行。
- 反编译器无法验证的未知指令会保留为原始 mlog，以优先保证程序语义不变。

## English

This major release makes Logic Sugar bidirectional: it can safely recover structured source from vanilla mlog, while producing denser dispatch code for larger `switch` programs.

**New features**

- With the `auto` strategy, `switch` chooses between the legacy comparison chain and an `@counter` jump table by executable-instruction cost. Integer case values, repeated cases, and dense ranges can produce smaller output; non-integer values or spans above 255 fall back to the comparison chain automatically.
- Added the `logicsugar.switchStrategy` setting. Use `chainOnly` to reproduce the legacy comparison-chain output.
- Added unconditional jump-chain threading after lowering to remove redundant control-flow jumps while preserving loops and self-jumps.
- Added a vanilla mlog decompiler that can recover `if / elif / else`, `while`, `for`, `switch / case`, normal-mode functions, calls, and return values.
- Every recovered result is recompiled and compared against the normalized original instruction stream. Uncertain regions remain vanilla mlog instead of being rewritten speculatively.
- The editor can switch between the original mlog view and a verified Sugar view, and protects unsaved edits or failed saves during view changes.

**Fixes**

- Fixed nested function-call argument splitting, function-name validation, return-value expressions, and negative-literal handling.
- Fixed expression and member-access highlighting, live error marking for invalid function expressions, and compact condition controls in narrow layouts.
- Fixed possible `IllegalAccessError` access to protected editor members across the Mindustry mod classloader boundary.
- Added compiler, decompiler, and cross-classloader regression tests and wired them into `check`.

**Compatibility**

- Generated output remains vanilla-compatible mlog, so existing programs continue to run.
- Unrecognized instructions remain in their original vanilla form when recovery cannot be verified, prioritizing semantic safety.
