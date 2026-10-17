# 上游基线（MlogAssertions）

LogicSugar 的断言/断点子系统与调试工具（Vars / Memory / Properties 界面、快照、Profiler）对齐
cardillan/MlogAssertions 的 **v0.11.7**。本文记录基线怎么取得、跟了哪些行为、明确不移植什么，以及三处
线格式例外；实现叙述见[架构总览](architecture.md)的「断言子系统」「变量/内存/属性界面与快照」「Profiler」，
测试入口见[测试指南](testing.md)。

## 参考源码与同步方式

- 基线 v0.11.7：参考源码在工作区 `../_upstream/MlogAssertions-0.11.7`，由
  `gh api repos/cardillan/MlogAssertions/tarball/refs/tags/v0.11.7` 取得。
- 旧快照 `../_upstream/MlogAssertions-0.11.6`、`../_upstream/MlogAssertions-0.11.3` 与
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

三处与上游不完全一致。改动前先看 `SugarAsserts.AssertTypeCard` 的注释与 `assertTypeTest`：

1. **`asserttype` 的 `null` 类型**：LogicSugar 扩展（上游 `AssertionDataType` 不识别 `none`/`null`）。
2. **`asserttype` token 顺序**：上游 v0.10 起为 `<type> <value>`；旧序仍可读，保存统一写新序。
3. **`snapshot` 5 槽文本**：上游 v0.11.2 起为 `type block steps message`；旧 3 载荷 token 文本按第 4 槽写法
   分流仍可读，清空的 `steps` 保存时归一为默认 `20`。

除此之外，断言指令族、`profile` / `restart` 卡、`recording` 快照类型以及停机/断点语义均按 v0.11.7 对齐。
