# 架构总览

改 `src/` 下的任何代码前先读本文。它解释 LogicSugar 的组织方式：Sugar 结构如何编译成 mlog、程序如何在保存后还原、以及为什么跨类加载器访问与反编译安全门是两条硬约束。

## 双形态：独立运行 / 并入 Neon 聚合

LogicSugar 是独立模组，同时也是 Neon 聚合模组的子模组之一（id `ls`）。两种形态共用同一套代码，由主类上的静态标记切换：

- `logicsugar.LogicSugarMod#bekBundled`：宿主（Neon）注入时置 `true`。
- **独立运行**：`init()` 在 `ClientLoadEvent` 后调用 `LogicSugarSettings.setup(true)`，注册自己的 `@logicsugar.settings` 设置分类（函数模式、Switch 分派策略、调试断言构建、函数库入口、指令上限、处理器状态、隐藏内部变量、框选、跳转线着色）。
- **Neon 聚合态**：宿主调用 `bekBuildSettings(SettingsTable)` 把设置行挂进 Neon 总设置页；模组自建分类被整体跳过（`if(!bekBundled)`），避免重复条目。注意 `bekBuildSettings` 当前聚合的是函数模式、调试断言构建、函数库入口、指令上限滑杆、处理器状态滑杆、隐藏变量、框选与跳转线着色；`SwitchStrategySetting` 只在独立态的 `build()` 中注册。

除设置入口外，两种形态的行为完全一致；不存在单独的聚合分支代码。

## 入口与生命周期

入口类 `logicsugar.LogicSugarMod`（`mod.json` 的 `main`），初始化流程：

1. **注册语句**：`registerStatements()` 把 13 种 Sugar 语句（`ForBegin` / `WhileBegin` / `SwitchBegin` / `IfBegin` / `Case` / `ElseIf` / `Else` / `Break` / `Continue` / `BlockEnd` / `FuncDef` / `FuncCall` / `Return`）加入 `LogicIO.allStatements`，随后 `SugarStatements.installParsers()` 向 `LAssembler.customParsers` 注册全部 token 解析器（含 `forend` / `whileend` / `switchend` 三个旧开发版本标记的只读兼容）。这是与反编译器、自测共享的唯一注册点。
2. **接管编辑器**：`ClientLoadEvent` 后把 `Vars.ui.logic` 换成 `SugarLogicDialog`（构造函数内使用 `SugarCanvas`）。替换前把旧对话框上除 canvas/buttons 外的子元素（如 MindustryX 的逻辑辅助浮层）按 z 顺序迁移到新对话框。
3. **挂辅助功能**：`BoxSelect.init()`（框选）、`ExprHook.init()`（表达式语句）、`VarDisplayFilter.init()`（隐藏 `__ls_*` 内部变量），并注册关闭对话框时清空跳转线着色缓存。

## 编译器：Sugar → mlog

`mindustry.logic.SugarCompiler` 是唯一编译入口（`compile(...)` 重载链）。核心不变式：**保存到处理器的永远是原版可解析的 mlog**。

- 结构语句（`ifbegin` / `forbegin` / … / `blockend`）被 lowering 成 `jump` / `op` / 标签注释组合；`SugarStatement.build()` 返回 `NoopI`，结构语句本身不产生指令。
- 函数由 `SugarFunctions.analyze` + `lower` 处理：本地函数（处理器内定义）与库函数走同一条管线。`FuncMode.normal` 生成共享 `@counter` 子程序（函数体 hoist 到程序尾部）；`FuncMode.inline` 按调用点展开副本，编译器临时名带 `__ls_i_<callId>_` 前缀。
- `SwitchStrategy` 决定 `switch` 的下降形态：`auto` 在整数 case、值域跨度 ≤255 时按实际可执行指令成本在比较链与 `@counter` 跳转表之间二选一；`chainOnly` 恒用比较链（与 2.3.1 之前输出逐字节一致）。lowering 之后还有无条件跳转链穿线（`threadAlwaysJumpTargets`，带环检测）。
- **持久化载体（carrier）**：Sugar 源码以 `set __ls_sugar "<base64>"` 载体行存回程序末尾，程序用到的库函数子集以 `set __ls_lib "<base64>"` 一并嵌入（跨机器可重编译）。载体是真实 `set` 语句，能挺过原版 parse/save 往返；单条载体不超过 60000 字符（LParser 字符串 token 上限 65535 UTF 字节以下）。**载体分片**：编码后超限的载荷自动切分为连续编号的多条语句 `set __ls_sugar_1/2/…`（`__ls_lib_N` 同理），每片 ≤60000 字符，restore 侧按"从末尾锚定、向前连续递减到 1"重拼后一次 decode（避免劈开 UTF-8 序列）；≤ 阈值时保持单条形状字节不变。分片行计入指令预算，极端超限时重现旧行为（丢弃超限载体并告警）。v2.0.0 旧程序回退到注释标记块 `# @logic-sugar-v1 begin` / `# @logic-sugar-line ` / `# @logic-sugar-v1 end`。
- 编译器保留前缀 `__ls_` 是用户不可用的命名空间；表达式临时变量用 `_0, _1, …` 栈式编号。

