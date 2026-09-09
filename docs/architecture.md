# 架构总览

改 `src/` 下的任何代码前先读本文。它解释 LogicSugar 的组织方式：Sugar 结构如何编译成 mlog、程序如何在保存后还原、以及为什么跨类加载器访问与反编译安全门是两条硬约束。

## 双形态：独立运行 / 并入 Neon 聚合

LogicSugar 是独立模组，同时也是 Neon 聚合模组的子模组之一（id `ls`）。两种形态共用同一套代码，由主类上的静态标记切换：

- `logicsugar.LogicSugarMod#bekBundled`：宿主（Neon）注入时置 `true`。
- **独立运行**：`init()` 在 `ClientLoadEvent` 后调用 `LogicSugarSettings.setup(true)`，注册自己的 `@logicsugar.settings` 设置分类（函数模式、Switch 分派策略、调试断言构建、函数库入口、处理器状态、隐藏内部变量、框选、跳转线着色）。
- **Neon 聚合态**：宿主调用 `bekBuildSettings(SettingsTable)` 把设置行挂进 Neon 总设置页；模组自建分类被整体跳过（`if(!bekBundled)`），避免重复条目。注意 `bekBuildSettings` 当前聚合的是函数模式、调试断言构建、函数库入口、处理器状态滑杆、隐藏变量、框选与跳转线着色；`SwitchStrategySetting` 只在独立态的 `build()` 中注册。

除设置入口外，两种形态的行为完全一致；不存在单独的聚合分支代码。

## 入口与生命周期

入口类 `logicsugar.LogicSugarMod`（`mod.json` 的 `main`），初始化流程：

1. **注册语句**：`registerStatements()` 把 16 种 `SugarStatements` 卡片（`ForBegin` / `WhileBegin` / `SwitchBegin` / `IfBegin` / `Case` / `ElseIf` / `Else` / `Break` / `Continue` / `BlockEnd` / `FuncDef` / `FuncCall` / `Return` / `Array` / `Matrix` / `ArrayInit`）加入 `LogicIO.allStatements`；随后注册数据子系统模块（`DataModules.register(new ArrayBulkModule/RecordModule/ContainerModule/BitsetModule/MapModule/ListHeapModule/ChainModule())`，同时注册表达式 intrinsic provider），再调用 `SugarStatements.installParsers()` 与 `DataModules.registerParsers()` 向 `LAssembler.customParsers` 注册全部 token 解析器（含 `forend` / `whileend` / `switchend` 三个旧开发版本标记的只读兼容，以及 `record` / `stack` / `queue` / `bitset` / `map` / `list` / `heap` / `chain` 八张数据声明卡）。整个 `registerStatements()` 由静态 `registered` 守卫，重复 `init()` 不会重复添加卡片或解析器。这是与反编译器、自测共享的唯一注册点。
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
- `ExprHook`：把 `ExprStatement` 注入语句列表，并在 `SugarCanvas.load()/save()` 中执行 `foldAll()/unfoldAll()`；折叠/展开全程固定在同一份画布数组注册表上（`ArrayRegistry.enter/restore`），`foldAll` 额外把注册表命中的原版 `read`/`write` 行作为链节点参与折叠（`rebuildAssignment` 折回 `buf[i] = x` 赋值卡）。
- `ArrayRegistry`：`array` 声明卡的编译期注册表（程序级，静态上下文 enter/restore 传递）。数组是纯 sugar 抽象——卡片 lower 时剥离，下标 `buf[i]` 按声明区间（内存块 + [base, base+size)）换算物理地址发射原版 `read`/`write`；严格口径（编译路径）拒绝重名/同内存块重叠/非法字面量，宽松口径（编辑器路径）供折叠与标红使用。v0 仅支持整数字面量的 base/size；纯原版 mlog 无声明卡时不做数组推断，`read`/`write` 原样保留。
- `ShortCircuitCompiler`：把 `&&` / `||` 谓词下降为条件 `jump`，按控制流顺序发射（不产生先行求值的布尔临时变量）；不依赖任何 Mindustry 类，便于在编译期与反编译恢复两侧复用。`whilebegin exprsc …` 等带 `c` 后缀的解析变体对应"折叠式表达式条件"（collapsed）。
- `RecoveryPredicate`：无依赖的谓词树模型（`EAGER` / `SHORT_CIRCUIT` / `UNKNOWN` 求值方式、loss/score 度量），供恢复代码在触碰游戏 API 之前构建与打分候选。

