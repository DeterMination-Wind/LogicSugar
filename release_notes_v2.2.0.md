# Logic Sugar v2.2.0

## 中文

本次更新带来一批新功能，并针对两份深度代码审查发现的问题做了全面修复。

**新功能**

- 编辑器会标红提示错误的 `return` 语句（例如写在函数外时），写错时一眼就能看出来。
- 把鼠标悬停在语句上会显示简短的说明提示，方便了解每个积木的用途。
- 添加语句的搜索框中，匹配到的内容会高亮显示，找语句更轻松。
- 设置页面排版优化，选项对齐更整齐。

**修复与改进**

- 修复了极端情况下处理器程序可能被意外清空的问题：打开编辑器前会先检查存档代码，遇到损坏或异常的内容时给出明确提示，不会再出现"打开后一片空白、一保存程序就没了"的情况。
- 修复了函数调用参数、返回值里包含引号等特殊符号时，保存后内容损坏的问题。
- 修复了函数内打印的文本（含多个空格或特殊字符）被自动改写的问题。
- 修复了 `memory1` 等存储设备在函数库中可能被误改名的罕见问题。
- 编辑器遇到损坏的代码时不再崩溃，而是显示清晰的错误信息。
- 性能优化：编辑大型程序时更流畅，函数库读取更快，界面响应更灵敏。
- 修复了按键重复触发、数据残留累积等稳定性问题。
- 与 Neon 捆绑使用时，设置项不再重复出现。
- 内部回归测试全面恢复运行，并新增针对性用例，后续版本更可靠。

## English

This update adds a set of new features and fixes everything found by two deep code reviews since the last release.

**New features**

- Out-of-place `return` statements (such as outside a function) are now highlighted in red, so mistakes are visible at a glance.
- Hovering over a statement shows a short explanation of what it does.
- The statement search box highlights matches while you type.
- Settings page alignment was cleaned up.

**Fixes and improvements**

- Fixed a rare case where a processor program could be silently wiped: the editor now validates saved code before opening and shows a clear message for corrupted content instead of presenting an empty canvas that overwrites the program on save.
- Fixed arguments and return values containing quotes or other special characters getting corrupted when saved.
- Fixed printed text inside functions (multiple spaces, special characters) being rewritten.
- Fixed a rare mis-rename of `memory1`-style storage devices inside the function library.
- The editor no longer crashes on corrupted code; it shows a readable error instead.
- Performance improvements when editing large programs; faster function library loading and snappier UI.
- Fixed duplicated key handling and stale data accumulation issues.
- Settings entries no longer appear twice when bundled with Neon.
- Internal regression tests are fully wired back into the build with new targeted cases, making future releases more reliable.

The universal JAR works on desktop and Android.