## 表达式子系统

`logicsugar.assist.expr` 包（部分思路致谢 mindcode 项目）：

- `ExprCompiler`：表达式字符串 ↔ `op` 语句链的双向转换。临时变量统一 `_0, _1, …`、一次写一次读形成线性链，是逆向重建的前提。
- `ExprStatement`：表达式语句卡片，折叠态显示 `dest = expr`，`write()` 输出 `op` 链文本（保证保存结果仍是标准 mlog），编译错误当场标红。
- `ExprHook`：把 `ExprStatement` 注入语句列表，并在 `SugarCanvas.load()/save()` 中执行 `foldAll()/unfoldAll()`。
- `ShortCircuitCompiler`：把 `&&` / `||` 谓词下降为条件 `jump`，按控制流顺序发射（不产生先行求值的布尔临时变量）；不依赖任何 Mindustry 类，便于在编译期与反编译恢复两侧复用。`whilebegin exprsc …` 等带 `c` 后缀的解析变体对应"折叠式表达式条件"（collapsed）。
- `RecoveryPredicate`：无依赖的谓词树模型（`EAGER` / `SHORT_CIRCUIT` / `UNKNOWN` 求值方式、loss/score 度量），供恢复代码在触碰游戏 API 之前构建与打分候选。

## 断言子系统（调试构建）

`mindustry.logic.SugarAsserts` + `logicsugar.assist.AssertInstructions` 移植自 cardillan/MlogAssertions（线格式逐字节兼容，致谢其作者 cardillan；Mindcode 产出的断言代码可被本编辑器识别）：七条自定义指令 `assertBounds` / `assertequals` / `assertflush` / `assertprints` / `error` / `log` / `breakpoint`，断言失败时程序在失败行自旋（`counter` 回退 + `yield`），消息由 `ProcessorStatus` 绘制在处理器上方；`breakpoint` 暂停游戏并清空全部 accumulator。

- **双身份序列化**：卡片 `write()` 直接输出指令 token，既是编辑器卡片也是 mlog 指令行；空槽位按 LogicSugar 惯例写 `~` 保持定长 token（上游无此约定，仅空字段场景降级）。
- **AssertEmit 开关**（设置项 `logicsugar.assertEmit`，默认 `strip`，**仅单机/编辑器生效**）：`strip` 把断言编译掉——sugar（含断言）随载体保存，mlog 保持原版可解析；`emit`（调试构建）把断言写回为真实指令，**原版客户端会将其降级为 InvalidStatement 占位**（程序能跑但断言静默失效）。联机会话（`Vars.net.active()`，已连接或自建）下 `currentAssertEmit()` 一律强制 `strip`——兼容底线在代码层强制，不依赖用户自觉；显式 `compile(..., AssertEmit)` 重载仅供验证矩阵与自测使用。
- **共存去重**：注册时若 `LAssembler.customParsers` 已有同名 opcode（如 MlogAssertions 先加载），整组跳过，不重复加面板卡片、不覆盖他人解析器。注意 MlogAssertions 后加载时会覆盖解析器并追加自己的卡片，两 mod 并存时面板可能出现两套卡片，属上游行为。
- **验证门**：候选或原始程序含断言时，verify 矩阵扩展为 FuncMode × SwitchStrategy × AssertEmit；无断言程序维持 2×2，编译成本不涨。`ProcessorStatus` 的地图扫描跳过断言指令（消息生命周期归指令自身管）。

## 函数与全局函数库

- 库文件：`<game data>/mods/config/LogicSugar/functions.txt`，只含 `funcdef … blockend` 对。`FunctionLibrary` 按 lastModified + 内容哈希缓存解析索引；损坏文件按函数逐个抢救，得到部分索引并在日志列出修复警告。
- 库语义（方案2）：库函数不得改写调用方变量——函数体写入的每个名字（含参数）都被重整为 `__ls_func_<name>_<name>`；`@` 系统变量与 `cellN` / `bankN` / `memoryN` 存储设备豁免，只读名字不动。
- 编辑入口 `FunctionLibraryDialog` 复用逻辑处理器编辑器（不绑定处理器），关闭时自动校验保存；保存失败会重开编辑器且修改不丢（`passThroughSugarOnError` + `discardButton` 逃生口）。