## 数据子系统（数组批量运算 / 矩阵 / 记录 / 容器 / 位集 / 哈希表 / 列表 / 堆 / 链表）

数据子系统把「内存块上的结构化数据」做成纯编译期抽象：声明卡只是元数据，lower 阶段整体跳过、不产指令；所有运算降级为原版 `read` / `write` / `op` / `funccall` / `jump`，产物仍是原版可解析的 mlog，联机（含自建服）与单机行为一致。

### 框架：ExprIntrinsics + DataModules + 注入函数

- **`ExprIntrinsics`**（`logicsugar.assist.expr`）：表达式函数名 → 原版指令链的展开点。Provider 实现必须放在 `expr` 包（`Node` / `Line` 是 `ExprCompiler` 的包私有类型）。`ExprCompiler` 的 `compileNode(Call)` / `compileNode(Member)` / 成员赋值路径先查 provider，未命中退回普通 `funccall` / sensor 路径。用户 `funcdef` / 库函数同名时优先（`enterUserFunctions` 遮蔽 intrinsic），`min` / `max` 按实参个数分派（1 参 = 数组运算，2 参 = 原版内置），名字匹配大小写不敏感。
- **`DataModule` / `DataModules`**（`logicsugar.assist.data`）：每个数据结构一个模块（`id()` 去重）。`LogicSugarMod.registerStatements()` 注册全部模块（同时把 `intrinsics()` 注册进 `ExprIntrinsics`）并调用 `DataModules.registerParsers()` 安装声明卡解析器与调色板卡片；`SugarCompiler.compile` 在 `analyze` 之后、`lower` 之前 `DataModules.collectAll(...)` 建立程序级注册表，`finally` 里 `restore()` 清理——配对标记在 `collectAll` 之前置位，任一模块 `collect` 抛异常也会恢复，不把注册表泄漏给下一次编译或编辑器渲染。`markInvalid` 供编辑期标红，`builtinSugar()` 提供注入函数源文本。
- **注入函数**：模块把循环型 / 写内存型操作写成 `funcdef __ls_builtin_*`，由 `SugarCompiler` 经 `SugarFunctions.withBuiltins` 并入本次编译的 `LibraryIndex`。normal 模式全程序共享一份子程序，未使用不进产物；`extractLibrarySource` 只处理用户库文本，内置函数不会进入 `__ls_lib` 载体、也不会出现在用户函数库。inline 模式按调用点展开函数体。

### 语法与降级

