# Logic Sugar v5.2.0

> [!IMPORTANT]
> 最低要求 **Mindustry v160.1**（桌面 / Android）。
>
> 保存出去的程序仍然是普通原版 mlog：没装模组的客户端能运行，联机（加入或自建服务器）不受影响。

> [!NOTE]
> v5.2 线的功能版：把 **68 个数据结构运算改名为 C++ STL 风格**（旧短名继续可解析），并修好表达式卡（Expr）"点了没反应 / 保存后掉回普通积木 / 显示多一个 `]`"这几个缺陷。
> 改名不改变降级产物：新旧名字生成的原版指令流逐字相同，旧存档的指令流与结构化视图都不受影响。

## 中文

**改名：数据结构运算改用 STL 风格名字**

- **68 个运算统一改名**，例如 `spush` → `stack_push`、`spop` → `stack_pop`、`speek` → `stack_top`、`lappend` → `vector_push_back`、`lget` → `vector_at`、`lremove` → `vector_erase`、`hpush` → `heap_push`、`sum` → `array_sum`、`avg` → `array_avg`、`indexof` → `array_find`、`bsearch` → `array_lower_bound`、`mapset` → `map_set`、`mapget` → `map_get`、`maphas` → `map_contains`、`uadd` → `set_add`、`uhas` → `set_contains`、`cnew` → `chain_alloc`、`cnext` → `chain_next`、`bset` → `bitset_set`……积木菜单、卡片正文、悬停提示与表达式函数名统一使用新名字。
- **旧短名仍然可解析**：旧存档打开照常工作；载体里的旧名在打开时会被归一化成新名，重新保存只写新名，卡片正文与提示不再残留旧名。
- **改名不改变产物**：新旧名字生成的原版指令流逐字相同（`dataSubsystemTest` 的 `renamedOpsLowerIdentically` 对每个运算都做了逐字节比对），因此旧存档的载体校验与联机兼容性都不受影响。
- **无结果卡**：`bitset_set` / `bitset_reset` / `chain_set_head` 的结果恒为 1、没有信息量，卡片可以留空写 `~`。
- 教程与卡片提示全部改用新名字；旧短名仍写入兼容说明。

**修复：表达式卡（Expr）**

- **加号菜单里点 `Expr` 没反应。** 调色板默认卡是 `result = 0`，它只编译出一条值拷贝指令（`set result 0`）；展开逻辑把这一行当成"未知行"丢弃，而卡片本身已经被移除，于是积木凭空消失。现在无法映射的行一律**保留卡片**而不是删块，单行表达式卡也不再展开（展开与保留写出的文本逐字相同，产物与所有 jump 下标不变）。
- **表达式卡保存一次就掉成普通积木。** 单行表达式（`x = 0`、`x = a + b`、`x = cos(a)`）在保存文本里与普通 `set` / `op` 积木完全一样，折叠逻辑的单行门槛又只对数组读写放行，因此重开或撤销一次就会退化成普通积木。现在这类卡片会随载体多带一行注释标记 `# @ls-expr-card <目标> "<表达式>"`：注释不改变可执行指令流、语句条数与跳转下标，重开与撤销重做时会还原成表达式卡。
- **显示多一个 `]`。** `result = list[1]` 在卡片上显示成 `result = list[1]]`：富文本里只有 `[` 需要转义（Arc 把 `[[` 渲染成一个 `[`），`]` 是普通字符，之前连它一起转义了。错误提示行同样修正。
- **失败值提示与 v5 API 对齐。** 13 个可失败操作（push / append / insert / set / erase / remove / free）失败时返回 `-1`，但卡片提示和教程还写着旧口径（"满时保持原长度"、"越界写入 0"、"不存在返回 0"），照着写会判错分支；现在三语提示、中英教程与文档统一为 `-1`，查询类（`map_contains` / `set_contains` / `bitset_test`）保持 0/1。

**兼容**

