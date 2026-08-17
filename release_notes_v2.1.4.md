# 流程控制升级：if/elif/else 与 continue — v2.1.4

## 中文

本版本为逻辑编辑器带来结构化分支与更顺手的循环控制：

- **新增 if / elif / else 多条件分支**：编辑器内可直接搭出整条分支链，编译为高效的 jump 指令序列；空分支、`always` 条件、条件取反（含 `strictEqual` 回退 `notEqual` 的近似语义）均已处理并有测试钉住。
- **新增 continue 关键字**：for 与 while 循环内均可使用，跳转目标正确（for 先步进再回查，while 直接重估条件），循环外使用会得到清晰报错。
- **for 循环变量生命周期修复**：跳出循环后再次执行时，循环变量现在会正确重新初始化（旧实现的 `for_init` 哨兵机制会导致二次进入时跳过初始化）。
- **while / if 条件统一为三段式（值 + 操作符 + 比较）**：与 vanilla jump 完全一致，编辑器复用 `JumpStatement` 的控件（`always` 时自动隐藏比较框），并保持旧单值条件文本的向后兼容。
- **文本/方块互转无损化**：`toggleComment` 改为前缀码转义（`~`→`~~`、空格→`~_`），含下划线的变量名（`my_var`、编译临时变量等）与引号参数往返不再被破坏。
- **if 链合法性校验进入编译路径**：`else` 后跟 `elif`、重复 `else` 不仅在画布标红，文本/库来源程序编译时也会被拒绝（`SugarFunctions.ifChainViolations` 三处共用）。
- **解析健壮性**：缺失/畸形的语句目标索引改为清晰报错；移除 if/elif 引入时遗留的死 legacy 解析分支。
- **构建门禁修复**：自测套件计数断言更新，`LogicSugar compiler self-test` 全量通过（38 个用例）。

完整变更见 PR #2；合并后 main 亦保留函数库自愈（R1–R7）回归保护。

## English

This release brings structured branching and smoother loop control to the logic editor:

- **New if / elif / else chains**: build a full branch chain visually; compiles to an optimized jump sequence. Empty branches, `always` conditions and negations (strictEqual falls back to notEqual approximation) are handled and pinned by tests.
- **New `continue` keyword**: supported inside for and while loops, jumps to the correct target (for steps first, while re-evaluates the condition); using it outside a loop gives a clear error.
- **For-loop variable lifecycle fix**: the loop variable is now correctly re-initialized when a loop is re-entered after being broken out of (the old `for_init` guard skipped initialization on re-entry).
- **while / if conditions unified to three-part form (value + op + compare)**: identical to vanilla jumps; the editor reuses `JumpStatement` widgets (compare input auto-hides for `always`) and stays backward compatible with legacy single-value condition text.
- **Lossless text/block conversion**: `toggleComment` now uses a prefix-code escape (`~`→`~~`, space→`~_`), so identifiers containing underscores (`my_var`, compiler temps) and quoted arguments survive the round trip.
- **if-chain validation in the compile path**: `else` followed by `elif` or a duplicate `else` is now rejected not only in the canvas but also when compiling text/library code (`SugarFunctions.ifChainViolations` shared across three call sites).
- **Parser robustness**: missing/malformed destination indices now produce clear errors; dead legacy parsing branches left over from the if/elif introduction were removed.
- **Build gate fixed**: the self-test count assertion was updated; `LogicSugar compiler self-test` passes fully (38 cases).

See PR #2 for the full change; main also keeps the library self-healing (R1–R7) regression protection after the merge.