| 结构 | 声明卡（token 定长，空槽 `~`） | 表达式用法 | 降级目标 |
| --- | --- | --- | --- |
| 数组 | `array <name> <memory> <base> <size>` | `buf[i]`、`len(buf)` | `read` / `write`；`len` 折叠为 `size` |
| 数组初始化 | `arrayinit <name> <v0>…<v7>` | —（卡片位置即写入位置） | 最多 8 条 `write`（`~` 跳过） |
| 矩阵 | `matrix <name> <memory> <base> <rows> <cols>` | `m[i][j]` 读 / 写 | 地址 = `base + i*cols + j`；字面量编译期折叠，越界报错 |
| 批量数组运算 | 复用 `array` / `matrix` | `sum` `avg` `min` `max` `count` `indexof` `fill` `copy` `sortasc` `sortdesc` | 注入函数 `__ls_builtin_arr*` |
| 记录 | `record <name> <f1>…<f8>` | `p.f1` 读 / `p.f1 = expr` 写 | 普通变量 `<name>_<field>` |
| 栈 | `stack <name> <memory> <base> <size>` | `spush` `spop` `speek` `ssize` `sclear` | `read` / `write` + `__ls_stk_<name>_top` |
| 队列 | `queue <name> <memory> <base> <size>` | `qpush` `qpop` `qpeek` `qsize` `qclear` | `read` / `write` + `__ls_que_<name>_head/_tail/_count` |
| 位集 | `bitset <name> <memory> <base> <words>` | `bset` `bclr` `btest` `bcount` | 每 word 64 位，`and` / `or` / `shl` / `shr` + `read` / `write` |
| 哈希表 | `map <name> <memory> <base> <capacity>` | `mapset` `mapget` `maphas` `mapdel` `mapsize` `mapclear` | 开放寻址；键区 `[base, base+capacity)`、值区 `[base+capacity, base+2*capacity)`；`hash = abs(key) % capacity`，线性探测 |
| 列表 | `list <name> <memory> <base> <size>` | `lappend` `lget` `lset` `linsert` `lremove` `lfind` `lsize` | `read` / `write` + `__ls_lst_<name>_count` |
| 堆（小顶） | `heap <name> <memory> <base> <size>` | `hpush` `hpop` `hsize` | `read` / `write` + `__ls_hep_<name>_count` |
| 链表 | `chain <name> <memory> <base> <size>` | `cinit` `cclear` `cnew` `cfree` `cget` `cset` `cnext` `clink` `cshead` `chead` `clen` | 节点 i 的值槽 `base+2*i`、next 槽 `base+2*i+1`（`next = -1` 为链尾）；`read` / `write` + `__ls_chn_<name>_head/_free` |

- **容量检查**：`memory` 形如 `cellN` 容量 64、`bankN` / `worldN` 容量 512（大小写不敏感），`base+size`（矩阵为 `base+rows*cols`，哈希表为 `base+2*capacity`）超容量编译期报错；其它名字跳过。
- **越界断言**：仅 `AssertEmit=emit` 的调试构建下、下标为非常量时，在 `read` / `write` 前发射 `assertBounds`（复用 `SugarAsserts` 线格式）；`strip` 模式不发射。数组/矩阵字面量越界始终是编译错误。
- **空容器语义**：pop / peek 在空时返回 NaN（`op div <tmp> 0 0` 或越界 `read`）；push 在满时返回当前长度且不写入；`lget` 越界返回 NaN，`lset` / `linsert` / `hpush` 失败返回 0，`lremove` 越界返回 NaN、成功返回被删除值，`lfind` 未找到返回 -1，`mapget` 未命中返回 NaN，`mapset` 在 NaN/±Inf 键上返回 -1；链表 `cget` 越界返回 NaN，`cset` / `clink` / `cfree` 越界返回 0，`cnext` 越界返回 -1，`cnew` 在空闲链为空时返回 -1，`clen` 空链返回 0。
- **保留命名空间**：隐藏状态变量与注入函数名都以 `__ls_` 开头（`VarDisplayFilter` 自动隐藏，用户声明名使用该前缀会被模块拒绝）。记录字段变量 `<name>_<field>` 是普通用户变量，不隐藏、可调试。

### 单机 / 联机与 1000 指令约束

- 声明卡不产指令，全部运算都是原版指令：产物在任何原版客户端可解析、可运行，联机（含自建服）与单机一致；数据子系统不引入任何 `AssertEmit` 例外。
- 1000 条上限沿用 `SugarCompiler` 既有检查（lowered 指令 + 载体行一起计数），新功能不绕过。循环型操作（push / sort / find / 哈希探测等）在 normal 模式做成共享 `funcdef`，指令预算与调用点数量线性、与结构数量无关；inline 模式会复制函数体，长程序需切回 normal（编译器在超限报错里提示）。

### 已知限制