- **旧存档（v5.1.x 及更早）**：旧操作名照常解析，指令流与结构化视图都不变；改名本身不产生新的断代。
- **内置函数体断代（沿用 v5.1.1 / v5.1.2 的既有说明）**：`sortasc` / `sortdesc`（v5.0.0 起改为希尔排序）、`indexof` / `copy`（v5.1.1 起的修复）的内置函数体变化会让**更早版本**保存过、且用过它们的处理器在重开时回落到原版视图——可执行 mlog 不变、程序照常运行，重新拖一次对应卡片即可恢复结构化视图。
- 处理器产物恒 ≤1000 条、函数库上限 10000 条语句等既有约束不变。

## English

**Renames: data-structure operations now use STL-style names**

- **All 68 operations were renamed**, e.g. `spush` → `stack_push`, `spop` → `stack_pop`, `speek` → `stack_top`, `lappend` → `vector_push_back`, `lget` → `vector_at`, `lremove` → `vector_erase`, `hpush` → `heap_push`, `sum` → `array_sum`, `avg` → `array_avg`, `indexof` → `array_find`, `bsearch` → `array_lower_bound`, `mapset` → `map_set`, `mapget` → `map_get`, `maphas` → `map_contains`, `uadd` → `set_add`, `uhas` → `set_contains`, `cnew` → `chain_alloc`, `cnext` → `chain_next`, `bset` → `bitset_set`. The add-block menus, card bodies, tooltips and expression functions all use the new names.
- **The old short names still parse.** Existing saves keep working; a legacy name found in the carrier is canonicalized when the card is opened, so card bodies and tooltips never show the old spelling again and a re-save writes the new name only.
- **The rename does not change the product.** Old and new spellings lower to byte-identical vanilla mlog (`dataSubsystemTest`'s `renamedOpsLowerIdentically` compares every operation), so carrier verification and multiplayer compatibility are unaffected.
- **Result-less cards:** `bitset_set` / `bitset_reset` / `chain_set_head` always yield 1 (no information), so their cards may leave the destination empty (`~`).
- Tutorials and tooltips were migrated to the new names, keeping the old spellings only in compatibility notes.

**Fixes: the Expr card**

- **Clicking `Expr` in the add-block dialog did nothing.** The palette default card is `result = 0`, which compiles to a single value-copy line (`set result 0`); the unfold pass treated that line as unknown and dropped it while the card itself had already been removed, so the block vanished. Unmappable lines now always keep the card instead, and single-line cards are no longer unfolded at all (the unfolded and kept forms write identical text, so the product and every jump index are unchanged).
- **An Expr card degraded to a plain block after one save.** A single-line expression (`x = 0`, `x = a + b`, `x = cos(a)`) is indistinguishable from an ordinary `set`/`op` block in the saved text, and the fold rule's single-line case only covers array reads/writes, so reopening or undoing once downgraded it. Such cards now carry one comment marker per card in the carrier, `# @ls-expr-card <dest> "<expr>"`: the comment leaves the executable stream, the statement count and every jump index untouched, and reopening/undo restores the expression card.
- **One bracket too many in the display.** `result = list[1]` rendered as `result = list[1]]`: only `[` needs rich-text escaping (Arc renders `[[` as a literal `[`) while `]` is an ordinary character, but it was escaped too. The card's error row had the same problem.
- **Failure-value wording now matches the v5 API.** The 13 fallible operations (push / append / insert / set / erase / remove / free) report `-1` on failure, while the tooltips and tutorials still described the pre-v5 values ("a full stack keeps its length", "out of range writes 0", "0 when missing") — players following them wrote the wrong branch. Tooltips in all three locales, both tutorial sets and the reference docs now say `-1`; the query operations (`map_contains` / `set_contains` / `bitset_test`) stay 0/1.

**Compatibility**

- **Existing saves (v5.1.x and older):** legacy operation names keep parsing, and neither the instruction stream nor the structured view changes; the rename itself introduces no new break.
- **Builtin body breaks (carried over from v5.1.1 / v5.1.2):** the changed bodies of `sortasc` / `sortdesc` (Shell sort since v5.0.0) and `indexof` / `copy` (fixes in v5.1.1) still make processors saved by **older** builds fall back to the vanilla view when they used those operations — the executable mlog is unchanged and the program keeps running; re-placing the card restores the structured view.
- The existing limits are unchanged: processor products stay ≤1000 instructions and the function library stays at 10000 statements.
