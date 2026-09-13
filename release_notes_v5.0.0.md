# Logic Sugar v5.0.0

> **BE 预发布版本 / Bleeding-edge pre-release**
>
> **最低要求：Mindustry v160.1。** 本版本按 v160 Logic API 适配，仍以预发布形式提供。
>
> 本版本未经过完整的正式版客户端验证，可能存在未知 bug。安装前请备份重要地图和逻辑程序，不建议在正式服务器或重要存档中直接替换稳定版本。

## 中文

这是自正式版 **v4.0.0** 以来的一次大版本更新，面向希望把逻辑写得更像「有结构的程序」的玩家。保存出去的仍是原版能跑的逻辑代码：没装本模组的客户端可以运行，联机也不受影响。请使用 **Mindustry v160.1 或更高版本**。

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

**v5 API（`logic-sugar-v2`）**

- **失败判断统一成 -1**：压栈 / 入队 / 追加 / 插入 / 删除 / 越界写入 / 释放节点等操作失败时一律返回 `-1`（旧版有的是 `0`，有的返回原容量）。老程序里写 `== 0` 判失败的表达式请改成 `== -1`。
- **查询与取值不变**：`maphas` / `uhas` / `btest` 仍是 0/1（`btest` 越界仍是 0）；空容器取值仍是 NaN —— 先用 `size`/`has` 判断再取值。
- **无结果卡**：`bset` / `bclr` / `cshead` 不再需要目标变量，卡片可以写 `~`；老卡片保留目标变量也能继续用。
- **函数返回声明**：`funcdef f a ~`（void，体内不得返回値）或 `funcdef f a value`（必须返回值）；不写就沿用原来的「看函数体推断」。
- **值不再被悄悄折成 1/0**：对象、单位、字符串、空值在赋值 / 传参 / 返回 / 记录字段 / 堆取值时原样保留。
- **老存档照常打开**：验证门会用 v5 之前的 lowering 复现旧指令流；重新保存时标记升级为 `logic-sugar-v2`，源码不变。

**兼容性**

- 保存的程序仍是纯原版 mlog，指令条数不超过 1000，联机（加入或自建）与原版客户端完全兼容。
- 调试类选项只在单机生效；请勿把「调试断言构建」下保存的程序分享到多人环境。

**请注意**

- 这是面向 Mindustry v160.1 的预发布版本；更低版本不受支持。
- **最低 Mindustry 版本：v160.1。** 本包已针对 v160 的编辑器接口和内存读写语义完成适配。
- 使用前请备份重要地图和逻辑程序；遇到问题时，请提供 Mindustry 版本号和复现步骤。

## English

This is a major update since the last official release **v4.0.0**, for players who want logic that reads like structured programs. Everything you save is still vanilla-compatible mlog: clients without this mod can run it, including in multiplayer. Please use **Mindustry v160.1 or newer**.

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

**v5 API (`logic-sugar-v2`)**

- **Failure means -1**: push / enqueue / append / insert / delete / out-of-range write / node-free now all report `-1` when they fail (older builds returned `0` for some and the old capacity for others). Change `== 0` failure tests in existing programs to `== -1`.
- **Queries and value reads are unchanged**: `maphas` / `uhas` / `btest` still return 0/1 (`btest` out of range is still 0), and reading from an empty container still yields NaN — check `size`/`has` before reading.
- **Void cards**: `bset` / `bclr` / `cshead` no longer expose a destination and accept `~`; cards that still carry a destination keep the exact same instruction stream.
- **Declared returns**: `funcdef f a ~` (void, no value return in the body) or `funcdef f a value` (must return one); leaving it out keeps the old infer-from-body behaviour.
- **Values are no longer silently folded to 1/0**: objects, units, strings and null keep their identity through assignment, arguments, returns, record fields and heap pops.
- **Old saves still open**: the verification gate re-lowers them with the pre-v5 API; re-saving upgrades the tag to `logic-sugar-v2` without touching the source.

**Compatibility**

- Saved programs remain plain vanilla mlog, at most 1000 instructions, fully compatible with vanilla clients in multiplayer (joining or hosting).
- Debug options apply only in single-player. Do not share programs saved under Debug Assert Build into multiplayer.

**Please note**

- This is a pre-release targeting Mindustry v160.1; older versions are not supported.
- **Minimum requirement: Mindustry v160.1.** The editor API and memory-read semantics introduced in v160 are covered by this adaptation.
- Back up important maps and logic programs before installing. When reporting a problem, include your Mindustry version and reproduction steps.
