# 上游基线（MlogDevTools / 原 MlogAssertions）

LogicSugar 的断言/断点子系统与调试工具（Vars / Memory / Properties 界面、快照、Profiler、处理器日志）对齐
cardillan/MlogDevTools 的 **v0.12.0**（仓库在 v0.12.0 前后从 MlogAssertions 改名为 MlogDevTools，tag 与源
码包的 `cardillan.mlogassertions` 包名未变）。本文记录基线怎么取得、跟了哪些行为、明确不移植什么，
以及四处线格式例外；实现叙述见[架构总览](architecture.md)的「断言子系统」「变量/内存/属性界面与快照」
「处理器日志」「Profiler」，测试入口见[测试指南](testing.md)。

## 参考源码与同步方式

- 基线 v0.12.0：参考源码在工作区 `../_upstream/MlogDevTools-0.12.0`，由
  `gh api repos/cardillan/MlogDevTools/tarball/refs/tags/v0.12.0` 取得。
- 历史快照 `../_upstream/MlogAssertions-0.11.8`、`-0.11.7`、`-0.11.6`、`-0.11.3` 与
  `../_upstream/MlogAssertions-pr`（v0.8.2）只当历史参考，不作为当前行为依据。
- 不要 `git fetch upstream`：本仓库不保留上游 remote；同步以上述快照 + GitHub tag/tarball 为准。
- UI 落点：数据层 `logicsugar.vars`、对话框/入口 `logicsugar.vars.ui`、Profiler `logicsugar.profile`
  与 `logicsugar.profile.ui`。这些只在客户端内存工作，不改变保存产物，因此不受“多人必须兼容原版”的
  单机门禁约束。

## 跟进历史

### v0.11.3 → v0.11.6

- **内存表导入上限**：`logicsugar.vars.MemoryText` 按内存块 `writeUTF` 的口径拒绝超过 65535 **UTF-8 字节**
  的字符串；导入仍是两阶段 `validate → read`，超长按坏行报行号且不写入，部分失败不会写坏内存。
- **三击不打开本客户端不可访问的方块**：三击入口与 `InstrumentationEngine.getInstrumentation` 都先判
  `Building.displayable()`（即 `LogicBuild.accessible()`）；世界处理器、禁止编辑地图上的处理器不弹窗、不统计。
- **失败断言 / `error` 的停机语义**：非断点分支在回退 `counter` + `yield` 之外写 `exec.stop`，消息仍由
  `ProcessorStatus` 绘制在处理器上方。
- **Profiler 界面重做与快路径**：`noYielding` 名单 + `DevToolsInstruction.yields()`；12 个调试指令的
  `yields()` 取值与上游逐字一致；`Instrumentation.isCovered` 合并 `steps` 与 `covered` 两个来源。
- **五个视图开关持久化**：hex / 排序 / 隐藏临时变量 / 隐藏链接 / 全精度。键名用本 mod 自己的
  `logicsugar.vars*`，不跟上游的 `mlogdevtools-*`。

### v0.11.6 → v0.11.7

上游只有一个提交「Fix possible crash」，三处守卫全部跟：

1. `Instrumentation.createSnapshot`：`Snapshots.create` 返回 `null`（快照上限被设成 0）时既不登记也不入列。
2. `InstrumentationEngine.getInstrumentation`：对 `!build.displayable()`（本客户端不可访问的世界处理器）
   返回 `null`。
3. `SnapshotI`：`master == null` 时不写 recording（本仓库从 v0.11.3 移植起已带着，`assertTest` 钉住）。

### v0.11.7 → v0.11.8

上游只有一个修复提交：`SnapshotManager.liveView` 对「从未载入过代码的处理器」加
`executor.counter != null` 守卫（三击刚创建的空处理器不再崩溃）。

- 没载入过代码的处理器 `executor.counter` 是 `null`——只有 `LExecutor.load` 才会创建它并赋 `@counter`——
  而 `ProcessorVars` 的构造期 `numvar(...)` 会立刻求值 `timeWaited()` → `executor.counter.numval`，
  所以旧写法对这类方块必 NPE。加守卫后这类方块在 `liveView` 里退化为 `SensorVars`（属性视图）。
- 本仓库同根的第二条可达路径是处理器配置面板的「快照」按钮（`BlockConfigAccess` 通往同一套快照
  创建代码），同一守卫一起挡住。修完后对空处理器建快照是**静默不产**而不是崩溃：
  `ProcessorSnapshot.create` 的 `executor.vars.length == 0` 守卫让创建返回 `null`。
- 真实崩溃路径需要一块活的 `LogicBuild`（`LogicBlock` 构造会读 `Vars.content`），无头环境构造不出来，
  因此自动化侧是 `VarsDataTest` 的源码钉子 + 变异验证（与 v0.11.7 那次的约定相同）。

### v0.11.8 → v0.12.0