- **跨模块校验未统一**：每个模块只严格校验「自己声明的结构 + `array`/`matrix`」。不同模块之间（如 `stack` 与 `list` 共用同一内存块且区间重叠，或跨结构重名）不做统一校验，需要用户自行避免；统一程序级名字/区间表需要改各模块的 `collect` 口径，属后续工作。
- **哈希表**：不支持字符串键；键比较沿用原版 `equal` 的 1e-6 容差；首次使用前必须调用 `mapclear(m)`（未初始化槽读回数字 0，会被当作「已占用且 key = 0」）；删除是墓碑策略——只把 key 槽写成 NaN，value 槽保留，探测是整表环形扫描因此墓碑不会截断探测链。
- **链表**：首次使用前必须调用一次 `cinit(c)` / `cclear(c)`——未赋值变量读作 0，不初始化直接 `cnew` 会把 0 号节点当成空闲节点；`cfree` 不检测重复释放，把已在空闲链上的节点再次释放会让空闲链成环；`clen` / `cfree` 的遍历在用户手工 `clink` 造出环时不会终止，链表不变量（next 槽只由本模块写入、指向合法下标或 -1）由使用者维护。
- **状态变量不随存档持久化**：隐藏计数是普通 mlog 变量，处理器代码重新载入（存档往返 / 重编译 / 换处理器）后归零，而内存块内容保留；跨存档运行的结构需要在程序开头显式重建状态（`sclear` / `qclear` / 重新初始化内存或计数）。
- 矩阵不支持 `len()`（用 `rows*cols`）；`len(a, b)` 仍是原版向量长度。

## 断言子系统（调试构建）

`mindustry.logic.SugarAsserts` + `logicsugar.assist.AssertInstructions` 移植自 cardillan/MlogAssertions v0.8.2（线格式逐字节兼容，致谢其作者 cardillan；Mindcode 产出的断言代码可被本编辑器识别）：八条自定义指令 `assertBounds` / `assertequals` / `assertflush` / `assertprints` / `asserttype` / `error` / `log` / `breakpoint`，断言失败时程序在失败行自旋（`counter` 回退 + `yield`），消息由 `ProcessorStatus` 绘制在处理器上方（失败消息统一走 `logicsugar.asserts.failed[WithValues]`，带「(expected X, got Y)」诊断）；`breakpoint` 按上游 v0.8.2 语义暂停游戏：视角居中到处理器、按设置临时分离视角、冻结全部 accumulator 并在本帧更新结束后归还，暂停期间消息持续绘制。设置「断言失败即断点」（`logicsugar.assertsAreBreakpoints`）可让断言失败改为在失败指令处暂停；「禁用断点」（`logicsugar.disableBreakpoints`）让 breakpoint 与断点化断言直接跳过。`asserttype` 的六种类型 token 与上游一致，LogicSugar 额外支持 `none`（线上写 `null`）——上游 `AssertDataType.valueOf` 不认识该 token，使用空值断言的调试构建无法在 MlogAssertions/Mindcode 中打开。

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
| 处理器状态指示 | `assist.ProcessorStatus` | drawOver 分帧轮询全图处理器（`Groups.build`，视野外按 hitbox 裁剪）：停止显示「已停在第 N 条」、长 wait 画进度圆环、断言失败显示消息；扫描预算按帧时长换算（`min(delta*60,5) × 每帧扫描数`，低帧率不爆发）；设置三滑杆（阈值 0 关闭 / 每帧扫描数 1–5000 档位 / 警告特效）+ 断点三开关（禁用断点 / 断言失败即断点 / 断点分离视角） |
| 结构引导线 | `SugarCanvas.StructureController` | 块结构竖线与折叠；`load()` 后必须重装引导层 |

### 结构语句布局

`SugarStatements` 中的 `For`、`If`、`While` 和 `ElseIf` 将条件标签、条件编辑器和 `OP/Expr` 切换分别放在安全行；`For` 的循环变量、初值、步长和 `until` 也各自换行，折叠按钮单独放在末行。这样嵌套卡片只增加垂直高度，不依赖横向滚动，也不会让行尾控件被结构缩进推出卡片。条件字段使用紧凑宽度，`SugarCanvas.SugarStatementElem` 则按卡片实际宽度计算可用缩进，避免使用固定嵌套层数上限。布局行为需在不同方向、UI scale、语言和 MindustryX LogicSupport 侧栏状态下手测。

## 上游版本适配笔记（v155.4 → v160 前瞻）

