# Logic Sugar v5.3.1

> [!IMPORTANT]
> 最低要求 **Mindustry v160.1**（桌面 / Android）。
>
> 保存出去的程序仍然是普通原版 mlog：没装模组的客户端能运行，联机（加入或自建服务器）不受影响。

> [!NOTE]
> v5.3.1 是修复版：底栏按 UI 缩放换算宽度后重新分行，手机上七个操作按钮不再溢出屏幕、不再把「返回 / 添加」推到屏外。

## 中文

**修复：手机底栏按钮溢出屏幕（首尾按钮被推出可见区）**

- **现象**：手机上底栏七个按钮被塞进同一行，整行比屏幕还宽；居中之后左右溢出，最左的「返回」与最右的「添加」整颗看不见，只剩中间三格（内置变量 / 打开函数库 / 撤销）可点。预算标签另起一行，说明确实走了「按行打包」，只是每行能放几格算错了。
- **根因：单位混用。** arc 会把每个 `Cell` 的 `size` / `pad` / `margin` 乘上 UI 缩放 `Scl.scl()`，所以源码里声明的 `160` 在 2.5 倍缩放的手机上真实占 400 场景单位；而 `buttons.getWidth()` / `getPrefWidth()` 量到的本来就是场景单位。行打包把**未缩放**的 160 / 196 拿去跟真实的 1260 场景单位比较，于是 7×160=1120「放得下」1220 的行空间，实际那一行宽 7×400=2800，居中后左右各溢出 770px。
- **修复**：打包前统一换算（`BottomBarLayout.scaledWidths(Scl.scl(1f), widths)`）；单行判定阈值、行内边距（`barRowPad` / `12f`）与预算标签的适配判定同样按缩放后的宽度计算，两边不再对调。
- **桌面**：缩放为 1 时声明单位与场景单位相等，桌面行为与 v5.3.0 完全一致；但把桌面 UI 缩放调到 100% 以上同样会踩到这个 bug，现在一并修好。
- **结果**：截图上那台 1260px / 2.5 倍手机上，底栏按 **3/3/2** 分行——返回·编辑·内置变量 / 函数库·撤销·重做 / 添加 + 指令预算标签——所有按钮都在屏内可点。

**回归测试**

- `bottomBarLayoutTest` 新增四组用例：缩放 1 与声明宽度等价（打包结果不变）；1 / 1.5 / 2 / 2.5 / 3 倍 × 360–1260px 每个宽度下每一行都放得下且不丢单元；1260px 手机栏（2.5 倍）必须打成 3/3/2；并保留「未缩放时七格挤进同一行、真实 2800 场景单位 > 1220 行空间」这一钉子。
- 另加真实 arc 布局几何：2.5 倍下声明 `size(160, 64)` 的单元格量到 400 场景单位、三格量到 1200，钉住「打包用的必须是这份宽度，而不是声明数字」。
- 把 `scaledWidths` 改回恒等，该用例立刻失败（`got [7, 1]`），证明钉子有效；`./gradlew check` 40 个自测任务全绿。
- `docs/architecture.md` 底栏章节新增「宽度必须只用一种单位」的说明，`docs/testing.md` 同步测试清单。

## English

**Fixed: the phone bottom bar overflowed the screen and hid its end buttons**

- **Symptom**: on a phone the seven bottom-bar buttons were packed into one row wider than the screen. Centred, it overflowed both sides, so the leftmost "back" and the rightmost "add" buttons were completely off screen, leaving only the middle three (variables / function library / undo) reachable. The budget label kept a row of its own, which shows the bar did take the wrapping path - it just counted the cells per row in the wrong units.
- **Cause: mixed units.** arc multiplies every `Cell` size / pad / margin by the UI scale `Scl.scl()`, so a declared `160` really occupies 400 scene units on a 2.5x phone, while `buttons.getWidth()` and `getPrefWidth()` already report scene units. The packer compared the **unscaled** 160 / 196 against the real 1260-unit bar, so seven cells looked like 1120 units fitting a 1220-unit row; that row really measured 7x400 = 2800 units and overflowed by 770px on each side.
- **Fix**: widths are converted before packing (`BottomBarLayout.scaledWidths(Scl.scl(1f), widths)`), and the single-row threshold, the row pads (`barRowPad` / `12f`) and the budget-label fit check use scaled widths as well, so the two units can no longer be swapped.
- **Desktop**: at a scale of 1 the declared and scene units are equal, so desktop behaves exactly as in v5.3.0; desktop windows with a UI scale above 100% hit the same bug and are fixed by the same change.
- **Result**: on the reported 1260px / 2.5x phone the bar packs **3/3/2** - back / edit / variables, function library / undo / redo, add + instruction-budget label - with every button on screen and pressable.

**Regression coverage**

- `bottomBarLayoutTest` gained four groups of cases: scale 1 is equivalent to the declared widths (same packing); scales 1 / 1.5 / 2 / 2.5 / 3 x widths 360-1260px keep every row inside the bar without dropping a cell; the 1260px / 2.5x phone must pack 3/3/2; and the unscaled widths are still pinned as the ones that squeeze all seven actions into a single row that really spans 2800 scene units against a 1220-unit row space.
- Added real arc layout geometry: at 2.5x a declared `size(160, 64)` cell measures 400 scene units and three of them measure 1200, nailing down that the packer must use those widths and not the declared numbers.
- Reverting `scaledWidths` to identity fails that case immediately (`got [7, 1]`), so the nail is load-bearing; `./gradlew check` passes all 40 self-test tasks.
- `docs/architecture.md` records the single-unit rule in the bottom-bar section and `docs/testing.md` carries the new cases.
