# Logic Sugar v5.1.0

> **最低要求：Mindustry v160.1。**
>
> 保存出去的程序仍然是普通原版 mlog：没装模组的客户端能运行，联机（加入或自建服务器）不受影响。
>
> 安装前建议备份重要地图和逻辑程序。遇到问题时，请附上 Mindustry 版本号与复现步骤。

## 中文

这是自正式版 **v4.0.0** 以来的一次大版本更新，把 Logic Sugar 的结构化编辑体验补齐成一套完整工具：
控制流、表达式、函数，以及一整套「像数组一样好写」的数据结构。所有保存的代码仍然可以在原版客户端运行。
所有 v4 老存档都能正常打开，重新保存时自动升级。

v5.0.0 曾以预发布形式提供过数据子系统与 v5 API，本版把它们一并带出，并新增了下面这些内容。

**本版新增**

- 每一种数据结构操作都有独立积木卡，并按结构分类；表达式里也能用更自然的 getter 写法（见下）。
- 数组排序换成希尔排序，明显更快；函数库上限提高到 10000 条语句。
- 单位 flag 彩色显示、卡片标题本地化、编辑器底部栏与断言卡片布局等一系列体验改进。
- 修复 `while` 条件文字误导，以及对象、单位、字符串、空值在传递中被悄悄转成 `1` / `0` 的问题。

**数据结构操作卡**

以前很多数据结构操作只能写在表达式里，现在每一种操作都有自己的一张积木卡，放在对应分类下随取随用：

- 数组与矩阵：求和 / 平均 / 最值 / 计数 / 查找 / 填充 / 复制 / 升序降序排序 / 倒序 / 替换 / 交换 / 二分查找。
- 栈：`spush` `spop` `speek` `ssize` `sclear`；队列：`qpush` `qpop` `qpeek` `qsize` `qclear`。
- 双端队列：`dpushf` `dpushb` `dpopf` `dpopb` `dpeekf` `dpeekb` `dsize` `dclear`。
- 位集、哈希表、集合、列表、小顶堆、链表各自的读写与状态操作。
- 卡片会按结构分类（栈操作、队列操作、数组算法……），不再全部堆在「数据结构」里。
- 旧存档里已经放了目标变量的卡片还能照常使用；新卡片中 `bset` / `bclr` / `cshead` 这类没有实际结果的操作可以留空写 `~`。

**数据结构 getter 语法糖**

在表达式里，已经声明的结构可以用更自然的写法读取，编译结果和对应的操作卡完全一致：

- 列表：`l[i]`、`l.get(i)`、`l.size()`、`l.find(v)`。
- 栈：`s.top()`、`s.peek()`、`s.size()`；队列：`q.front()`、`q.peek()`、`q.size()`。
- 双端队列：`d.front()`、`d.back()`、`d.size()`。
- 位集：`b[i]`、`b.test(i)`、`b.count()`。
- 哈希表：`m[k]`、`m.get(k)`、`m.has(k)`、`m.size()`；集合：`s.has(v)`、`s.size()`。
- 链表：`c[i]`、`c.get(i)`、`c.head()`、`c.next(i)`、`c.len()`。

下标写法仍只读：要写入，请使用 `lset`、`bset`、`cset` 等操作卡。

**数组排序更快**

`sortasc` / `sortdesc` 从插入排序换成了希尔排序（原地、不需要额外内存）。结果与以前完全一致，但对随机或逆序数据明显更快：几百个元素的排序不再像以前那样卡住处理器。

- 注意：排序子程序的指令序列变了。**旧版本保存过、并且用过排序卡的处理器，重新打开时会回落到原版 mlog 视图**（数组声明卡与排序卡会显示成原始指令）。把排序卡重新放一次即可恢复正常。这是「重开时逐条核对产物」的必然结果，不是 bug。

**函数库更大**

函数库现在最多可以有 **10000 条语句**（以前受处理器上限影响，只能到 1000 条）。函数库不是某台处理器的保存产物，不会占用单台处理器那 1000 条指令额度；编辑器底部会同时显示「函数库行数 / 上限」，超限时保存会被明确拦住。

**界面与体验**

- 单位 flag 显示：可以选择在单位正上方显示逻辑 flag，并让不同 flag 使用不同的鲜明颜色（默认 0 不显示）。
- 卡片标题本地化：数据卡片标题会跟随游戏语言，也可在设置里切回英文。
- 编辑器底部栏更适应屏幕宽度，「逻辑操作」居中，调试按钮与常用按钮不再互相挤压。
- 断言卡片重建时不再逐次变大，占用格子恢复正常。
- 修复 `while` 卡片把「循环条件」误写成「结束条件」，避免玩家照着写反表达式导致循环体不执行。
- 修复赋值、函数返回、条件临时值与记录字段在传递对象、单位、字符串、空值时被悄悄转成 `1` / `0` 的问题，这些值现在原样保留。

**从 v4 升上来？请确认两处**

- **失败统一返回 `-1`**：压栈、入队、追加、插入、删除、越界写入、释放节点等操作失败时一律返回 `-1`（旧版有的是 `0`，有的返回原容量）。老程序里写 `== 0` 判失败的表达式请改成 `== -1`；查询类 `maphas` / `uhas` / `btest` 仍是 0/1。
- **函数库更大但处理器上限不变**：单台处理器保存的产物仍然不超过 1000 条指令，与原版客户端联机完全兼容。

**安装与兼容性**