LogicSugar 编译与测试钉在上游 Mindustry v155.4（`build.gradle` 的 `mindustryVersion`，`mod.json` 的 `minGameVersion: "155"`）。本节记录上游（Anuken/Mindustry）v159.4 之后、面向 v160 的 Logic 相关改动（基于 Mindustry-master 工作区核实，区间 `894bab4ecd..c81d0eb025`，2026-08-26 ~ 09-05），每条给出上游变化、对 LogicSugar 的影响与适配时机。本节涉及的 `mindustry.logic.*` 成员访问结论必须与 `AGENTS.md` 保持一致，冲突时以 `AGENTS.md` 为准。

### 内存对象存储（上游 #12459，fac33d08d）

- **上游变化**：`MemoryBlock` 改为双数组（数字数组 + 对象数组 + 哨兵），logic 的 `read` / `write` 可存取对象（单位、方块等）；存档经 `TypeIO.writeObject` 序列化并带 version 迁移（旧存档全按数字读回）。**行为变化：越界 `read` 从返回 NaN 改为返回 null**。
- **影响/风险**：v155.4 的内存 cell 只存数字、越界 `read` 返回 NaN，LogicSugar 的产物与测试目前都建立在这套语义上（`assertTypeTest` 已为「内存对象存储」场景预留 number/对象分型）。`MlogLint` 当前只做 token 形状检查、不涉及内存语义；未来若加入内存/类型检查，必须按 `minGameVersion` 区分「越界=NaN（旧语义）」与「越界=null（新语义）」两套规则。
- **适配动作**：现在无需改动。升级 `minGameVersion` 时：复查内存相关文档与测试的 NaN 假设，为 `MlogLint` 的内存/类型规则引入按版本分叉的语义。

### LAccess cleanup（b9189a5570 + 023a4ff1）

- **上游变化**：`LAccess.isPrivileged()` 方法删除，改为公共字段 `privileged`。
- **影响/风险**：LogicSugar 当前未引用 `LAccess.isPrivileged()`；`ExprCompiler` 依赖的 `LAccess.senseable` 列表内容未变——升级无 API 风险。注意区分：`SugarCanvas` / `BoxSelect` 里自有同名 `isPrivileged()` 辅助方法读的是 `LCanvas.privileged`（反射），`SugarLogicDialog` 读的是 `LogicDialog.privileged`，均与 `LAccess` 无关，不受此次 cleanup 影响。
- **适配动作**：现在无需改动；升级时无需调整任何反射目标。

### 新逻辑规则与 marker 控制

- **上游变化**：新增规则 `unitLight`（`set rule unitLight <bool>`，`LogicRule.unitLight`），开关单位灯光；marker 新增 `light` 控制（`LMarkerControl.light`）；world/minimap 的参数标签 "true/false" 改名 "truefalse"（纯 UI bundle key 变化）。
- **影响/风险**：均为新增枚举项/规则项，不改既有 opcode 与线格式；`truefalse` 改名只影响上游自身 UI 文案。Sugar 保存产物是原版可解析的 mlog，这类新字面量经原版 parse/save 往返即可携带。
- **适配动作**：现在无需改动；升级后新规则与新 marker 控制自动可用。

### 逻辑语句本地化（上游 #12158 + #12569）

- **上游变化**：新增设置 `logiclocalization`（默认开）；`LStatement` 增加 `bundle()` / `localizedName()` / `statementKey()`，卡片标题、语句菜单与搜索文案走 bundle，约定键 `instruction.<statementKey小写>`。
- **影响/风险**：LogicSugar 自身的卡片本地化走 `logicsugar.*` 键并新增设置 `logicsugar.localizeCards`（另一任务并行实现），键名与上游约定对齐：三份 bundle 的 `instruction.*` 键以此为准。升级时需确认上游 `logiclocalization` 与 `logicsugar.localizeCards` 两层开关叠加后行为符合预期。
- **适配动作**：现在：保持 `instruction.<statementKey小写>` 键名约定一致。升级时：复查两层本地化开关的叠加行为。

### 无新 opcode 与逻辑显示器修复

