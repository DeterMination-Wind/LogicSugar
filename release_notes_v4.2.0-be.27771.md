# Logic Sugar v4.2.0-be.27771

> **BE 预发布版本 / Bleeding-edge pre-release**
>
> **最低要求：Mindustry BE 27771。**
>
> 本版本未经过详细测试，可能存在未知 bug。安装前请备份重要地图和逻辑程序，不建议在正式服务器或重要存档中直接替换稳定版本。

## 中文

这是专门面向 Mindustry BE 的体验版本，不适用于普通稳定版 Mindustry。请使用 **Mindustry BE 27771 或更高版本**。

**本版本可以体验：**

- 一整套新的数据结构卡片：记录、栈、队列、位集、哈希表、列表、小顶堆和链表。在编辑器里放一张声明卡，就可以在表达式中直接使用它们的操作。
- 数组能力增强：二维矩阵（`m[i][j]`）、数组长度 `len(buf)`、数组初始化卡片，以及求和、平均、最值、计数、查找、填充、复制、排序等批量运算。
- 声明数据时会检查内存块容量（内存元格 64 格、内存库 512 格），越界会直接报错而不是悄悄写错地方。
- 调试更安全：开启「调试断言构建」后，用变量做下标的数组访问会自动带上越界断言。
- 断言体验同步上游改进：失败信息会附上期望值与实际值；可以禁用断点、让断言失败直接变成断点、断点时分离视角；处理器状态扫描在视野外不再绘制；断言卡片在调色板里也有说明。
- 所有保存的代码仍然是纯原版 mlog，联机时与原版客户端完全兼容。

**请注意：**

- 这是 BE 预发布版本，不是稳定版。
- **最低 Mindustry BE 版本：27771。** 更低版本和普通稳定版不受支持。
- 本版本**未经过详细测试，可能存在未知 bug**，也可能出现显示或兼容性问题。
- 使用前请备份重要地图和逻辑程序；遇到问题时，请提供 Mindustry BE 构建号和复现步骤。

## English

This is a **Bleeding Edge pre-release** made for Mindustry BE. It is not intended for the regular stable release of Mindustry. Please use **Mindustry BE 27771 or newer**.

**What you can try:**

- A full set of new data-structure cards: records, stacks, queues, bitsets, hash maps, lists, min-heaps and linked lists. Drop a declaration card in the editor and use their operations directly in expressions.
- Stronger arrays: two-dimensional matrices (`m[i][j]`), array length via `len(buf)`, an array-initialization card, and bulk operations such as sum, average, min/max, count, find, fill, copy and sort.
- Capacity checks when declaring storage (memory cells hold 64 slots, memory banks 512), so an out-of-range layout is reported instead of silently writing to the wrong place.
- Safer debugging: with "Debug Assert Build" enabled, array accesses with a variable subscript automatically carry a bounds assertion.
- Assertion improvements synced from upstream: failure messages include the expected and actual values; breakpoints can be disabled, failed assertions can become breakpoints, and the camera can stay detached at a breakpoint; the processor status scan no longer draws processors outside the viewport; assertion cards now have palette descriptions.
- Everything saved is still plain vanilla mlog, fully compatible with vanilla clients in multiplayer.

**Please note:**

- This is a BE pre-release, not a stable release.
- **Minimum requirement: Mindustry BE 27771.** Older builds and regular stable releases are not supported.
- This version has **not been thoroughly tested and may contain unknown bugs**, including visual or compatibility issues.
- Back up important maps and logic programs before installing. When reporting a problem, include your Mindustry BE build number and reproduction steps.
