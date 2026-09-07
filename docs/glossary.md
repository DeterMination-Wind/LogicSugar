# 术语表

按主题归类的 LogicSugar 术语。每条说明它在本项目语境下的准确含义。

## 项目形态

### Sugar / Sugar 语句
LogicSugar 提供的结构化编辑语言：`ifbegin`/`elif`/`else`、`forbegin`、`whilebegin`、`switchbegin`/`case`、`break`/`continue`、`blockend`、`funcdef`/`funccall`/`return`。它们以卡片形式出现在编辑器里，`build()` 返回 `NoopI`（自身不产生指令），语义由编译器 lowering 成普通 mlog。

### mlog
Mindustry 原生逻辑指令集（`set` / `op` / `jump` / `read` / …）。LogicSugar 的硬约束是保存结果必须是原版兼容的 mlog：无模组客户端能运行、能重开。

### bekBundled
`LogicSugarMod` 上的静态布尔标记，表示"当前运行在 Neon 聚合环境中"。为 `true` 时模组跳过自建设置分类，改由宿主调用 `bekBuildSettings(...)` 收口设置；独立运行时为 `false`。

## 编译与持久化

### 载体（carrier）
保存到处理器里的持久化元数据，是真实的 `set` 语句（能挺过原版 parse/save 往返）：`set __ls_sugar "<base64>"` 存 Sugar 源码，`set __ls_lib "<base64>"` 存程序用到的库函数子集（跨机器可重编译）。单条上限 60000 字符。

### 注释标记块（marker block）
v2.0.0 旧程序的持久化方式：`# @logic-sugar-v1 begin` / `# @logic-sugar-line ` / `# @logic-sugar-v1 end`。现仅作读取兼容，新程序一律用载体。

### FuncMode（函数模式）
函数展开方式。`normal` = 共享 `@counter` 子程序（函数体上提到程序尾部，一次定义多处跳转）；`inline` = 每个调用点展开一份副本，编译器临时名带 `__ls_i_<callId>_`。设置项 `logicsugar.funcMode`。

### SwitchStrategy（分派策略）
`switch` 的下降形态。`auto` = 整数 case 且值域跨度 ≤255 时按可执行指令成本在比较链与跳转表间二选一；`chainOnly` = 恒用比较链（与 2.3.1 之前输出逐字节一致）。设置项 `logicsugar.switchStrategy`。

### AssertEmit（调试断言构建）
断言卡片的编译开关（设置项 `logicsugar.assertEmit`）。`strip`（默认）把断言编译掉，mlog 保持原版可解析；`emit` 把断言写回为真实自定义指令——原版客户端会把这些行降级为 InvalidStatement 占位（断言静默失效）。**仅单机/编辑器生效**：联机会话强制 `strip`（见"单机门禁"）。

### 单机门禁（single-player gate）
项目硬底线的执行机制：**多人联机环境必须兼容原版客户端**，因此会改变保存产物语义的调试类功能（目前是 AssertEmit=emit）只在 `!Vars.net.active()`（单机/地图编辑器）时生效，联机（已连接或自建）一律回落原版行为。门禁在代码层强制（`SugarCompiler.currentAssertEmit`）；不提供改变指令预算的能力（指令上限覆盖曾试做后移除，产物恒 ≤1000 条），不依赖用户自觉；纯展示类功能不受此限。残余风险：单机创建的越界内容被分享到多人环境时原版客户端仍会截断/清空/静默降级，只能靠文档与设置描述讲清。

### 断言语句集（assertions）
移植自 cardillan/MlogAssertions 的八条运行时检查指令（`assertBounds`/`assertequals`/`assertflush`/`assertprints`/`asserttype`/`error`/`log`/`breakpoint`），线格式逐字节兼容：断言失败程序在失败行自旋并由 `ProcessorStatus` 显示消息，`breakpoint` 暂停游戏。与 MlogAssertions 并存时按"先到先得"跳过重复 opcode 注册。

### 线格式（wire format）
自定义指令在 token 流中的精确形状（opcode 拼写、参数顺序、引号约定）。断言语句集的线格式必须与 MlogAssertions/Mindcode 保持逐字节一致，由 `assertTest` 钉住；任何一侧漂移都会破坏互操作。

### 跳转表（jump table）
`switch` 的一种下降结果：先做上下界守卫，再用 `op add @counter @counter` 按槽位分派；越界与空洞槽走默认路径。非整数或跨度过大的 case 集合自动回退比较链。

### 跳转链穿线（jump threading）
lowering 之后对"无条件跳转到无条件跳转"的链做合并，减少冗余指令；带环检测保证循环与自跳转安全（`SugarCompiler.threadAlwaysJumpTargets`）。

### `__ls_` 前缀
编译器保留命名空间：函数返回变量、临时守卫、内联编号等都用它。用户函数/参数名不得使用；`VarDisplayFilter` 会把它们从 MindustryX 变量浏览器里隐藏。

### 表达式临时变量（`_0, _1, …`）
表达式编译的栈式编号临时变量，每个一次写一次读形成线性链——这是反向把 `op` 链重建为表达式的关键前提。

### 内存对象存储（memory object storage）
上游 #12459（v160 前瞻）的 `MemoryBlock` 改动：内部改为数字 + 对象双数组（含哨兵），logic 的 `read` / `write` 可存取对象（单位、方块等），存档经 `TypeIO.writeObject` 带版本迁移（旧档全按数字读回）；越界 `read` 从返回 NaN 改为返回 null。LogicSugar 钉在 v155.4（内存只存数字、越界=NaN），升级 `minGameVersion` 时 `MlogLint` 的内存/类型语义需按版本区分。详见[架构总览](architecture.md)「上游版本适配笔记」。

