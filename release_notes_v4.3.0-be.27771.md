# Logic Sugar v4.3.0-be.27771

> **BE 预发布版本 / Bleeding-edge pre-release**
>
> **最低要求：Mindustry BE 27771。**
>
> 本版本未经过详细测试，可能存在未知 bug。安装前请备份重要地图和逻辑程序，不建议在正式服务器或重要存档中直接替换稳定版本。

## 中文

这是专门面向 Mindustry BE 的体验版本，不适用于普通稳定版 Mindustry。请使用 **Mindustry BE 27771 或更高版本**。

**本版本可以体验：**

- 双端队列：`deque` 声明卡，表达式里用 `dpushf` / `dpushb` / `dpopf` / `dpopb` / `dpeekf` / `dpeekb` / `dsize` / `dclear`。
- 无序集合：`uset` 声明卡（不能叫 `set`，那是原版指令），`uadd` / `uhas` / `udel` / `usize` / `uclear`。首次使用前先调用一次 `uclear`。
- 数组算法：对已声明数组/矩阵使用 `reverse`、`replace`、`swap`、`bsearch`（升序二分查找）。
- 调色板分类：For / While / Switch / If / 函数在 Advanced Flow Control；声明卡在 Data Structures；数组/矩阵在 Array Algorithms。原版 jump / end 仍在 Flow Control。
- 撤销与重做：电脑 Ctrl+Z / Ctrl+Y，手机在编辑器底部两个按钮。
- 编辑器底部显示当前编译后的指令条数对照上限；超限时关闭编辑器会被拦住，避免关了才发现。
- 所有保存的代码仍然是纯原版 mlog，联机时与原版客户端完全兼容。

**请注意：**

- 这是 BE 预发布版本，不是稳定版。
- **最低 Mindustry BE 版本：27771。** 更低版本和普通稳定版不受支持。
- 本版本**未经过详细测试，可能存在未知 bug**，也可能出现显示或兼容性问题。
- 使用前请备份重要地图和逻辑程序；遇到问题时，请提供 Mindustry BE 构建号和复现步骤。

## English

This is a **Bleeding Edge pre-release** made for Mindustry BE. It is not intended for the regular stable release of Mindustry. Please use **Mindustry BE 27771 or newer**.

**What you can try:**

- Deques: the `deque` card, with `dpushf` / `dpushb` / `dpopf` / `dpopb` / `dpeekf` / `dpeekb` / `dsize` / `dclear` in expressions.
- Unordered sets: the `uset` card (not `set` — that is a vanilla opcode), with `uadd` / `uhas` / `udel` / `usize` / `uclear`. Call `uclear` once before the first use.
- Array algorithms: `reverse`, `replace`, `swap` and `bsearch` (ascending binary search) on a declared array or matrix.
- Palette categories: For / While / Switch / If / functions under Advanced Flow Control; declaration cards under Data Structures; array / matrix cards under Array Algorithms. Vanilla jump / end stay in Flow Control.
- Undo and redo: Ctrl+Z / Ctrl+Y on desktop, two buttons at the bottom of the editor on mobile.
- A live compiled-instruction count against the processor limit in the editor; closing is blocked when the program is over the cap, so you are not surprised after leaving.
- Everything saved is still plain vanilla mlog, fully compatible with vanilla clients in multiplayer.

**Please note:**

- This is a BE pre-release, not a stable release.
- **Minimum requirement: Mindustry BE 27771.** Older builds and regular stable releases are not supported.
- This version has **not been thoroughly tested and may contain unknown bugs**, including visual or compatibility issues.
- Back up important maps and logic programs before installing. When reporting a problem, include your Mindustry BE build number and reproduction steps.
