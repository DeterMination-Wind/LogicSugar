# Logic Sugar v5.0.0

> **BE 预发布版本 / Bleeding-edge pre-release**
>
> **最低要求：Mindustry BE 27771。** 尚未确认可在官方稳定版（例如 v155.4 / 当前 v160）上安全运行，因此以预发布形式提供。
>
> 本版本未经过完整的正式版客户端验证，可能存在未知 bug。安装前请备份重要地图和逻辑程序，不建议在正式服务器或重要存档中直接替换稳定版本。

## 中文

这是自正式版 **v4.0.0** 以来的一次大版本更新，面向希望把逻辑写得更像「有结构的程序」的玩家。保存出去的仍是原版能跑的逻辑代码：没装本模组的客户端可以运行，联机也不受影响。请使用 **Mindustry BE 27771 或更高版本**，普通稳定版 Mindustry 不会加载本包。

**数据结构**

在编辑器里放一张声明卡，就可以给内存块的一段空间起名，再用更直观的写法读写：

- 数组、二维矩阵、数组初始化；求和 / 平均 / 最值 / 计数 / 查找 / 填充 / 复制 / 排序，以及倒序、替换、交换、升序二分查找。
- 记录（一组命名字段）。
- 栈、队列、双端队列。
- 位集。
- 哈希表、无序集合（声明卡叫 `uset`，因为 `set` 已经是原版指令）。
- 列表、小顶堆、链表。

声明卡本身不会写进保存的代码。运算会变成普通原版指令，没装模组的人也能跑。哈希表、集合、链表第一次用之前，请按卡片提示先做一次清空 / 初始化。

**编辑器**

- 积木按类分开放：流程控制、数据结构、数组算法、断言，不再全部挤在原版「流程控制」里。
- 撤销与重做：电脑 Ctrl+Z / Ctrl+Y，手机在编辑器底部两个按钮。
- 底部显示当前编译后的指令条数对照上限；超限时关掉编辑器会被拦住。
- 语句卡片标题可以本地化，也可在设置里改回英文。
- 继续保留：跳转线着色、框选复制、隐藏内部变量、复制变量 / 打印缓冲、处理器头顶的运行状态。

**断言与调试**

- 新增「断言数据类型」卡片，可检查值是数字、空值、字符串、内容、方块、单位还是队伍。
- 失败时会附上期望值与实际值；可禁用断点、让断言失败变成断点、断点时分开视角。
- 「调试断言构建」仍只在单机 / 地图编辑器生效。联机时保存的程序永远不含这些调试指令。

**打开已保存的程序**

- 重新打开自己用 Logic Sugar 保存的处理器时，会尽量把 `if` / `for` / `while` 以及数组、栈、记录等声明卡还原成当初的积木，而不是一堆跳转。
- 别人手写的纯原版代码只会尝试还原控制流，不会凭空长出数据结构。

**兼容性**

- 保存的程序仍是纯原版 mlog，指令条数不超过 1000，联机（加入或自建）与原版客户端完全兼容。
- 调试类选项只在单机生效；请勿把「调试断言构建」下保存的程序分享到多人环境。

**请注意**

- 这是 BE 预发布版本，不是面向普通稳定版的正式发布。
- **最低 Mindustry BE 版本：27771。** 更低版本和普通稳定版不受支持。尚未把最低版本改到官方 v155.4：较新的正式版客户端在编辑器接口和内存读写语义上已有变化，本包未针对那些变化做适配。
- 使用前请备份重要地图和逻辑程序；遇到问题时，请提供 Mindustry BE 构建号和复现步骤。

## English

This is a major update since the last official release **v4.0.0**, for players who want logic that reads like structured programs. Everything you save is still vanilla-compatible mlog: clients without this mod can run it, including in multiplayer. Please use **Mindustry BE 27771 or newer**. Regular stable Mindustry will not load this package.

**Data structures**

Drop a declaration card to name a slice of a memory block, then read and write it in a more natural form:

- Arrays, 2-D matrices, array initialization; sum / average / min-max / count / find / fill / copy / sort, plus reverse, replace, swap and ascending binary search.
- Records (named fields).
- Stacks, queues and deques.
- Bitsets.
- Hash maps and unordered sets (the card is `uset`, because `set` is already a vanilla opcode).
- Lists, min-heaps and linked lists.

Declaration cards never enter the saved code. Operations become ordinary vanilla instructions, so unmodded clients can run them. Follow the card hints to clear or initialize maps, sets and lists before the first use.

**Editor**

- Cards are grouped: flow control, data structures, array algorithms and assertions, instead of crowding vanilla Flow Control.
- Undo and redo: Ctrl+Z / Ctrl+Y on desktop, two buttons at the bottom of the editor on mobile.
- A live compiled-instruction count against the processor limit; closing is blocked when the program is over the cap.
- Statement-card titles can be localized, or switched back to English in settings.
- Still included: colored jump lines, box-select copy, hidden internal variables, copy-variables / print-buffer buttons, and processor status on the map.

**Assertions and debugging**

- New "Assert Type" card: check whether a value is a number, null, string, content, building, unit or team.
- Failures show expected vs actual values; breakpoints can be disabled, failed assertions can become breakpoints, and the camera can stay detached at a breakpoint.
- "Debug Assert Build" still applies only in single-player / the map editor. Multiplayer saves never contain those debug instructions.

**Reopening saved programs**

- When you reopen a processor saved with Logic Sugar, structured blocks (`if` / `for` / `while`) and data-declaration cards (arrays, stacks, records, …) are restored whenever they still match the saved instructions — not a wall of jumps.
- Hand-written vanilla mlog recovers control flow only; it will not invent data-structure cards.

**Compatibility**

- Saved programs remain plain vanilla mlog, at most 1000 instructions, fully compatible with vanilla clients in multiplayer (joining or hosting).
- Debug options apply only in single-player. Do not share programs saved under Debug Assert Build into multiplayer.

**Please note**

- This is a BE pre-release, not a stable release for regular Mindustry.
- **Minimum requirement: Mindustry BE 27771.** Older builds and regular stable releases are not supported. The minimum was not moved to official v155.4: newer stable clients have editor-API and memory-read changes this package does not adapt to yet.
- Back up important maps and logic programs before installing. When reporting a problem, include your Mindustry BE build number and reproduction steps.
