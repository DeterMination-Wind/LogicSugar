# Logic Sugar v4.0.0

## 中文

本次大版本为 Logic Sugar 引入了运行时检查（断言）系统与一组调试辅助功能：在程序里布置"体检点"，程序跑偏时当场停下并告诉你错在哪；处理器的工作状态也会直接画在地图上。多人联机兼容性不受影响：与原版客户端联机时，保存的程序依然完全通用。

**新功能**

- 断言语句（新增「断言」分类，七张卡片）：
  - 断言边界：检查变量（典型是数组下标）是否越界、是否为整数或某数的倍数——把"下标越界导致处理器行为诡异"变成明确的报错。
  - 断言相等：变量的值与期望不符时，程序停在那一行并显示提示。
  - 记录打印位置 + 断言打印：成对使用，验证某段代码打印出的文字是否正确，验证通过后自动清理这段输出，不干扰正常打印。
  - 运行错误：立即停机，消息里可以带上任意变量的当前值。
  - 写日志：把消息写进游戏日志文件，不打断程序运行。
  - 断点：条件满足时整个游戏当场冻结，所有处理器、变量与内存原样保留，可以从容检查现场，继续后照常运行。
- 处理器状态指示：停机的处理器头顶显示"已停在第 N 条"；长等待的处理器显示进度圆环；运行出错的处理器原地显示错误消息。等待阈值、检查频率与提醒特效均可在设置中调节。
- 编辑器新增两个复制按钮：「复制变量」把当前处理器的全部变量整理成表格复制到剪贴板（按名称排序、保留完整精度，可直接粘贴进电子表格），「复制打印缓冲」复制程序当前打印的内容。
- 调试断言构建开关（仅单机/地图编辑器生效）：想让断言真正在游戏里运行时打开它。保存出来的调试程序只供自己本地使用，请勿分享给其他玩家。

**兼容性**

- 多人联机（加入或自建）时，一切调试选项自动关闭，保存的程序与原版客户端完全兼容——这条底线已写入项目规范并纳入手测清单。
- 默认设置下，断言只存在于编辑器中，保存的代码不含它们；断言内容随 Logic Sugar 源码一并保存，重开编辑器原样恢复。

**修复**

- 函数库入口按钮在首次打开编辑器后消失的问题。
- 英文界面下三个设置项的按钮文字与标题重叠。
- 断言分类标题显示为乱码占位、断言卡片字段被挤压截断的显示问题。

## English

This major release brings a runtime-check (assertion) system and a set of debugging aids to Logic Sugar: plant checkpoints in your program and it stops right where things go wrong, telling you why. Processor status is now drawn directly on the map. Multiplayer compatibility is unaffected: everything you save remains fully vanilla-compatible when playing with others.

**New features**

- Assertion statements (new "Assertions" category, seven cards):
  - Assert Bounds: checks that a variable (typically an array index) stays in range and is an integer or a multiple of a value — turning silent out-of-bounds weirdness into a clear error.
  - Assert Equals: when a variable's value differs from what you expect, the program stops right there and shows your message.
  - Assert Flush + Assert Prints: used as a pair to verify the text a section of your program prints; once verified, the test output is cleaned up automatically.
  - Error: halts the program immediately with a message that can embed the current values of any variables.
  - Log: writes a message to the game log file without interrupting the program.
  - Breakpoint: freezes the whole game at a chosen instruction with every processor, variable and memory cell intact — inspect at leisure, then resume as usual.
- Processor status on the map: stopped processors show "Stopped at #N" above them, long waits draw a progress ring, and failures show their message in place. Wait threshold, scan rate and warning effects are adjustable in settings.
- Two new editor buttons: "Copy Variables" dumps every variable of the current processor to the clipboard as a name-sorted, full-precision table ready for spreadsheets; "Copy Print Buffer" copies the current print output.
- Debug Assert Build toggle (single-player / map editor only): turn it on when you want assertions to actually run. Such debug programs are for local use only — do not share them with other players.

**Compatibility**

- In multiplayer (joining or hosting) every debug option switches itself off, and everything you save stays fully compatible with vanilla clients — this bottom line is now part of the project rules and the manual test checklist.
- By default, assertions live only in the editor: saved code does not contain them, and they are stored together with the Logic Sugar source and restored on reopen.

**Fixes**

- The function library button no longer disappears after the first editor session.
- Fixed overlapping title and value text on three settings entries in English.
- Fixed the Assertions category heading showing a placeholder and assertion cards having squeezed, truncated fields.