上游这一版只有一个功能提交（`a32fa15e`，发布提交为 `8bfa33b`）：

1. **`log` 重命名为 `systemlog`**（破坏性）。旧的 `log` 写游戏日志文件，新 `log` 把消息存进处理器；
   本仓库跟这个重命名，并额外保留旧 `log` 文本的读取（见「线格式例外」第 4 条）。
2. **处理器自带日志**：`LogMessages`（每处理器一份定长队列、上限、「重启清空」）与 `LogDialog`
   （级别/分类过滤、帧号、复制/导出/清空）。设置项落到本 mod 的 `logicsugar.logMessageLimit` /
   `logicsugar.logClearOnRestart`，默认 100 条 / 开启清空，注册在 `addVarsPrefs`（自有设置页与 Neon
   聚合页共用）。`log` 卡片新增分类槽，运行时用分类变量做前缀。
3. **入口调整**：`VarsDialog` 标题栏与 Edit 菜单各加一个日志按钮；处理器配置面板的「快速快照」按钮
   换成日志按钮（快照仍在 Vars 界面里）；`VarsDialog` 的帮助页加一行日志说明，并修了单图标帮助行
   的居中（colspan 单元格里套一层子表）。
4. **同批不显眼的两处修复**：`SensorVars` 把四个 camera 访问从 Properties 列表里剔除（v160 的
   `LAccess.privilegedAccess`，对实体读数恒为 0）；`Snapshots.SnapshotRecord.add` 先按当前上限裁剪再
   入队（上限调小、`updateLimit()` 还没跑时不再多留一条）。拆方块时的清理改成「任意建筑」
   （connected/global 快照可能收进非处理器建筑）。

两处有意偏离：`LogI.vars()` 把分类槽算进指令变量（上游漏了，recording 快照会漏值）；旧 `log` 文本
按第 3 槽写法分流成 `systemlog`（上游直接按新格式读，会串用 `LParser` 复用数组里的陈旧 token）。


## 明确不移植

- **`max-instructions`（指令预算覆盖）**：处理器保存产物恒 ≤1000 条是硬不变式，见根 `AGENTS.md`。
- **v0.11.5「自动 profile 全部处理器」游戏规则**（`data/CustomGameRules` + `Accessor` + `MapIndex`）：
  需要反射挂 4 个 `CustomRulesDialog`（MindustryX 路径不同）并把 `rules.tags` 写进地图状态，收益小、兼容风险大。
- **`ConfigEvent` 的精细判断**（`instructions != instrumentation.instructions` 才重建）：那是为自动 profile
  服务的微优化；本 mod 保持“配置变更就丢弃重建”的简单口径。
- **身份类改动**：设置键前缀 `mlogdevtools-*`、mod 改名、Gradle Groovy → KTS、`README_zh` 均不跟。
  持久化键用 `logicsugar.vars*`，设置分类仍是本 mod 自己的 `logicsugar.*`。

## 与 MindustryX 共存

方块配置面板必须与 MindustryX 共存：探测到 fork 自带 `LogicSupport` 时，先调用方块自己的
`buildConfiguration`，再追加本 mod 的按钮——是 append，不是 replace。

## 线格式例外（改前先读）

四处与上游不完全一致。改动前先看 `SugarAsserts` 里对应卡片的注释与 `assertTypeTest` / `assertTest`：

1. **`asserttype` 的 `null` 类型**：LogicSugar 扩展（上游 `AssertionDataType` 不识别 `none`/`null`）。
2. **`asserttype` token 顺序**：上游 v0.10 起为 `<type> <value>`；旧序仍可读，保存统一写新序。
3. **`snapshot` 5 槽文本**：上游 v0.11.2 起为 `type block steps message`；旧 3 载荷 token 文本按第 4 槽写法
   分流仍可读，清空的 `steps` 保存时归一为默认 `20`。
4. **`log` / `systemlog` 的旧文本迁移**：上游 v0.12.0 把旧 `log`（写游戏日志文件）硬改名为 `systemlog`，
   旧 `log` 文本按新格式读会串用 `LParser` 复用数组的陈旧 token。本仓库的 `parseLog` 用第 3 槽的**写法**
   分流：`~` / 空 / 引号开头 = 旧式消息模板（读成 `SystemLogCard`，重写输出 `systemlog`）；其余
   （`null`、裸变量名、数字）按新 `log` 的 `category` 读。因此手写「带引号的分类」会被读成 `systemlog`
   （唯一二义情形）；`LogCard.write` 把清空的分类归一为 `null`，保证新文本永远不会落进旧式分支。
   存量 `emit` 产物里的旧 `log` 指令无法通过反编译 verify 门（候选会重编译成 `systemlog`），
   打开时退回原版 mlog；载体里的 sugar 不受影响。

除此之外，断言指令族、`profile` / `restart` 卡、`recording` 快照类型以及停机/断点语义均按 v0.12.0 对齐。