## 表达式与恢复

### 数组（array）
`array` 声明卡定义的纯 sugar 抽象：把内存块变量（如 `cell1`）上 `[base, base+size)` 的一段物理地址登记为命名数组。卡片本身不产出任何 mlog 行（lower 时剥离，产物保持纯原版指令）；表达式下标 `buf[i]` / 下标赋值 `buf[i] = x` 在编译期查 `ArrayRegistry` 换算物理地址（= base + 逻辑下标）后发射原版 `read` / `write`。v0 限制：base/size 仅接受整数字面量，重名与同内存块区间重叠是编译错误，数组名不得与函数重名。编辑器折叠只在注册表把 `read`/`write` 的内存块命中到已声明数组时把该行折回下标表达式——纯原版 mlog（无声明卡）不做数组推断恢复；字面量下标越界是编译错误，变量下标不做静态越界检查，运行时保持原版内存语义（v155.4 越界读返回 NaN）。由 `arrayTest` 钉住。

### 短路求值（short-circuit）
`&&` / `||` 按控制流顺序求值：右侧只在需要时执行。`ShortCircuitCompiler` 把短路谓词下降为条件 `jump`，不产生先行求值的布尔临时变量。

### 恢复（recovery / 反编译）
打开普通 mlog 时自动识别其中的 `if` / `for` / `while` / `switch` / 函数结构并还原为 Sugar 视图（`SugarDecompiler`）。

### 安全门（verification gate）
恢复结果的强制闸口：候选 Sugar 必须重新编译并与输入的规范化指令流比对一致才被接受；不识别的内容回退为原样 vanilla 语句。失败方向永远是"多显示原版代码"。

### flat 模式
安全门未通过（或输入无结构）时的结果：逐语句保留规范化 vanilla mlog，不呈现任何 Sugar 结构。

### collapsed（`c` 后缀）
语句解析器的折叠式变体（如 `forbeginc`、`whilebegin exprsc …`），对应条件为折叠进卡片一行的表达式形式；`exprsc` 表示短路口径的表达式条件。

### RecoveryPredicate
无依赖的谓词树模型（`And`/`Or`/`Not`/比较原子），带 `EAGER` / `SHORT_CIRCUIT` / `UNKNOWN` 求值方式标注与 loss/score 度量，供恢复代码在触碰游戏 API 前构建、打分候选。

## 函数

### 函数库（function library）
全局函数文件 `<game data>/mods/config/LogicSugar/functions.txt`，只含 `funcdef … blockend` 对，所有处理器共享。损坏时按函数逐个抢救为部分索引；在处理器编辑器内直接编辑，关闭自动校验保存。

### 名字重整（mangling）
库函数的隔离语义：函数体写入的每个名字（含参数）重整为 `__ls_func_<name>_<name>`，保证不改写调用方变量；`@` 系统变量与 `cellN`/`bankN`/`memoryN` 豁免，只读名字不动。

## 编辑器

### 画布（canvas）
逻辑编辑器的语句卡片区域。原版是 `LCanvas`；本模组用 `SugarCanvas` 子类接管，叠加结构引导线、框选、表达式折叠等能力。

### 跨类加载器访问（cross-loader access）
运行时模组类与 `mindustry.logic.*` 游戏类分属不同类加载器——同包名、不同运行时包。受保护/包私有成员只能在本模组子类实例方法内或经反射访问，否则运行时抛 `IllegalAccessError`（源码级能编译通过）。详见 AGENTS.md。

### 双视图（original / Sugar）
`SugarLogicDialog` 提供的切换：查看生成的原版 mlog 或返回 Sugar 编辑，切换前保护未保存修改。

### 可降级反射（optional reflection）
`SugarCanvas` 对外围功能字段的策略：`optionalField`/`optionalMethod` 找不到上游成员时该功能静默退化，而不是整个编辑器崩溃；核心字段（如 `LogicDialog.privileged`）则用无降级余地的硬反射。

### 逻辑语句本地化（logic localization）
上游 #12158 + #12569 的改动：`LStatement` 增加 `bundle()` / `localizedName()` / `statementKey()`，卡片标题、语句菜单与搜索文案走 bundle，约定键 `instruction.<statementKey小写>`（上游设置项 `logiclocalization`，默认开）。LogicSugar 自身的卡片本地化沿用 `logicsugar.*` 键与设置 `logicsugar.localizeCards`，键名与该约定对齐。详见[架构总览](architecture.md)「上游版本适配笔记」。

## 构建与发布

### D8 / classes.dex
把 Java 字节码转成安卓 `classes.dex` 的工具链。Mindustry 安卓端从 jar 内的 `classes.dex` 加载 Java 模组，桌面中间 jar 不含它、不能分发。构建用 `--min-api 21 --release --lib android.jar`，并传游戏类路径供 desugar 解析。

### deploy（合并 jar）
唯一可分发产物形态：桌面 classes + `classes.dex` + `mod.json` + 资产的合并 jar（`LogicSugar-v<version>.jar`）。`deploy` 完成后自动复制改名到 `构建/LogicSugar/LogicSugar-dev.jar` 作为本地开发产物。

### dev 身份（LogicSugar-dev / 0.0.0）
本地开发期 `mod.json` 的临时身份（工作区默认约定）；发布态改回 `LogicSugar` / `<version>`。详见[版本与发布](release.md)。

### bundle
Mindustry 的 i18n 文案文件。本模组在 `assets/bundles/` 下维护 `bundle.properties` / `bundle_zh_CN.properties` / `bundle_zh_TW.properties` 三份，key 以 `logicsugar.*` 开头，用户可见文案不允许硬编码。
