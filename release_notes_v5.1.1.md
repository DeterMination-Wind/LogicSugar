# Logic Sugar v5.1.1

> [!IMPORTANT]
> 最低要求 **Mindustry v160.1**（桌面 / Android）。
>
> 保存出去的程序仍然是普通原版 mlog：没装模组的客户端能运行，联机（加入或自建服务器）不受影响。

> [!NOTE]
> v5.1 线的补丁版，无破坏性变更：语法、存档格式与元数据都没动，任何 5.1 存档直接打开即可。
> 安装前建议备份重要地图和逻辑程序；遇到问题时请附上 Mindustry 版本号与复现步骤。

## 中文

**编辑器**

- **一行表达式可以直接粘贴。** `array buf cell1 0 8` + `x = buf[3]` 现在会导入成一张声明卡和一张 Expr 卡，与手动拖出来的完全一致，并降级为 `read x cell1 3`。以前这类赋值行没有任何解析器认领，会被静默换成 `noop`——程序还能跑，但这一行没了（#12）。
- **写错的表达式会拦住保存。** 无法解析的表达式行现在落成一张带报错的红卡，而不是悄悄变成 `noop`。
- **跳转目标失效不再崩溃。** 跳转目标被删除、或在结构刷新途中尚未挂回时，渲染循环会抛 `NullPointerException`。现在保存前会丢弃失效的跳转目标。
- **手机 / 窄窗底栏。** 底栏改为在**所有**设备上按真实宽度布局。手机与竖屏以前沿用原版固定宽度行，而那一行压不到按钮总宽以下；比屏幕宽的行会被 `Element.keepInStage()` 推出可见区，返回键（最左）与打开函数库按钮（最右）因此被裁掉。现在会换成真正放得下的多行；栏宽连指令预算读数（196px）都放不下时，让位的是读数这一格，而不是多占一整行——可按的按钮始终保留。

**兼容**

- **原版 v159 / 聚合构建恢复正常加载。** `LStatement.localizedName()` / `statementKey()` 并非所有核心都有；现在两者都改为反射解析，并回落到 `name()` / Sugar bundle，Neon 聚合构建与更旧的原版 classpath 不再报错。
- **登记为联机支持模组（`hidden`）。** 模组不再出现在联机模组校验与存档元数据里，服务器与原版客户端既不会要求它，也不会报版本不匹配。

## English

**Editor**

- **One-line expressions can be pasted.** `array buf cell1 0 8` + `x = buf[3]` now import as a declaration card and an Expr card, exactly like placing them by hand, and lower to `read x cell1 3`. The assignment used to match no parser and was silently replaced by a `noop`: the program still ran, but the line was gone (#12).
- **Bad expressions block the save.** An expression line that cannot be parsed now lands as a red card with an error, instead of quietly turning into a `noop`.
- **No crash on stale jumps.** A jump whose target had been removed, or was mid-rebuild during a structure refresh, crashed the render loop with a `NullPointerException`. Stale jump targets are now dropped before saving.
- **Phone and narrow-window bottom bar.** The bottom bar is laid out from the actual width on **every** device. Mobile and portrait used to keep vanilla's fixed-width row, which cannot be compressed below the sum of its buttons; a row wider than the screen is pushed out of the viewport by `Element.keepInStage()`, which cut off the back button (leftmost) and the function-library button (rightmost). The bar now wraps onto rows that really fit, and on a bar too narrow for the instruction-budget readout (196px) that one cell yields rather than costing an extra row — pressable controls always stay.

**Compatibility**

- **Vanilla v159 and aggregate builds load again.** `LStatement.localizedName()` / `statementKey()` do not exist on every core; both are now resolved reflectively and fall back to `name()` / the Sugar bundle, so the Neon aggregate build and older vanilla classpaths no longer break.
- **Registered as a multiplayer-support mod** (`hidden`). The mod is left out of the multiplayer mod check and of save metadata, so servers and vanilla clients never demand it or report a version mismatch.