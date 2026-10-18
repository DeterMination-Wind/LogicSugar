# logicsugar.vars / profile — 调试 UI、快照与 Profiler

作用域：`src/logicsugar/vars/`（数据层）、`src/logicsugar/vars/ui/`（对话框与入口）、
`src/logicsugar/profile/` 与 `profile/ui/`（Profiler）。改这里前先读本文件；实现细节见
[架构总览](../../../docs/architecture.md)「变量/内存/属性界面与快照」「Profiler」，上游语义见
[上游基线](../../../docs/upstream.md)。

## 保存产物与联机口径

- 这些界面与统计只存在于客户端内存，**不改变保存产物**，因此不受“多人必须兼容原版”的单机门禁限制；
  profiler 是本地只读观测，联机可用，每个客户端各算各的。
- 但调试副作用仍受门禁：`breakpoint` 在 `Vars.net.active()` 下拒绝暂停；`profile` / `restart` /
  `snapshot recording` 只在 `emit` 调试构建里落进程序文本，联机强制 `strip`。不要为了 UI 方便把这些行写进
  普通保存路径。
- 快照不随地图保存，关图即丢；`Vars` / Memory / Properties 只是同一套 `VariableValues` 渲染。

## 持久化与双形态

- 视图偏好五个键固定为 `logicsugar.varsHex` / `logicsugar.varsSorted` / `logicsugar.varsHideTemps` /
  `logicsugar.varsHideLinks` / `logicsugar.varsFullPrecision`（`VarsOptions` 的常量、`load()` / `save()`、
  `VarsDialog.refreshView` / `updateView`、`VarsAccess.applySettings` 必须对齐）。不要用上游的
  `mlogdevtools-*` 前缀。
- 设置行必须在 `LogicSugarSettings.setup(...)` 与 Neon 的 `bekBuildSettings(...)` 两处都可达；vars/snapshot
  行走 `LogicSugarSettings.addVarsPrefs`，`logicsugar.startProfilerImmediately` 也在其中，别只在一边注册。
- `Core.settings == null` 的无头环境要静默跳过持久化，不能崩。

## 快照 / profiler 的已知守卫

- 三击入口与 `InstrumentationEngine.getInstrumentation` 都必须先判 `Building.displayable()`
  （= `LogicBuild.accessible()`）：世界处理器、禁止编辑地图上的处理器不弹窗、不统计。
- `Snapshots.liveView` 必须先判 `executor.counter != null` 才能构造 `ProcessorVars`（上游 v0.11.8）：
  没载入过代码的处理器没有 `@counter`，而 `ProcessorVars` 构造期 `numvar(...)` 会立刻读
  `executor.counter.numval`，旧写法必 NPE（三击刚放下的空处理器、配置面板快照按钮都会崩）。
  这类方块退化为 `SensorVars`（属性视图）；分派顺序 `MemoryVars → ProcessorVars → SensorVars`
  不能动（顺序变了视图类型就变），`varsTest` 同时钉住守卫与顺序。
- `Snapshots.create` 在额度为 0 时返回 `null`；`Instrumentation.createSnapshot` 必须先判空，再
  `recording().add` / `Snapshots.register`，否则录制中把上限改成 0 会 NPE。`SnapshotI` 在 `master == null`
  时不写 recording。
- recording 的额度按“队列条目 + 子快照”计：子快照创建时 `register()` 递增，离场时按 `recording().size`
  整批扣回。改计账口径要同时改 `varsTest`。
- 内存文本导入按 `writeUTF` 口径限制 65535 UTF-8 字节，两阶段 `validate → read`；超长按坏行报行号且不
  写入，部分失败不能写坏内存（`MemoryText`）。
- profiler 读 `executor.instructions` 的每个点都必须先 `InstrumentationEngine.unwrap(...)`（包装器是另一个
  类）；`Instrumentation.isCovered` 合并 `steps` 与 `covered` 两个来源。改包装/记录口径先看 `profilerTest`。

## 入口与编辑

- 三个入口：编辑器「变量」按钮（`SugarLogicDialog.installVarsButton`）、三击方块（`VarsAccess` +
  `TripleTap`）、方块配置面板（`BlockConfigAccess`）。改入口先看 `varsAccessTest` 的纯状态机与源码钉子。
- UI 代码可能被 Neon 的旧 arc 编译：Label 用 `setWrap(...)`，不用旧版没有的 `Cell.wrap(boolean)`
  （`varsUiTest` 源码钉子）。
- 主要回归：`varsTest`、`varsUiTest`、`varsAccessTest`、`profilerTest`；需要活场景的对话框行为走
  [测试指南](../../../docs/testing.md) 的手测清单。
- 上游版本相关的判空/停机语义（v0.11.6 的 65535 字节上限与 `displayable()`、v0.11.7 的三处守卫、
  v0.11.8 的 `liveView` 空处理器守卫）见
  [上游基线](../../../docs/upstream.md)。