- **上游变化**：`LogicIO` 无改动（无新 opcode）；`logicids.dat` 仅新增 target-dummy 方块条目（既有内容逻辑 ID 的稳定映射不受影响）；逻辑显示器修复：拼接大屏统一 `rootDisplay.buffer`（#12514）、图源屏强制刷新（#12418）。
- **影响/风险**：线格式稳定，Sugar 载体、反编译器与断言线格式均不受影响。
- **适配动作**：无需动作。

### 升级 checklist

bump 到新上游版本时按序执行：

1. `build.gradle` bump `mindustryVersion`，同步 CI 检出的 Mindustry 固定 commit 与 [development.md](development.md) / [release.md](release.md) 的版本表述；按需 bump `mod.json` 的 `minGameVersion`。
2. 在 `Mindustry-master` 工作区重建配套 `Mindustry.jar`（`desktop:dist`，需配套 Arc 检出）。
3. `./gradlew check` 全绿；重点盯 `crossLoaderTest`（跨类加载器访问模式）、`lintTest`、`assertTypeTest`（内存 number/对象分型）。
4. 逐条核对上文各节标记为「升级时做」的适配动作：MlogLint 内存/越界语义按新版本分叉、`instruction.*` 键与上游 `statementKey()` 约定仍对齐、上游成员签名变化未破坏既有反射目标。
5. 按 [testing.md](testing.md) 的手测清单上游戏验证（卡片渲染、本地化开关），并按工作区默认模式产出 `构建/LogicSugar/LogicSugar-dev.jar` 做本地确认。

## 目录速查

```text
src/logicsugar/           模组侧：入口、设置、函数库、FunctionLibraryDialog
src/logicsugar/assist/    编辑器辅助：BoxSelect、JumpLineColor、VarDisplayFilter、MlogLint、
                          VarClipboard、ProcessorStatus、AssertInstructions
src/logicsugar/assist/expr/  表达式子系统：ExprCompiler、ExprStatement、ExprHook、ArrayRegistry、
                          ShortCircuitCompiler、ExprIntrinsics 与各数据结构的 *Intrinsics
src/logicsugar/assist/data/  数据子系统：DataModule/DataModules/DataDeclaration 框架 +
                          ArrayBulkModule、RecordModule、ContainerModule、BitsetModule、MapModule、ListHeapModule
src/mindustry/logic/      与游戏同包名的扩展层：SugarCompiler、SugarDecompiler、SugarStatements、
                          SugarAsserts、SugarCanvas、SugarLogicDialog、SugarFunctions、RecoveryPredicate、MlogCFG
test/                     与 src 同构的 main() 式自测（无 JUnit）
assets/bundles/           bundle.properties / bundle_zh_CN / bundle_zh_TW（用户可见文案）
```

注意源码目录是非标准布局：Gradle `sourceSets` 直接把 `src/`、`test/` 当根目录，没有 `src/main/java` 层级。

## 设计约束（务必保持）

- **兼容底线（项目所有者明文要求）：多人联机环境下必须兼容原版客户端**——保存到处理器的代码在任何原版客户端上都要能解析、能运行，优先级高于一切新功能。调试类功能（目前是 AssertEmit=emit）只在单机/编辑器（`!Vars.net.active()`）生效，联机会话一律回落原版行为，门禁在代码层强制（`SugarCompiler.currentAssertEmit`）。不提供改变指令预算的能力：指令上限覆盖曾试做后被移除（保存产物恒 ≤1000 条是硬不变式）。残余风险：单机创建的调试构建若分享到多人环境，原版客户端仍会静默降级，代码无法阻止分享，只能靠设置描述与文档讲清。
- 用户可见文案一律走 `logicsugar.*` bundle key，不硬编码。
- 受保护游戏成员访问只走子类实例方法或反射（见上），静态辅助代码只用 public 游戏 API。
- 反编译恢复必须留在重编译/规范化流比对门后，失败方向是"多显示原版代码"。
- 上游 API 依赖尽量做成可降级：核心路径硬反射，外围功能 optional 反射。
