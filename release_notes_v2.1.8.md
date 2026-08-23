# Logic Sugar v2.1.8

## 中文

- 修复语句输入框 / 表达式编辑器无法点击聚焦的问题（BoxSelect 误吞事件，输入框点击不再触发积木候选拖动）。
- 输入框点击后不会再把已选积木组整体拖走，与 v2.1.7 之前的行为一致；仍需从语句其它区域拖动。
- 新增两个设置开关："Ctrl+点击 = 复制积木" 与 "Ctrl+拖动 = 复制积木"，默认开启，可在设置中单独关闭。
- 关闭开关后 Ctrl 退化为普通点击 / 普通移动拖动；中键复制行为不受影响。

## English

- Fixed clicks inside statement text fields / the expression editor being swallowed so they could not take focus (BoxSelect no longer treats them as candidate drags).
- Clicking inside an input field no longer drags a selected group along; drag from other statement areas instead, matching pre-v2.1.7 behavior.
- New settings "Ctrl+Click = Copy Statement" and "Ctrl+Drag = Copy Statements", both on by default and individually toggleable in the settings menu.
- With a switch off, Ctrl falls back to a normal click / normal move-drag; middle-click copying is unaffected.

The universal JAR works on desktop and Android.