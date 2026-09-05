# Logic Sugar v3.1.0

## 中文

本次版本重构了逻辑卡片（语句块）的布局策略：放弃按卡片宽度每帧动态判断换行，改为确定性布局，并修复了深缩进与折叠状态下的若干显示问题。

**改动**

- 语句块确定性换行：While / If / ElseIf / Switch / FuncDef / FuncCall / Return 回归确定性单行布局，删除按总宽度每帧动态重建（responsiveRows），消除布局频繁重建与视觉跳动。
- For 固定两行：宽屏合并为一行；窄屏（useRows）只在步长后固定换一次行。前缀字段（变量/初值/步长）与条件拆成独立子表，条件控件的 growX 不再把前缀列撑出空白。
- 下划线配色修复：条件编辑器与 For 前缀字段每帧跟随语句卡片颜色（正常蓝色/无效红色），修复输入框下划线变白。
- 深缩进可见性：缩进上限改按卡片宽度推导（minContentWidth 260→360），条件行与行尾模式/折叠按钮在深嵌套下不再被顶出可视区。
- 折叠空间补偿改为按元素实例：新增 syncFoldHiddenSpace 统一切换入口，避免多画布互相污染；折叠块内部不再撑出空隙。
- 字段宽度收窄：For 前置区输入框 85→65、条件行输入框 85→75、组间距 10→6，窄屏下条件行不超出卡片。

## English

This release reworks the layout strategy of logic statement cards: dynamic per-frame width-based wrapping is replaced with deterministic layouts, and several display issues under deep indentation and folded states are fixed.

**Changes**

- Deterministic statement layouts: While / If / ElseIf / Switch / FuncDef / FuncCall / Return return to deterministic single-row layouts; the per-frame width-probing rebuild (responsiveRows) is removed, eliminating frequent layout rebuilds and visual jitter.
- Fixed two-row For form: merged into one row on wide canvases; on narrow canvases (useRows) it breaks exactly once after the step field. The prefix fields (variable / initial / step) and the condition are split into independent sub-tables, so the condition controls' growX no longer inflates the prefix columns.
- Underline color fix: the condition editor and the For prefix fields now follow the statement card color every frame (normal blue / invalid red), fixing white text-field underlines.
- Deep-indent visibility: the maximum inset is now derived from the card width (minContentWidth 260→360), keeping the condition row and the trailing mode/fold controls visible under deep nesting.
- Per-element fold-space compensation: fold-height compensation moved from a static field to per-element state behind a new syncFoldHiddenSpace entry point, preventing multiple canvases from overwriting each other's layout state; folded blocks no longer leave gaps inside.
- Narrower fields: For prefix inputs 85→65, condition-row inputs 85→75, group spacing 10→6, so condition rows fit inside the card on narrow screens.