- **最低要求：Mindustry v160.1**（桌面或 Android）。
- 调试类选项（如「调试断言构建」）只在单机 / 地图编辑器生效，联机时保存的程序永远与原版一致。
- 需要新版游戏本体，请从 [Releases](https://github.com/DeterMination-Wind/LogicSugar/releases) 下载通用 JAR，放进 mods 目录后在游戏内启用。

**已知事项**

- 这是面向 Mindustry v160.1 的版本，更低版本不受支持。
- 旧版排序程序的重新打开说明见上文「数组排序更快」；其余 v4 老存档可以正常打开并自动升级。
- 使用前请备份重要地图和逻辑程序。

## English

This is a major update since the last official release **v4.0.0**. It rounds Logic Sugar out into a complete structured editor: control flow, expressions, functions, and a full set of data structures that are as easy to write as an array. Everything you save still runs on a vanilla client. Every v4 save opens normally and upgrades itself on the next save.

v5.0.0 offered the data subsystem and the v5 API as a pre-release; this release carries them out and adds the following.

**New in this release**

- Every data-structure operation is its own palette block, grouped by structure, and expressions accept the more natural getter spellings (below).
- Array sorting uses Shell sort for a clear speed-up, and the function library holds up to 10,000 statements.
- Unit-flag coloring, card localization, bottom-bar and assert-card layout polish, and more.
- Fixed the misleading `while` condition label and values such as objects, units, strings and null being silently folded to `1` / `0` in transit.

**Data-structure operation cards**

Most operations used to live only in expressions. Every operation now has its own block in the matching palette category:

- Arrays and matrices: sum / average / min-max / count / find / fill / copy / ascending and descending sort / reverse / replace / swap / binary search.
- Stacks: `spush` `spop` `speek` `ssize` `sclear`; queues: `qpush` `qpop` `qpeek` `qsize` `qclear`.
- Deques: `dpushf` `dpushb` `dpopf` `dpopb` `dpeekf` `dpeekb` `dsize` `dclear`.
- Bitsets, hash maps, sets, lists, min-heaps and linked lists each get their own read, write and status blocks.
- Cards are grouped by structure (Stack Operations, Queue Operations, Array Algorithms, ...) instead of crowding one Data Structures category.
- Cards saved by older versions that already carry a destination keep working. On new cards, operations with no meaningful result such as `bset` / `bclr` / `cshead` accept `~`.

**Data-structure getter sugar**

In expressions, a declared structure can be read with a more natural spelling. The compiled result is exactly the same as the matching operation card:

- List: `l[i]`, `l.get(i)`, `l.size()`, `l.find(v)`.
- Stack: `s.top()`, `s.peek()`, `s.size()`; queue: `q.front()`, `q.peek()`, `q.size()`.
- Deque: `d.front()`, `d.back()`, `d.size()`.
- Bitset: `b[i]`, `b.test(i)`, `b.count()`.
- Hash map: `m[k]`, `m.get(k)`, `m.has(k)`, `m.size()`; set: `s.has(v)`, `s.size()`.
- Linked list: `c[i]`, `c.get(i)`, `c.head()`, `c.next(i)`, `c.len()`.

Index sugar is still read-only. To write, use the `lset`, `bset`, `cset` and similar operation cards.

**Faster array sorting**

`sortasc` / `sortdesc` moved from insertion sort to Shell sort (in place, no scratch memory). The result is identical, but random and reversed data sort much faster: a few hundred elements no longer visibly stall the processor.

- Note: the sort subroutine's instruction sequence changed. **Processors saved by an older version that used a sort card will reopen in the vanilla mlog view** (the array declaration card and the sort card show up as raw instructions). Drop the sort card again to recover. This follows from how reopening verifies the saved stream instruction by instruction, and is not a bug.

**Bigger function library**

The function library now holds up to **10,000 statements** (it used to be capped by the 1000-instruction processor limit). The library is not part of any processor's saved program and does not consume a processor's 1000-instruction budget; the editor footer shows both "library lines / limit" and blocks saving when the library is over its cap.

**Editor and experience**

- Unit flag overlay: optionally draw each unit's logic flag above it, with a distinct vivid color per flag (flag 0 stays hidden by default).
- Card localization: data-card titles follow the game language and can be switched back to English in settings.
- The editor bottom bar adapts to the actual width, centers the logic actions, and keeps debug and common actions from squeezing each other.
- Assert cards no longer grow every time they are rebuilt.
- Fixed the `while` card labelling its loop condition as a termination condition, which invited an inverted expression and a body that never ran.
- Fixed values such as objects, units, strings and null being silently folded to `1` / `0` through assignment, returns, condition temporaries and record fields; they now keep their identity.

**Upgrading from v4? Two things to check**

- **Failures report `-1`**: push / enqueue / append / insert / delete / out-of-range write / node-free all report `-1` on failure (older builds returned `0` for some, the old capacity for others). Change `== 0` failure tests in existing programs to `== -1`. Queries such as `maphas` / `uhas` / `btest` still return 0/1.
- **Bigger library, same processor cap**: a single processor still saves at most 1000 instructions and stays fully compatible with vanilla clients in multiplayer.

**Install and compatibility**

- **Minimum requirement: Mindustry v160.1** (desktop or Android).
- Debug options such as "Debug Assert Build" apply only in single-player / the map editor; multiplayer saves always stay vanilla.
- You need the newer game build. Download the universal JAR from [Releases](https://github.com/DeterMination-Wind/LogicSugar/releases), drop it into the mods directory, and enable it in-game.

**Known items**

- This release targets Mindustry v160.1; older versions are not supported.
- Older sort programs are covered by the note under "Faster array sorting"; other v4 saves open normally and upgrade automatically.
- Back up important maps and logic programs before installing.
