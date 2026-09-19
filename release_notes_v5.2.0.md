> [!IMPORTANT]
> 最低要求 **Mindustry v160.1**（桌面 / Android）。保存出去的程序仍是普通原版 mlog：没装模组的客户端能运行，联机（加入或自建服务器）不受影响。
>
> Requires **Mindustry v160.1** (desktop / Android). Saved programs stay plain vanilla mlog: they run on unmodded clients and multiplayer is unaffected.

> [!NOTE]
> v5.2 功能版：68 个数据结构运算改名成 C++ STL 风格（旧短名继续可解析），并修好表达式卡（Expr）的三个缺陷——加号菜单插入后积木消失、保存一次后掉回普通积木、显示多一个 `]`。改名不改变降级产物，旧存档照常打开、程序照常运行。
>
> v5.2 feature release: all 68 data-structure operations were renamed to C++ STL style (old short names still parse), plus three Expr card fixes - the card vanishing after a palette insert, degrading to a plain block after one save, and one bracket too many in the display. The rename does not change lowered output, so old saves keep working.

## 中文

* 数据结构运算改名：68 个运算统一改成 C++ STL 风格，例如 `spush` → `stack_push`、`lappend` → `vector_push_back`、`sum` → `array_sum`、`indexof` → `array_find`、`bsearch` → `array_lower_bound`、`mapget` → `map_get`、`uhas` → `set_contains`、`cnew` → `chain_alloc`、`bset` → `bitset_set`；积木菜单、卡片正文、悬停提示与表达式函数统一使用新名。
* 旧短名仍然可解析：旧存档打开照常工作，载体里的旧名在打开时归一到新名，重新保存只写新名。
* 改名不改变产物：新旧名字生成的原版指令流逐字相同，载体校验与联机兼容性都不受影响。
* `bitset_set` / `bitset_reset` / `chain_set_head` 的结果恒为 1，改为无结果卡，目标可以留空写 `~`。
* 修复：加号菜单里点 `Expr` 没反应——默认卡 `result = 0` 只编译出一条 `set` 指令，展开时被当成未知行丢弃、卡片本身又已被移除；现在无法映射的行一律保留卡片，单行表达式卡也不再展开。
* 修复：表达式卡保存一次就掉成普通积木——单行表达式（`x = 0`、`x = a + b`、`x = cos(a)`）现在随载体多带一行注释标记，重开与撤销重做都会还原成表达式卡（注释不改变指令流、语句条数与跳转下标）。
* 修复：表达式卡显示多一个 `]`——`result = list[1]` 曾显示成 `result = list[1]]`；富文本里只有 `[` 需要转义，错误提示行同样修正。
* **破坏性变更：** 13 个可失败操作（push / append / insert / set / erase / remove / free）失败时返回 `-1`，此前提示与教程写的是满时保持原长度、越界写入 0、不存在返回 0，照旧写的分支判断需要改成 `== -1`；查询类（`map_contains` / `set_contains` / `bitset_test`）保持 0/1。
* 兼容：更早版本保存过、且用过 `array_sort` / `array_sort_desc`（v5.0.0 起改为希尔排序）或 `array_find` / `array_copy`（v5.1.1 起的修复）的处理器，重开时会回落到原版视图；可执行 mlog 不变，重新拖一次对应卡片即可恢复。

## English

* Operations renamed: all 68 data-structure operations now use C++ STL style names, for example `spush` → `stack_push`, `lappend` → `vector_push_back`, `sum` → `array_sum`, `indexof` → `array_find`, `bsearch` → `array_lower_bound`, `mapget` → `map_get`, `uhas` → `set_contains`, `cnew` → `chain_alloc`, `bset` → `bitset_set`; the add-block menus, card bodies, tooltips and expression functions all use the new names.
* Old short names still parse: existing saves keep working, a legacy name in the carrier is canonicalized when the card is opened, and a re-save writes the new name only.
* The rename does not change the product: old and new spellings lower to byte-identical vanilla mlog, so carrier verification and multiplayer compatibility are unaffected.
* `bitset_set` / `bitset_reset` / `chain_set_head` always return 1, so they became result-less cards and the destination may be left empty (`~`).
* Fixed: clicking `Expr` in the add-block dialog did nothing - the default card `result = 0` compiles to a single `set` line, which the unfold pass dropped as an unknown line while the card itself had already been removed. Unmappable lines now always keep the card, and single-line cards are no longer unfolded.
* Fixed: an Expr card degraded to a plain block after one save - single-line expressions (`x = 0`, `x = a + b`, `x = cos(a)`) now carry one comment marker in the carrier, so reopening and undo restore the expression card (the comment leaves the instruction stream, the statement count and every jump index untouched).
* Fixed: one bracket too many in the display - `result = list[1]` rendered as `result = list[1]]`; only `[` needs rich-text escaping, and the card error row follows the same rule.
* **Breaking:** the 13 fallible operations (push / append / insert / set / erase / remove / free) report `-1` on failure, while the tooltips and tutorials described the pre-v5 values (a full stack keeps its length, out of range writes 0, 0 when missing); branches written against the old wording must compare `== -1`. The query operations (`map_contains` / `set_contains` / `bitset_test`) stay 0/1.
* Compatibility: processors saved by older builds that used `array_sort` / `array_sort_desc` (Shell sort since v5.0.0) or `array_find` / `array_copy` (fixes in v5.1.1) reopen in the vanilla view; the executable mlog is unchanged and re-placing the card restores the structured view.