## 反编译器与恢复安全门

`mindustry.logic.SugarDecompiler` 把已存的 mlog 反向呈现为 Sugar 视图，流程：

1. 先按原版规则解析输入；带有效载体（`SugarCompiler.isSugarProgram` + `verifyRestore`）的程序优先走载体无损路径。
2. 载体过期（程序被外部编辑过）时剥掉载体变量，在裸指令流上重试推断。
3. **CFG 分诊**：用 `MlogCFG`（零依赖控制流图 IR）扫描可达指令，出现"已知安全形状"之外的动态 `@counter` 写入（跳表派发 `op add @counter @counter x` 与 `set @counter __ls_*` 蹦床除外）即认定程序不可静态恢复，直接保留 vanilla 并注明位置——这类程序本来就会验证失败，分诊只是更快、更明确。
4. 结构恢复：`recoverFunctions()` + `parseMain()` 生成候选 Sugar 源。函数区先过静态验证（区间外 jump 不得跳入、区间内 jump 不得跳出；嵌套调用前导跳向其他函数入口的 always 跳转豁免）。同一位置可能有多个候选帧（`tryFrames`），按 `RecoveryPredicate` 的 loss 排序取最优；贪心选择验证失败时，`backtrack()` 会在记录的决策点上逐个提升次优候选重试（有次数预算），每次仍走同一道门。
5. **安全门（必须保留）**：候选先重新编译，再与输入的规范化指令流比对，比对通过才允许返回恢复结果。验证矩阵覆盖 FuncMode × SwitchStrategy 全部组合（`verify`）——程序可能在另一台机器、另一个 switch 策略设置下保存，不能因本机设置不同而误判。任何识别不了的内容回退为原样保留的 vanilla 语句（`matchedMode = "flat"`）。

失败方向永远是"多显示原版代码"，绝不改写未知程序。新增恢复模式（跳转表、短路谓词等）一律放在这道门之后。

短路守卫恢复（`tryShortCircuitFrames`）是这套机制的核心用户：`ShortCircuitCompiler` 的 lowering 是若干 `[条件 jump, fallback jump]` 原子对的连续拼接（内部续接标签都落在原子对起点），守卫解析器从对的目标关系重建布尔树（`parseGuardTree`，带换目标环检测的备忘递归），为同一片守卫区域同时给出 `if` / `while` / `for` 候选。由此单原子守卫、顶层 `!`、任意嵌套 `&&`/`||` 树以及 `whilebegin`/`forbegin` 的 `exprsc` 条件都能恢复，不再限于固定四指令布局。体内跳回 while 守卫头的 always 跳转就是 `continue` 的 lowering 形状，由循环上下文恢复为 `continue` 语句。

辅助组件：`MlogCFG`（`cfgTest`）是反编译器共享的 CFG/数据流只读视图；`MlogLint`（`lintTest`，Bang logic_lint 风格）是 advisory 的编译后 MLog 静态检查器（未知 op、参数个数、对字面量赋值、自/越界跳转等，规则事实全部转录自 Mindustry-master 源码），当前独立于编译管线，供工具与测试使用。

## 跨类加载器访问约束（继承自 AGENTS.md）

运行时本模组类经 mod class loader 加载，`mindustry.logic.*` 游戏类在 app loader——同包名、**不同运行时包**：

- 游戏类的 `protected` / 包私有成员（`LStatement.field`、`LogicDialog.privileged` 等）只能：① 在本模组的子类实例方法内访问（`SugarStatement.fieldsHint` / `addCompactOp` 即此模式）；② 经 `Field.setAccessible(true)` 反射访问（`SugarLogicDialog.privilegedField` 是既定范式）。
- 静态辅助方法触碰这些成员**能编译通过**，运行时 UI 渲染时才抛 `IllegalAccessError`。
- 缓解策略分级：编辑器赖以工作的字段用硬反射（无降级模式）；锦上添花的功能字段用 `optionalField`/`optionalMethod`（`SugarCanvas`），上游改名时功能退化而不是整个编辑器崩溃。
- `crossLoaderTest` 以 child-first 加载器无头复现该拓扑，防止模式回退。

