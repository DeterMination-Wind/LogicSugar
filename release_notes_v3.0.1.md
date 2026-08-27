# Logic Sugar v3.0.1

## 中文

本次版本修复了 Logic 编辑器中代码块首次拖动偶发无效的问题，并改善了移动端与桌面端的拖动手感。

**修复**

- capture 监听器改为在初始化时同步安装，避免第一次触摸在监听器就绪前被 vanilla 代码块拖动逻辑抢先处理。
- 普通单块拖动使用固定 8px 移动阈值；移动端仍需长按约 0.43 秒，桌面端无需强制长按。
- 首次跨过拖动阈值时立即更新代码块预览，避免第一次拖动看起来没有响应。
- 增加多指触摸归属和取消处理，避免异常触摸状态影响后续拖动。
- 在画布加载或重建时清理旧选中状态，避免代码块重建后继续引用失效对象。
- 增加拖拽策略回归自测，并继续保留按钮点击、输入框聚焦和框选行为。

## English

This release fixes an intermittent first-drag failure in the Logic editor and improves block-drag behavior across desktop and mobile.

**Fixes**

- Install the capture listener synchronously during initialization so the first touch cannot reach vanilla block dragging before the custom handler is ready.
- Use a fixed 8 px movement threshold for ordinary single-block dragging; mobile still requires about 0.43 seconds of long press, while desktop does not require a forced long press.
- Update the block preview immediately on the first event that crosses the drag threshold, avoiding an apparently unresponsive first drag.
- Track the active touch pointer and handle cancelled touches so stray multi-touch input cannot contaminate later drags.
- Clear stale selection state when the canvas is loaded or rebuilt, preventing references to replaced statement elements.
- Add drag-policy regression coverage while preserving button clicks, input focus, and box selection behavior.