## 编辑器辅助功能

| 功能 | 类 | 要点 |
| --- | --- | --- |
| 框选/批量操作 | `assist.BoxSelect` + `BoxSelectDragPolicy` | capture 监听器事件驱动；拖动阈值为纯函数（8px slop、移动端 430ms 长按）便于自测 |
| 跳转线着色 | `assist.JumpLineColor` | 按目标着色三模式：关闭 / 分散色 / 积木色 |
| 隐藏内部变量 | `assist.VarDisplayFilter` | 过滤 MindustryX 变量浏览器里的 `__ls_*` 与 `_N`；只动展示用的 `allVars`，绝不碰 `executor.vars`（`sync` 指令的索引空间）；原版无 `allVars`，自动不生效 |
| 复制变量/打印缓冲 | `assist.VarClipboard` | SugarLogicDialog 按钮行，全精度 TSV 变量导出（按名排序）+ 打印缓冲；executor 经反射读取，失败则不显示按钮 |
| 处理器状态指示 | `assist.ProcessorStatus` | drawOver 分帧轮询全图处理器：停止显示「已停在第 N 条」、长 wait 画进度圆环、断言失败显示消息；设置三滑杆（阈值 0 关闭 / 每帧扫描数 / 警告特效） |
| 指令上限覆盖 | `assist.InstructionLimit` | **仅单机/编辑器生效**：联机会话强制回落原版 1000（原版解析器按静态上限截断、跨界 label jump 会清空处理器）；单机改 `LExecutor.maxInstructions`（1000→2000），字段为 final 时设置行隐藏 |
| 结构引导线 | `SugarCanvas.StructureController` | 块结构竖线与折叠；`load()` 后必须重装引导层 |

### 结构语句布局

`SugarStatements` 中的 `For`、`If`、`While` 和 `ElseIf` 将条件标签、条件编辑器和 `OP/Expr` 切换分别放在安全行；`For` 的循环变量、初值、步长和 `until` 也各自换行，折叠按钮单独放在末行。这样嵌套卡片只增加垂直高度，不依赖横向滚动，也不会让行尾控件被结构缩进推出卡片。条件字段使用紧凑宽度，`SugarCanvas.SugarStatementElem` 则按卡片实际宽度计算可用缩进，避免使用固定嵌套层数上限。布局行为需在不同方向、UI scale、语言和 MindustryX LogicSupport 侧栏状态下手测。

## 目录速查

```text
src/logicsugar/           模组侧：入口、设置、函数库、FunctionLibraryDialog
src/logicsugar/assist/    编辑器辅助：BoxSelect、JumpLineColor、VarDisplayFilter、MlogLint、
                          VarClipboard、ProcessorStatus、InstructionLimit、AssertInstructions
src/logicsugar/assist/expr/  表达式子系统：ExprCompiler、ExprStatement、ExprHook、ShortCircuitCompiler
src/mindustry/logic/      与游戏同包名的扩展层：SugarCompiler、SugarDecompiler、SugarStatements、
                          SugarAsserts、SugarCanvas、SugarLogicDialog、SugarFunctions、RecoveryPredicate、MlogCFG
test/                     与 src 同构的 main() 式自测（无 JUnit）
assets/bundles/           bundle.properties / bundle_zh_CN / bundle_zh_TW（用户可见文案）
```

注意源码目录是非标准布局：Gradle `sourceSets` 直接把 `src/`、`test/` 当根目录，没有 `src/main/java` 层级。

## 设计约束（务必保持）

- **兼容底线（项目所有者明文要求）：多人联机环境下必须兼容原版客户端**——保存到处理器的代码在任何原版客户端上都要能解析、能运行，优先级高于一切新功能。调试类功能（指令上限覆盖、AssertEmit=emit）只在单机/编辑器（`!Vars.net.active()`）生效，联机会话一律回落原版行为，门禁在代码层强制（`InstructionLimit.sessionAllows` / `SugarCompiler.currentAssertEmit`）。残余风险：单机创建的越界内容若分享到多人环境，原版客户端仍会截断/清空/静默降级，代码无法阻止分享，只能靠设置描述与文档讲清。
- 用户可见文案一律走 `logicsugar.*` bundle key，不硬编码。
- 受保护游戏成员访问只走子类实例方法或反射（见上），静态辅助代码只用 public 游戏 API。
- 反编译恢复必须留在重编译/规范化流比对门后，失败方向是"多显示原版代码"。
- 上游 API 依赖尽量做成可降级：核心路径硬反射，外围功能 optional 反射。
