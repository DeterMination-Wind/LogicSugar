# 架构总览

改 `src/` 下的任何代码前先读本文。它解释 LogicSugar 的组织方式：Sugar 结构如何编译成 mlog、程序如何在保存后还原、以及为什么跨类加载器访问与反编译安全门是两条硬约束。

## 双形态：独立运行 / 并入 Neon 聚合

LogicSugar 是独立模组，同时也是 Neon 聚合模组的子模组之一（id `ls`）。两种形态共用同一套代码，由主类上的静态标记切换：

- `logicsugar.LogicSugarMod#bekBundled`：宿主（Neon）注入时置 `true`。
- **独立运行**：`init()` 在 `ClientLoadEvent` 后调用 `LogicSugarSettings.setup(true)`，注册自己的 `@logicsugar.settings` 设置分类（函数模式、Switch 分派策略、调试断言构建、函数库入口、处理器状态、单位 flag 显示/按 flag 着色、隐藏内部变量、框选、跳转线着色）。
- **Neon 聚合态**：宿主调用 `bekBuildSettings(SettingsTable)` 把设置行挂进 Neon 总设置页；模组自建分类被整体跳过（`if(!bekBundled)`），避免重复条目。注意 `bekBuildSettings` 当前聚合的是函数模式、调试断言构建、函数库入口、处理器状态滑杆、单位 flag 显示/按 flag 着色、隐藏变量、框选与跳转线着色；`SwitchStrategySetting` 只在独立态的 `build()` 中注册。

除设置入口外，两种形态的行为完全一致；不存在单独的聚合分支代码。

**每个改动都要过一遍 Neon 兼容性（项目所有者明文要求）**：任何用户能看见、能改的东西，必须在**两种形态下都看得见、都能改**。`bekBuildSettings(SettingsTable)` 就是聚合态用户能碰到的完整清单——`bekBundled` 为 `true` 时 `LogicSugarSettings.setup(...)` 根本不执行，所以只在 `setup()` 里注册的设置对聚合态用户**不存在**：没有设置行、没有报错、也没有任何入口能改它。提 PR / 宣布功能完成前先回答两件事：

- 新增的设置行 / 调色板条目 / 按钮 / 浮层 / 偏好项，是否在 `LogicSugarSettings.setup(...)` 与 `bekBuildSettings(...)` **两处**都有注册行？只注册一处就是同一个改动里的 bug；若某处确实是刻意不提供的，必须在同一次改动里写进本文与 Neon 侧，而不是只在评审口头说明。只挂在 `bekBuildSettings` 的行同样需要这个理由。
- 它的**默认值**对「够不到这个设置的用户」安全吗？破坏性默认值 + 漏注册行是最坏组合：聚合态用户被静默锁死在那个默认档上。
- 不要为了绕开这一点再自建一个 `@logicsugar.settings` 分类；也不要在设置/安装路径之外分支 `bekBundled`——编译产物、持久化与编辑器行为在两种形态下必须一致。
- 改 `bekBundled` / `bekBuildSettings` 的契约（改名、加行、改行语义）时，与 Neon 仓 `tools/submods.json` 的同步断言放在同一个改动里完成。

反例（`editorConflict`，PR #15）：该设置只在 `LogicSugarSettings.setup(...)` 注册，聚合形态下完全不可达，而它的默认档 `takeover` 会摘掉第三方逻辑编辑器 UI —— 聚合态用户既没有入口切走，也不会收到任何提示。修法只是 `bekBuildSettings(...)` 加一行 + Neon 侧同步断言；这条兼容性要求就是为了不再出现这一类问题。

## 入口与生命周期

入口类 `logicsugar.LogicSugarMod`（`mod.json` 的 `main`），初始化流程：

1. **注册语句**：`registerStatements()` 把 17 种 `SugarStatements` 卡片（`ForBegin` / `WhileBegin` / `SwitchBegin` / `IfBegin` / `Case` / `Default` / `ElseIf` / `Else` / `Break` / `Continue` / `BlockEnd` / `FuncDef` / `FuncCall` / `Return` / `Array` / `Matrix` / `ArrayInit`）加入 `LogicIO.allStatements`；随后注册数据子系统模块（`DataModules.register(new ArrayBulkModule/RecordModule/ContainerModule/BitsetModule/MapModule/SetModule/ListHeapModule/ChainModule())`，同时注册表达式 intrinsic provider），再调用 `SugarStatements.installParsers()` 与 `DataModules.registerParsers()` 向 `LAssembler.customParsers` 注册全部 token 解析器（含 `forend` / `whileend` / `switchend` 三个旧开发版本标记的只读兼容，以及 `record` / `stack` / `queue` / `deque` / `bitset` / `map` / `uset` / `list` / `heap` / `chain` 十张数据声明卡）。整个 `registerStatements()` 由静态 `registered` 守卫，重复 `init()` 不会重复添加卡片或解析器。这是与反编译器、自测共享的唯一注册点。
2. **接管编辑器**：`ClientLoadEvent` 后把 `Vars.ui.logic` 换成 `SugarLogicDialog`（构造函数内使用 `SugarCanvas`）。替换前把旧对话框上除 canvas/buttons 外的子元素（如 MindustryX 的逻辑辅助浮层）按 z 顺序迁移到新对话框。
3. **挂辅助功能**：`BoxSelect.init()`（框选）、`ExprHook.init()`（表达式语句）、`VarDisplayFilter.init()`（隐藏 `__ls_*` 内部变量）、`ProcessorStatus.init()`（处理器状态指示）、`UnitFlags.init()`（单位 flag 叠加），并注册关闭对话框时清空跳转线着色缓存。

## 编译器：Sugar → mlog

`mindustry.logic.SugarCompiler` 是唯一编译入口（`compile(...)` 重载链）。核心不变式：**保存到处理器的永远是原版可解析的 mlog**。

- 结构语句（`ifbegin` / `forbegin` / … / `blockend`）被 lowering 成 `jump` / `op` / 标签注释组合；`SugarStatement.build()` 返回 `NoopI`，结构语句本身不产生指令。
- 函数由 `SugarFunctions.analyze` + `lower` 处理：本地函数（处理器内定义）与库函数走同一条管线。`FuncMode.normal` 生成共享 `@counter` 子程序（函数体 hoist 到程序尾部）；`FuncMode.inline` 按调用点展开副本，编译器临时名带 `__ls_i_<callId>_` 前缀。
- `SwitchStrategy` 决定 `switch` 的下降形态：`auto` 在整数 case、值域跨度 ≤255 时按实际可执行指令成本在比较链与 `@counter` 跳转表之间二选一；`chainOnly` 恒用比较链（与 2.3.1 之前输出逐字节一致）。lowering 之后还有无条件跳转链穿线（`threadAlwaysJumpTargets`，带环检测）。**例外是裸表**：`switchbegin … raw`（恢复出来的手写跳转表）无视策略恒发跳转表，因为它是程序属性而非本机偏好。**`default:`** 卡片是"没有 case 命中"的目标：比较链里是末尾跳转的目标，跳转表里是所有空槽行（带守卫形态下还有两条边界守卫）的目标；每个 switch 至多一张，validation 与标红共用 `SugarFunctions.defaultViolations`。
- **持久化载体（carrier）**：Sugar 源码以 `set __ls_sugar "<base64>"` 载体行存回程序末尾，程序用到的库函数子集以 `set __ls_lib "<base64>"` 一并嵌入（跨机器可重编译）。载体是真实 `set` 语句，能挺过原版 parse/save 往返；单条载体不超过 60000 字符（LParser 字符串 token 上限 65535 UTF 字节以下）。**载体分片**：编码后超限的载荷自动切分为连续编号的多条语句 `set __ls_sugar_1/2/…`（`__ls_lib_N` 同理），每片 ≤60000 字符，restore 侧按"从末尾锚定、向前连续递减到 1"重拼后一次 decode（避免劈开 UTF-8 序列）；≤ 阈值时保持单条形状字节不变。分片行计入指令预算，极端超限时重现旧行为（丢弃超限载体并告警）。v2.0.0 旧程序回退到注释标记块 `# @logic-sugar-v1 begin` / `# @logic-sugar-line ` / `# @logic-sugar-v1 end`。
- 编译器保留前缀 `__ls_` 是用户不可用的命名空间；表达式临时变量用 `_0, _1, …` 栈式编号。
- **载体不执行（entry skip）**：编译产物在 main 末尾统一多一条 `set @counter 0`（`SugarCompiler.entrySkipLine`，配 `hasEntrySkip()` / `withEntrySkip()` / `withoutEntrySkip()`），让紧随其后的几 KB 载体 `set __ls_sugar` 永不执行——否则 MDTX 逻辑面板的值列会把载体当成一条运行中的赋值显示出来。等价性依据：`runOnce()` 在 `@counter` 越界时本就「置 0 执行指令 0」，跳过条不改变任何语义。**这条 skip 是被存储的糖源码的一部分**（随 `# @logic-sugar-line` 一起进载体，编译期不额外 append），因此旧版本重编译这段文本能原样复现，`verifyRestore` 仍然通过；代价是旧版编辑器多显示一行 `set @counter 0`（显示层，不影响数据与再保存），以及**有效指令上限变成 `maxInstructions - 1`**（skip 与载体一起计入末尾的上限检查，顶格程序会抛 `IllegalArgumentException`）。反编译侧 `isEntrySkip` 带位置约束：跳过至多一条 hoist `jump` 之后必须只剩载体行——既不能简化成「必须是最后一条」，也不能要求「其后一定有载体」（`stripGenerated()` 会剥掉载体的程序里它照样在）。设计取舍与跨版本双向实测结论记在 `entrySkipLine` 的 javadoc。**解析上限（源文本超窗口）与现状**：skip 是「拼到糖源码末尾再交给 `LAssembler.read` 解析」，而原版 `LParser` 只解析前 `LExecutor.maxInstructions`(1000) 条语句、其余**静默丢弃**（上限按**语句数**计，注释与空行免费——实测 1000 行注释 + 3 条语句仍解析出 3 条）。糖源码的语句数可以在指令数 ≤1000 的前提下突破 1000（例如 500 个空 `if` 块 = 1000 条语句、只编译出 500 条指令）。窗口一旦关闭就有两种丢失，`compile` 都在 `containsSugar` 早返回**之前**拦住并抛 `IllegalArgumentException`（消息说明是解析上限）：① 头部带糖却没有 skip 落地 —— 载体重新每周期执行，正是 skip 要防的那个 bug；② 头部无糖而整份源码在提升上限下含糖 —— 糖整体落在窗口之后，原实现会把糖文本当成「产物」原样存下，存进处理器的代码原版解析器根本读不了。判 ② 要拿提升到 `libraryInstructionLimit` 的上限重解析整份源码（丢尾巴的正是同一个窗口，不重解析看不见）。**纯 vanilla 的长程序不拦**：那是原版解析器自己的截断，且没有载体需要保活。

## 表达式子系统

`logicsugar.assist.expr` 包（部分思路致谢 mindcode 项目）：

- `ExprCompiler`：表达式字符串 ↔ `op` 语句链的双向转换。临时变量统一 `_0, _1, …`、一次写一次读形成线性链，是逆向重建的前提。
- `ExprStatement`：表达式语句卡片，折叠态显示 `dest = expr`，`write()` 输出 `op` 链文本（保证保存结果仍是标准 mlog），编译错误当场标红。**单行表达式的自描述标记**：`x = 0` / `x = a` / `x = a + b` / `x = cos(a)` 各自只编译出一条指令，在保存文本里与普通 `set`/`op` 积木逐字相同，而 `foldAll` 的单行门槛只对数组 `read`/`write` 放行——不给证据的话，单行表达式卡保存一次、撤销一次就会退化成普通积木。`write()` 因此在展开行之后追加一行注释 `# @ls-expr-card <dest> "<expr>"`（`ExprStatement.cardMarkerPrefix`，表达式按 `escapeQuoted` 转义）；注释被原版 `LParser` 直接忽略，**可执行流、语句条数与 jump 下标都不受影响**，标记随载体（与 `# @logic-sugar-line` 注释块）一起保存，加载时由 `ExprTextImport` 把紧邻它上面的那一行按其记录的 dest/expr 还原成卡片。多行卡片靠 `foldAll` 的 ≥2 门槛折回，单行数组 `read`/`write` 靠数组门槛折回，两者都不写标记（标记只处理"折叠猜不出来"的那一类；一对一替换也保证了语句条数不变）。
- `ExprHook`：把 `ExprStatement` 注入语句列表，并在 `SugarCanvas.load()/save()` 中执行 `foldAll()/unfoldAll()`；折叠/展开全程固定在同一份画布数组注册表上（`ArrayRegistry.enter/restore`），`foldAll` 额外把注册表命中的原版 `read`/`write` 行作为链节点参与折叠（`rebuildAssignment` 折回 `buf[i] = x` 赋值卡）。**展开必须无损**：`unfoldAll` 只在链里每一行都有对应原版卡片时才移除表达式卡（`hasUnmappableLine`），并且**单行卡一律不展开**（`keepsCard`）——单行链在保存文本里本来就只占一条语句，展开没有结构收益，却会因为 `foldAll` 的单行门槛而无法折回；跳过展开后 `write()` 输出的文本与展开逐字相同（`exprCardTest` 钉住这一等价），因此产物与所有下标都不变。
- `ExprTextImport`：文本导入层的表达式语句识别。原版 `LParser` 只按 `tokens[0]` 查表（`LogicIO.read` + `LAssembler.customParsers`），`x = buf[3]` 这类行没有任何解析器认领，会被静默换成 `InvalidStatement`（`noop`）。`SugarCanvas.load()` 先把它一对一换成哨兵 `set __ls_import_N 0`（语句条数不变，标签/jump 下标不受影响），加载完成后把哨兵原位换成 `ExprStatement` 卡，之后完全走 `unfoldAll`/`foldAll` 既有路径——产物与手拖 Expr 卡一致，仍是纯原版 mlog，联机兼容性不变。首 token 已被原版/custom parser 认领的行、含顶层 `;`、字符串内文本、`==` 等比较一律不动。回归见 `exprTextImportTest`（`logicsugar.assist.expr.ExprTextImportSelfTest`）。同一次扫描还负责 `ExprStatement.cardMarkerPrefix` 自描述标记（见上）：标记认领紧邻它上面的那一行，把它换成哨兵并按标记里的 dest/expr 还原成卡片；没有标记的普通 `set`/`op` 文本一律不受影响。回归见 `exprCardTest`（`logicsugar.assist.expr.ExprCardSelfTest`）。
- `ArrayRegistry`：`array` 声明卡的编译期注册表（程序级，静态上下文 enter/restore 传递）。数组是纯 sugar 抽象——卡片 lower 时剥离，下标 `buf[i]` 按声明区间（内存块 + [base, base+size)）换算物理地址发射原版 `read`/`write`；严格口径（编译路径）拒绝重名/同内存块重叠/非法字面量，宽松口径（编辑器路径）供折叠与标红使用。v0 仅支持整数字面量的 base/size；纯原版 mlog 无声明卡时不做数组推断，`read`/`write` 原样保留。
- `ShortCircuitCompiler`：把 `&&` / `||` 谓词下降为条件 `jump`，按控制流顺序发射（不产生先行求值的布尔临时变量）；不依赖任何 Mindustry 类，便于在编译期与反编译恢复两侧复用。`whilebegin exprsc …` 等带 `c` 后缀的解析变体对应"折叠式表达式条件"（collapsed）。
- `RecoveryPredicate`：无依赖的谓词树模型（`EAGER` / `SHORT_CIRCUIT` / `UNKNOWN` 求值方式、loss/score 度量），供恢复代码在触碰游戏 API 之前构建与打分候选。

## 数据子系统（数组批量运算 / 矩阵 / 记录 / 容器 / 位集 / 哈希表 / 集合 / 列表 / 堆 / 链表）

> 玩家向教程（每种结构的声明、函数、转译、复杂度、使用须知）：[高级数据类型教程](tutorials/README.md)。

数据子系统把「内存块上的结构化数据」做成纯编译期抽象：声明卡只是元数据，lower 阶段整体跳过、不产指令；所有运算降级为原版 `read` / `write` / `op` / `funccall` / `jump`，产物仍是原版可解析的 mlog，联机（含自建服）与单机行为一致。

### 框架：ExprIntrinsics + DataModules + 注入函数

- **`ExprIntrinsics`**（`logicsugar.assist.expr`）：表达式函数名 → 原版指令链的展开点。Provider 实现必须放在 `expr` 包（`Node` / `Line` 是 `ExprCompiler` 的包私有类型）。`ExprCompiler` 的 `compileNode(Call)` / `compileNode(Member)` / 成员赋值路径先查 provider，未命中退回普通 `funccall` / sensor 路径。用户 `funcdef` / 库函数同名时优先（`enterUserFunctions` 遮蔽 intrinsic），数组最值用 `array_min` / `array_max`，旧短名 `min` / `max` 仍按实参个数分派（1 参 = 数组运算，2 参 = 原版内置），名字匹配大小写不敏感。
- **`DataModule` / `DataModules`**（`logicsugar.assist.data`）：每个数据结构一个模块（`id()` 去重）。`LogicSugarMod.registerStatements()` 注册全部模块（同时把 `intrinsics()` 注册进 `ExprIntrinsics`）并调用 `DataModules.registerParsers()` 安装声明卡解析器与调色板卡片；`SugarCompiler.compile` 在 `analyze` 之后、`lower` 之前 `DataModules.collectAll(...)` 建立程序级注册表，`finally` 里 `restore()` 清理——配对标记在 `collectAll` 之前置位，任一模块 `collect` 抛异常也会恢复，不把注册表泄漏给下一次编译或编辑器渲染。`markInvalid` 供编辑期标红，`builtinSugar()` 提供注入函数源文本。
- 数据 intrinsic 还通过 `DataModule.PaletteCall` 提供 palette metadata。`DataModules` 统一注册 `datacall <operation> <destination> "<arguments>"`，每个 intrinsic 都可编辑、可持久化；lower 阶段把卡的调用转回既有 `ExprIntrinsics` 链，最终只输出原版 mlog。**一张卡一个结构**：选板从 68 张运算卡收敛为 10 张（同族的卡挨着），卡内按钮切换该结构的运算，切换时把实参重设成该运算的默认形状。**参数元数据不另建表**——`PaletteCall.arguments` 的默认实参串逐位就是参数名，`splitArgs` 做括号感知拆分，UI 与编译路径共用同一套拆分规则；尾部空槽丢弃、中间空槽保留（`array_swap(buf, , j)` 与 `array_swap(buf, j)` 语义不同，宁可如实报参数错误），实参数多于参数量或运算名未知则退回单框。`argumentSlots()` 把判定逻辑外提（`build()` 需要 GL，无头测不了）。`PaletteCall` 同时记录源代码形参默认值和 `returnsValue`：有返回值的卡默认显示 `result = op(args)`，结果写入左侧可编辑变量；无返回值的卡只显示 `op(args)`，lower 时把实现内部的兼容哨兵丢入每个调用专用的 `__ls_*datacall_discard` 变量。当前无返回值的操作是数组原地变换 `array_fill/array_copy/array_sort/array_sort_desc/array_reverse/array_swap`，以及各容器的 `stack_clear/queue_clear/deque_clear/map_clear/set_clear`；其余操作的返回值/失败哨兵均保留并在卡片提示中说明。卡内运算按钮的悬停键是 `logicsugar.hint.datacall.operation`，每组一张卡对应一个 `lst.datacall.group.<族>` 说明；实参输入框的灰色占位取 `logicsugar.datacall.arg.<参数名>`（全部运算共用的 19 个词）。按模块/结构族分别进入 Stack/Queue/Deque/Array Algorithms/Bitset/Hash Map/Set/List/Heap/Linked List Operations 分类，避免把模块细节硬编码在编译器中；这些分组标签与**选板栏**是两件事，见「调色板分类」。
- **注入函数**：模块把循环型 / 写内存型操作写成 `funcdef __ls_builtin_*`，由 `SugarCompiler` 经 `SugarFunctions.withBuiltins` 并入本次编译的 `LibraryIndex`。normal 模式全程序共享一份子程序，未使用不进产物；`extractLibrarySource` 只处理用户库文本，内置函数不会进入 `__ls_lib` 载体、也不会出现在用户函数库。inline 模式按调用点展开函数体。
- **内置函数体版本与旧存档断代（重要）**：注入函数体是**编译期烘焙进产物**的普通 mlog，会随处理器一起保存。`SugarCompiler.verifyRestore` 用 `matchesStoredStream` 把载体里的 sugar 重新编译后与存档指令流**逐条比对**（`executableStream` 剥掉 carrier 后经 `read → write` 归一化，其中包含 hoist 的函数体），任何一行不同都会判失败。因此**改动任一 `__ls_builtin_*` 的函数体，都会让旧版本保存过、且用过该内置的处理器重开时落到 vanilla 视图**：`SugarLogicDialog` 回退到 `SugarDecompiler` 推断，而 `array` / `sortasc` 这类只活在载体里的卡片不会被凭空恢复（既不能进载体、也不能被推断 → 按规则显示原版）。已确认的断代：`sortasc` / `sortdesc` 由插入排序改为希尔排序（`ArrayBulkIntrinsics.sort()`，函数体 27 → 33 条指令）；`indexof` 为「命中即停」新增一条跳出循环的 `jump`；`copy` 修正读/写基址交叉（`ArrayBulkIntrinsics.copy()` / `indexof()`，见 `dataRuntimeTest` 的对应用例）。这类改动属于产品决策级的兼容性变更：要么接受断代并在教程与发布说明写明，要么给内置函数体做版本化、让 `verifyRestore` 额外尝试旧 body（框架级改动，成本高于改算法本身）。

### 语法与降级

| 结构 | 声明卡（token 定长，空槽 `~`） | 表达式用法 | 降级目标 |
| --- | --- | --- | --- |
| 数组 | `array <name> <memory> <base> <size>` | `buf[i]`、`len(buf)` | `read` / `write`；`len` 折叠为 `size` |
| 数组填充 | 复用 `array` | `array_fill(buf, value)`（独立积木） | 类似 C++ `fill`：把声明区间整体写成同一值，降级到 `__ls_builtin_arrfill` |
| 旧数组初始化（兼容） | `arrayinit <name> <v0>…<v7>` | 不再出现在新增面板 | 旧 carrier 仍可解析、显示和原样降级，token 数不变 |
| 矩阵 | `matrix <name> <memory> <base> <rows> <cols>` | `m[i][j]` 读 / 写 | 地址 = `base + i*cols + j`；字面量编译期折叠，越界报错 |
| 批量数组运算 | 复用 `array` / `matrix` | `array_sum` `array_avg` `array_min` `array_max` `array_count` `array_find` `array_fill` `array_copy` `array_sort` `array_sort_desc` `array_reverse` `array_replace` `array_swap` `array_lower_bound` | 注入函数 `__ls_builtin_arr*` |
| 记录 | `record <name> <f1>…<f8>` | `p.f1` 读 / `p.f1 = expr` 写 | 普通变量 `<name>_<field>` |
| 栈 | `stack <name> <memory> <base> <size>` | `stack_push` `stack_pop` `stack_top` `stack_size` `stack_clear` | `read` / `write` + `__ls_stk_<name>_top` |
| 队列 | `queue <name> <memory> <base> <size>` | `queue_push` `queue_pop` `queue_front` `queue_size` `queue_clear` | `read` / `write` + `__ls_que_<name>_head/_tail/_count` |
| 双端队列 | `deque <name> <memory> <base> <size>` | `deque_push_front` `deque_push_back` `deque_pop_front` `deque_pop_back` `deque_front` `deque_back` `deque_size` `deque_clear` | 与队列同一环形缓冲；前端 push 走 `__ls_builtin_deqpushf`，后端 push 复用队列 builtin；状态 `__ls_deq_<name>_head/_tail/_count` |
| 位集 | `bitset <name> <memory> <base> <words>` | `bitset_set` `bitset_reset` `bitset_test` `bitset_count` | 每 word 64 位，`and` / `or` / `shl` / `shr` + `read` / `write` |
| 哈希表 | `map <name> <memory> <base> <capacity>` | `map_set` `map_get` `map_contains` `map_erase` `map_size` `map_clear` | 开放寻址；键区 `[base, base+capacity)`、值区 `[base+capacity, base+2*capacity)`；`hash = abs(key) % capacity`，线性探测 |
| 集合 | `uset <name> <memory> <base> <capacity>` | `set_add` `set_contains` `set_remove` `set_size` `set_clear` | 只占用键区 `[base, base+capacity)`，探测与哈希表相同；token 不能是 `set`（原版 opcode） |
| 列表 | `list <name> <memory> <base> <size>` | `vector_push_back` `vector_at` `vector_set` `vector_insert` `vector_erase` `vector_find` `vector_size` | `read` / `write` + `__ls_lst_<name>_count` |
| 堆（小顶） | `heap <name> <memory> <base> <size>` | `heap_push` `heap_pop` `heap_size` | `read` / `write` + `__ls_hep_<name>_count` |
| 链表 | `chain <name> <memory> <base> <size>` | `chain_init` `chain_clear` `chain_alloc` `chain_free` `chain_get` `chain_set` `chain_next` `chain_link` `chain_set_head` `chain_head` `chain_len` | 节点 i 的值槽 `base+2*i`、next 槽 `base+2*i+1`（`next = -1` 为链尾）；`read` / `write` + `__ls_chn_<name>_head/_free` |

- **getter 语法糖（只读）**：已声明结构在 Expr 模式下可用下标/方法写法替代 getter intrinsic——`list` 的 `l[i]` / `l.get(i)`，`list`/`heap` 的 `.size()`/`.length()`/`.count()`，`stack` 的 `.top()`/`.peek()`，`queue` 的 `.front()`/`.peek()`，`deque` 的 `.front()`/`.back()`，`bitset` 的 `b[i]`/`.test(i)`/`.get(i)`，`chain` 的 `c[i]`/`.get(i)`/`.head()`。实现走 `ExprIntrinsics.Provider` 的 `kindOf` / `methodIntrinsic` / `indexIntrinsic` 扩展点，由 `compileNode(Method)` / `compileNode(Index)` 分派；语义与对应 intrinsic 完全一致，已声明数组优先于同名结构的 `[i]`。映射覆盖只读 getter，既包括无注入函数的 `vector_at` / `stack_top` / `bitset_test` / `chain_get` / `chain_head` / `*size`，也包括走注入函数的 `map_get` / `set_contains` / `vector_find` / `bitset_count` / `chain_next` / `chain_len`。`SugarCompiler.compile` 在 `analyze` 之前用 `DataModules.declaredKinds(statements)` 安装轻量声明表；`collectCallNodes` 据此把方法/下标解析成 intrinsic 并发出 root-intrinsic `CallSite`，`registerExprCalls` 再用 `calleesOfRoot` 登记 `__ls_builtin_*` 可达性（否则 normal 模式会漏 hoist）。下标糖只读：`l[i] = v` 显式报编译错误并提示使用 `vector_set` / `bitset_set` / `chain_set`，避免静默降级为 `write <v> l <i>`。
- **容量检查**：容量优先取**处理器当前链接的方块**——解析 `memory` 变量到 `LogicBuild.optionalLink`，命中原版 `MemoryBlock` 就用它的真实 `memoryCapacity`（模组内存块、world-cell 都由此得到正确值）。解析不到时才回落按名字猜的 `<cellN>` = 64、`<bankN>` / `<worldN>` = 512（大小写不敏感）；解析到方块但不是内存块则确定不限制、跳过检查。`base+size`（矩阵为 `base+rows*cols`，哈希表为 `base+2*capacity`，集合为 `base+capacity`）超容量编译期报错；猜出来的容量在错误信息里明确标注 `inferred from the variable name`。名字不是证据：原版 `LogicBlock.getLinkName` 取方块名最后一个 `-` 之后的部分，所以 **world-cell（512 格）的变量名同样是 `cellN`**，纯按名字判断会把它错限成 64。`ArrayRegistry.capacityOf` / `capacitySource` 是唯一口径，各模块不得自己读 `memoryCapacity`。
- **越界断言**：仅 `AssertEmit=emit` 的调试构建下、下标为非常量时，在 `read` / `write` 前发射 `assertBounds`（复用 `SugarAsserts` 线格式）；`strip` 模式不发射。数组/矩阵字面量越界始终是编译错误。
- **空容器语义**：pop / peek 在空时返回 NaN（`op div <tmp> 0 0` 或越界 `read`）；可失败操作（push / append / insert / delete / set / free）失败时按 v5 API 统一返回 **-1**，且不写入（`vector_push_back` / `vector_insert` / `heap_push` / `vector_set` / `chain_set` / `chain_link` / `chain_free` / `map_erase` / `set_remove` 同理；`bitset_set` / `bitset_reset` 越界不写入但恒返回 1，是无结果卡，见 [api-v5.md](api-v5.md) 第 3、4 节）；`vector_at` 越界返回 NaN，`vector_erase` 越界返回 NaN、成功返回被删除值，`vector_find` 未找到返回 -1，`map_get` 未命中返回 NaN，`map_set` / `set_add` 在 NaN/±Inf 键上返回 -1，查询类 `map_contains` / `set_contains` / `bitset_test` 恒为 0/1（`bitset_test` 越界也是 0）；链表 `chain_get` 越界返回 NaN，`chain_next` 越界返回 -1，`chain_alloc` 在空闲链为空时返回 -1，`chain_len` 空链返回 0。`array_lower_bound` 在升序数组上未命中返回 -1。
- **保留命名空间**：隐藏状态变量与注入函数名都以 `__ls_` 开头（`VarDisplayFilter` 自动隐藏，用户声明名使用该前缀会被模块拒绝）。记录字段变量 `<name>_<field>` 是普通用户变量，不隐藏、可调试。

### 单机 / 联机与 1000 指令约束

- 声明卡不产指令，全部运算都是原版指令：产物在任何原版客户端可解析、可运行，联机（含自建服）与单机一致；数据子系统不引入任何 `AssertEmit` 例外。
- 1000 条上限沿用 `SugarCompiler` 既有检查（lowered 指令 + 载体行一起计数），新功能不绕过。循环型操作（push / sort / find / 哈希探测等）在 normal 模式做成共享 `funcdef`，指令预算与调用点数量线性、与结构数量无关；inline 模式会复制函数体，长程序需切回 normal（编译器在超限报错里提示）。
- **函数库文件不受 1000 条限制**：`functions.txt` 不是保存到处理器的程序，`SugarFunctions.libraryInstructionLimit`（当前 10000 条语句）是库文件自身的安全上限。处理器仍然只能保存 ≤1000 条；调用库函数时只通过 `extractLibrarySource` 嵌入用到的子集，子集与主程序一起计入 1000 条预算。

### 已知限制

- **跨模块校验未统一**：每个模块只严格校验「自己声明的结构 + `array`/`matrix`」。不同模块之间（如 `stack` 与 `list` 共用同一内存块且区间重叠，或跨结构重名）不做统一校验，需要用户自行避免；统一程序级名字/区间表需要改各模块的 `collect` 口径，属后续工作。
- **哈希表 / 集合**：不支持字符串键；键比较沿用原版 `equal` 的 1e-6 容差；首次使用前必须调用 `map_clear(m)` / `set_clear(s)`（未初始化槽读回数字 0，会被当作「已占用且 key = 0」）；删除是墓碑策略——只把 key 槽写成 NaN，探测是整表环形扫描因此墓碑不会截断探测链。集合只占用 `capacity` 个槽，没有 value 区。
- **链表**：首次使用前必须调用一次 `chain_init(c)` / `chain_clear(c)`——未赋值变量读作 0，不初始化直接 `chain_alloc` 会把 0 号节点当成空闲节点；`chain_free` 不检测重复释放，把已在空闲链上的节点再次释放会让空闲链成环；`chain_len` / `chain_free` 的遍历在用户手工 `chain_link` 造出环时不会终止，链表不变量（next 槽只由本模块写入、指向合法下标或 -1）由使用者维护。
- **状态变量不随存档持久化**：隐藏计数是普通 mlog 变量，处理器代码重新载入（存档往返 / 重编译 / 换处理器）后归零，而内存块内容保留；跨存档运行的结构需要在程序开头显式重建状态（`stack_clear` / `queue_clear` / `deque_clear` / 重新初始化内存或计数）。
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
- **行数上限（当前 10000 条语句）**：`LAssembler.read` 会静默截断在 `LExecutor.maxInstructions`，所以库文本统一走 `SugarFunctions.readLibrary`（解析期间临时抬高、`finally` 还原，处理器的 1000 条预算不受影响）；库编辑会话里原版 `LCanvas.load` 也走 `SugarFunctions.withLibraryLimit`。`FunctionLibrary.save` 与 `FunctionLibraryDialog.editInProcessor` 用 `libraryOverLimit` 在超限时明确拒绝，避免静默丢尾部；编辑器预算条显示「库源码行数 / 上限」。库文本整体存在本地 `functions.txt`，进入 `__ls_lib` 载体的仍只是被调用的函数子集。
- **载体子集 vs. 本地库的合并（`SugarCompiler.effectiveLibrary`）**：`__ls_lib` 只含**保存时真正内联过**的子集，所以重开处理器时要把它与本地 `functions.txt` 里其余函数合并成本次会话的有效库（否则用户新加一个「以前没调用过」的库函数调用就会撞上 `calls undefined function`）。`funcdef`/`begin`/`jump` 的 `destIndex` 是**目标库文本里的绝对语句下标**（"must point to a block end below it"），因此追加在载体子集之后的那一段必须整体平移前缀语句数——`SugarFunctions.extractLibrarySource(text, usedNames, outBase)` 的第三个参数就是这个前缀长度，`effectiveLibrary` 用它传入已写入的语句数。漏掉这次 rebase 的后果不是报错而是**静默丢函数**：`buildLibrary` 抛出的 "must point to a block end below it" 会被 `sanitizedLibrary` 的抢救路径吞掉，被追加的函数从有效库里消失。`SugarCompilerSelfTest.libraryMergeRebasesAppendedFunctions` 钉住这条路径（旧用例只传空本地库，覆盖不到）。

## 重建（reconstruction）：打开已保存程序

玩家重开处理器时，编辑器要把已存的原版 mlog 尽量还原成当初的 Sugar 积木。这是功能是否「完成」的一部分：**只要新增积木/功能会改变编译出的原版 mlog，或修改现有 lowering 会改变产物，就必须在同一次改动里补齐对应的从原版代码重建逻辑；只改 lowering 不算完成。** 两条路径都要过安全门；失败方向永远是「多显示原版代码」。

```text
打开处理器
  │
  ├─ 1. 载体还原（优先、无损）
  │     decode __ls_sugar / __ls_lib
  │     按 begin/blockend 嵌套重配对过期 destIndex（跳转注释）
  │     再 compile，与可执行 mlog 比对（剥掉载体/标记块）
  │     通过 → 显示 Sugar（含数据声明卡）
  │
  └─ 2. 反编译推断（载体缺失或与指令不一致）
        剥掉陈旧载体 → CFG 分诊 → 恢复 if/for/while/switch/函数
        重编译比对通过才采用；否则 flat 原版
        不发明 array/stack/… 声明卡，不把 __ls_builtin_* 当成用户函数
```

### 路径 1：载体还原

`SugarCompiler.restore` + `verifyRestore`。`ifbegin`/`forbegin`/`whilebegin`/`switchbegin`/`funcdef` 行尾的 `destIndex` 只是跳转注释：嵌套仍然完好、注释指向越界或交叉时，按最内层 `blockend` 重配对后再编译。`matchesStoredStream` 比较的是剥掉载体之后的规范化指令流，所以注释本身的 Base64 差异不会误判「被外部改过」。

数据子系统的声明卡（`array` / `matrix` / `record` / `stack` / `queue` / `deque` / `bitset` / `map` / `uset` / `list` / `heap` / `chain`）**只存在于载体里的 Sugar 源码**，lowering 时整张剥离，原版 mlog 里看不到它们。因此：

- 有载体且验证通过 → 声明卡和表达式一并回来（编辑器再 `foldAll` 折回 `buf[i]` / `stack_push` 等）。
- 没有载体（别人用手写 mlog、或载体被删）→ **不猜测**声明卡，只显示 `read`/`write`/`op`/`jump`。注入函数 `__ls_builtin_*` 的蹦床也不得恢复成用户 `funcdef`。

反编译预检必须走 `LogicSugarMod.registerStatements()`（模块 + 解析器），否则载体里的声明卡会被当成未知行。

### 路径 2：反编译推断

`mindustry.logic.SugarDecompiler` 把已存的 mlog 反向呈现为 Sugar 视图，流程：

1. 先按原版规则解析输入；带有效载体（`SugarCompiler.isSugarProgram` + `verifyRestore`）的程序优先走载体无损路径。
2. 载体过期（程序被外部编辑过）时剥掉载体变量，在裸指令流上重试推断。
3. **CFG 分诊**：用 `MlogCFG`（零依赖控制流图 IR）扫描可达指令，出现"已知安全形状"之外的动态 `@counter` 写入（跳表派发 `op add @counter @counter x` 与 `set @counter __ls_*` 蹦床除外）即认定程序不可静态恢复，直接保留 vanilla 并注明位置——这类程序本来就会验证失败，分诊只是更快、更明确。
4. 结构恢复：`recoverFunctions()` + `parseMain()` 生成候选 Sugar 源。函数区先过静态验证（区间外 jump 不得跳入、区间内 jump 不得跳出；嵌套调用前导跳向其他函数入口的 always 跳转豁免）。同一位置可能有多个候选帧（`tryFrames`），按 `RecoveryPredicate` 的 loss 排序取最优；贪心选择验证失败时，`backtrack()` 会在记录的决策点上逐个提升次优候选重试（有次数预算），每次仍走同一道门。
5. **安全门（必须保留）**：候选先重新编译，再与输入的规范化指令流比对，比对通过才允许返回恢复结果。验证矩阵覆盖 FuncMode × SwitchStrategy 全部组合（`verify`）——程序可能在另一台机器、另一个 switch 策略设置下保存，不能因本机设置不同而误判。任何识别不了的内容回退为原样保留的 vanilla 语句（`matchedMode = "flat"`）。

**门的两次归一化（手写/第三方程序能开成 Sugar 的前提）**：LogicSugar 从未保存过的程序既没有载体，也没有本编译器产物必然带的两样东西；两者原先都缺，导致这类程序无论识别得多好都只能回落 vanilla（2026-09-25 报的 655 条跳转表程序；夹具 `test/fixtures/realworld-jump-table.mlog`）：

- **入口 skip 的两个纪元**：`compile` 会在末尾补 `set @counter 0`，于是任何含糖的候选都比输入多一条指令。`verify` 现在对每个候选按"有无 skip"各编译一次，与 `SugarCompiler.verifyLowering` 对存量存档的做法完全一致（公开入口 `compileWithoutEntrySkip`，只有门用它）。恢复后的视图保存时仍会补上 skip —— 这是既定行为，且语义等价（跑出末尾本来就回到 0）。
- **跳转穿线**：`compile` 会跑 `threadAlwaysJumpTargets`，把 `jump A always` 改写到 A 的链尾。那一趟是**按标签**读链的，而手写 mlog 用指令下标寻址、一个标签都没有，于是旧的比对目标 `threadAlwaysJumpTargets(original)` 恰恰在需要它的程序上是空操作。`SugarCompiler.threadNumericJumpTargets` 把同一套不动点搬到语句下标上（只认无条件跳转、成环保持原目标、行结构与指令数不变），`verify` 用它作为比对目标。

两次归一化都不放松门：它们产出的指令流与输入行为完全一致，且都是编译器本来就会对自己产物做的变换。

**但门修好不等于能被调用**：`verifyRestore` 开头就是 `if(!hasSugarCarrier(code)) return true;`——没有载体就没有"待验证的载体"，于是它对从未被 LogicSugar 保存过的程序**恒返回 true**；`SugarLogicDialog.show` 把这当成"载体可信"，直接载入原版本体，反编译器只在"有载体但验证失败"时才被叫到，也就是**对这类程序永远不会被叫到**。所以门修好后用户看到的仍是 251 张裸 jump 卡。

因此开屏决策被抽成 `SugarDecompiler.openingSource(code, privileged, librarySession)`，返回要载入的源码与来源模式：`stored`（载体/旧标记块/函数库文本，原样载入）、`inferred`（验证过的推断：弹恢复提示并保留 Original 视图）、`raw`（原样载入代码）。没有可信载体时一律先跑推断，且只对处理器程序跑（函数库文本是糖源码，不是程序）。它是**纯粹静态方法**：这个 bug 就长在无头测试够不到的 UI 代码里，现在 `decompileTest` 的 `editorOpensHandWrittenProgramsAsSugar` 在两种编辑器权限下都钉住该决策（普通处理器编辑时 `privileged == false`，而恢复测试习惯传 `true`）。

失败方向永远是"多显示原版代码"，绝不改写未知程序。新增恢复模式（跳转表、短路谓词、新数据结构）一律放在这道门之后；新功能若既不能进载体、也不能被推断，就要在文档写明「重开只显示原版」。

`reconstructionTest` 钉住：过期 destIndex 的世界处理器样例走载体还原、数据声明卡随载体回来、剥掉载体后不发明 `stack`/`funcdef __ls_builtin_*`。`reconstructionMatrixTest` 用 179 个 fixture / 1106 个 gate 断言覆盖当前全部控制积木、全部声明卡、全部 `datacall` 操作和断言/调试卡：每个新积木至少补一个 carrier fixture，可推断的新控制流形状还要补 inference fixture；矩阵自动检查每个已注册 `datacall` 操作都有 fixture，且必须保持 100+，不得只改数字。

**无边界跳转表（`switchbegin … raw`）与 `default` 分支**：手写 `@counter` 跳转表没有边界守卫，就是 `op add @counter @counter <v>` 后跟每条槽位一条无条件跳转行。用编译器的*带守卫*跳转表去还原它会多出两条指令并夹紧越界值，等于偷偷改写程序，所以形态写进源码：

- `switchbegin <切换值> <dest> raw`（可选第 4 个 token；缺省即带守卫）只发射派发 + `span` 条槽位行，且**忽略 `SwitchStrategy`**——形态是程序属性，这也正是门的策略矩阵能接受它的原因。非法裸表（非整数 case、跨度超 `MAX_TABLE_SPAN`）直接编译报错。
- `default:` 是 switch 自己的"没有 case 命中"卡片：比较链里它是末尾跳转的目标；跳转表里它是所有空槽行的目标，带守卫形态下边界守卫也指向它（越界值落在这里）。每个 switch 至多一个、必须在 switch 内；`defaultViolations` 是编译器、编辑器标红与函数库构建共用的一条规则。
- 裸表推断（`tryBareSwitchTable`）：槽位 *k* 直接寻址第 *k* 行，所以 case 值就是行号、跨度从 0 开始；行的目标按无条件跳转链（作者自己的穿线）读取。目标落在体区之外的行是空槽，它们必须同指一处，该处成为 `default`——并且必须落在**已存在**的指令上（其自身跳转链终于同一处），否则重编译出的空槽行会落到程序从未有过的跳转上。跨度两端若正好是空槽，就用一个 case 标签钉住（标签与 default 共位，编译出的行完全相同）。任何不吻合都返回 null，视图保持 vanilla。
- 带守卫跳转表的推断同样恢复 `default`：守卫与空槽行落在 switch *内部*的体上而不是出口，此时 switch 真正的结尾是各 case 体 break 跳转的目标（`switchEndBeyond`）。两种读法都作为候选给出——体里跳出 switch 的跳转在这一层与 default 无法区分——由门裁决，与其余恢复逻辑同一套做法。

短路守卫恢复（`tryShortCircuitFrames`）是这套机制的核心用户：`ShortCircuitCompiler` 的 lowering 是若干 `[条件 jump, fallback jump]` 原子对的连续拼接（内部续接标签都落在原子对起点），守卫解析器从对的目标关系重建布尔树（`parseGuardTree`，带换目标环检测的备忘递归），为同一片守卫区域同时给出 `if` / `while` / `for` 候选。由此单原子守卫、顶层 `!`、任意嵌套 `&&`/`||` 树以及 `whilebegin`/`forbegin` 的 `exprsc` 条件都能恢复，不再限于固定四指令布局。体内跳回 while 守卫头的 always 跳转就是 `continue` 的 lowering 形状，由循环上下文恢复为 `continue` 语句。

辅助组件：`MlogCFG`（`cfgTest`）是反编译器共享的 CFG/数据流只读视图；`MlogLint`（`lintTest`，Bang logic_lint 风格）是 advisory 的编译后 MLog 静态检查器（未知 op、参数个数、对字面量赋值、自/越界跳转等，规则事实全部转录自 Mindustry-master 源码），当前独立于编译管线，供工具与测试使用。

## 跨类加载器访问约束（继承自 AGENTS.md）

运行时本模组类经 mod class loader 加载，`mindustry.logic.*` 游戏类在 app loader——同包名、**不同运行时包**：

- 游戏类的 `protected` / 包私有成员（`LStatement.field`、`LogicDialog.privileged` 等）只能：① 在本模组的子类实例方法内访问（`SugarStatement.fieldsHint` / `addCompactOp` 即此模式）；② 经 `Field.setAccessible(true)` 反射访问（`SugarLogicDialog.privilegedField` 是既定范式）。
- 静态辅助方法触碰这些成员**能编译通过**，运行时 UI 渲染时才抛 `IllegalAccessError`。
- 缓解策略分级：编辑器赖以工作的字段用硬反射（无降级模式）；锦上添花的功能字段用 `optionalField`/`optionalMethod`（`SugarCanvas`），上游改名时功能退化而不是整个编辑器崩溃。
- `crossLoaderTest` 以 child-first 加载器无头复现该拓扑，防止模式回退。

## 编辑器接管与共存（`logicsugar.editorConflict` 四档）

逻辑编辑器是**一个全局引用**（`Vars.ui.logic`），而扩展 `LogicDialog` 的模组都可以替换它，于是「两个模组只能有一个生效」。本模组不再假设「不是我的就替换」，而是显式判定当前 owner：

- `LogicSugarMod.classify(LogicDialog)` 返回 `EditorOwner`：`null` 与游戏自带的 `LogicDialog` 都是 `vanilla`（可安全替换），自家 `SugarLogicDialog` **及其子类**是 `sugar`，其余 `LogicDialog` 子类一律 `foreign`——把 foreign 误判成 sugar 正是旧实现的失效点。
- **安装顺序是确定的**：对方模组（如 逻辑工具）在**构造函数**里注册 `ClientLoadEvent` 监听，本模组在 `init()` 里注册，而 `Core.app.post` 是 FIFO ⇒ 对方总是先安装，本模组总是看到一个外来对话框。旧守卫只拿 `SugarLogicDialog` 比较，别的模组不可能是它，于是它替换掉对方对话框、把对方面板搬到自己的画布上，而那些面板仍指向已脱离的实例。
- 四档 `logicsugar.editorConflict`：`ask`(默认) / `takeover` / `stepaside` / `coexist`，`EditorConflict.parse` 大小写不敏感，缺失/空/未知值一律回落 `ask`（**绝不回落到「编辑器被停用」**）。默认档不是 `takeover` 是因为它带不可逆的副作用：接管走 `replaceEditor(foreign, false)`，不搬运对方的子元素，于是对方模组的面板此后指向被拆掉的实例。用户没做选择时不该发生这种事。设置按钮走单个 `next()` 遍历，`editorConflictTest` 断言一圈恰好访问全部状态并回到默认档——漏一个状态就是用户够不到的档。设置键 `logicsugar.editorConflict` 决定 `setting.<key>.name` 能否解析，改名会静默丢标题。切换后立即生效，不需要重启。
- **`ask` 档**（默认）：启动时弹一次选择框，三个答案（用 LogicSugar / 保留对方 / 两者共存）都要有接线；**点掉弹窗不答 = 让位**，因为「没做选择」不能落进破坏性的接管。设置页的聚合表单（`bekBuildSettings`）也必须带上这一行、且与独立设置项用同一个默认值——聚合表单曾经默认接管，两处默认值不一致时用户看到的默认档取决于从哪个入口进设置。回答不写回设置：设置仍停在 `ask`，所以下次启动还会问，答一次只对本次会话生效。
- **`coexist` 档**（`mindustry.logic.SugarCoexist`）：保留对方整套编辑器界面，把 LogicSugar 的画布**跑在对方的编辑器里**。需要跨 classloader 反射读写 `LCanvas` / `LogicDialog` 的包级字段（`Field.setAccessible(true)` 可跨 loader 生效），统一走 `SugarCoexist.field(Class,String)`；同时把 MDTX 的逻辑辅助面板重新绑定到新画布上，对方的面板照常可用。放置失败时退回接管（`logicsugar.conflict.coexistfailed`）。
- **跑在别人的关闭路径上**：`SugarCoexist.push` 挂在对方对话框关闭时的原版 `hidden(...)` 出口上，因此必须捕 `RuntimeException`、绝不逃逸——对方的关闭流程不该被本模组打断；退出共存时要把对方的画布还原回去。
- **共存的残余风险**：① **会话中途切档（已加保护）**——若在对话框**正开着**时换掉画布，本次会话的保存回调仍是对方的裸 consumer（`arm()` 只在当次 `show()` 时 `dialog.canvas` 已是 `CoexistCanvas` 才包装），于是关闭时 `consumer.get(canvas.save())` 会写入我们**空的**画布 → 空程序。`SugarCoexist.install()` 现在遇到 `dialog.isShown()` 直接返回 `false`（调用方按既有约定回落接管档并留一条 warn），不再只靠「进设置页必须先关掉逻辑编辑器」这条 UI 流程兜底。② **第三方在构造期缓存画布（未闭环，需真机验证）**——`CoexistCanvas` 把原画布存进 `original` 字段，第三方浮层面板若在构造时抓住那个 `LCanvas`，此后 `captured.save()` 拿到的是从未被 `load` 过的空画布 → 把空程序写回处理器；目前只反射重绑了 MindustryX 的 `LogicSupport`，对其它面板没有通用答案（哪些面板在构造期缓存画布、哪些每帧现读，取决于对方 mod 的实现，只能实测）。③ **加载顺序晚于本模组 `Core.app.post` 的第三方编辑器**——`installEditor` 只在 `ClientLoadEvent` 后跑一次，之后没有观测点；若对方的安装晚于本模组，`Vars.ui.logic` 会变成对方的对话框，而糖语句卡仍留在 `LogicIO.allStatements` 里（插得进、编译不了）。对「逻辑工具」的现有顺序有效（其监听在构造函数注册，早于本模组 `init()`）。

## 编辑器辅助功能

| 功能 | 类 | 要点 |
| --- | --- | --- |
| 框选/批量操作 | `assist.BoxSelect` + `BoxSelectDragPolicy` | capture 监听器事件驱动；拖动阈值为纯函数（8px slop、移动端 430ms 长按）便于自测 |
| 跨处理器剪贴板 | `assist.StatementClipboard` | 编辑菜单「复制选区 / 粘贴选区」，Ctrl+C/V 驱动同一实现。**剪贴板放糖源码而不是编译后的 mlog**，片段落进另一个处理器后仍可继续编辑；唯一必须区别对待的是 `jump`——跨程序时旧的数字目标是另一程序的指令下标，因此复制与粘贴**双侧拒绝**。块配对由 `pairBlockEnds` 在插入前校验，之后每帧 `syncStatementIndices` 自愈。全程只有语句与字符串，无画布依赖（`statementClipboardTest` 无头跑）。快捷键只能轮询不能事件驱动：`UI.update()` 会把焦点清成 `null`，挂在对话框上的 capture 监听器再也收不到，而 arc 的 `handle()` 不停止冒泡、`TextField` 也保护不了自己；两件事由 `Core.scene.hasField()` 一次问清（原版无 Ctrl+C/V 键位） |
| 提示折行 | `assist.TextWrap` + `assist.SugarTooltip` | arc 的 `Tooltip` 只把容器**位置**夹进舞台，比屏幕宽的容器仍会两边溢出 ⇒ 提前把**文字**折行（按 `min(屏宽×0.5, 560 design)` 预折、resize 时重折）。规则是纯函数（测量函数可替换，`textWrapTest` 用字符数精确断言）：只在空格断、超长单词硬断不丢字符、markup 标签绝不拆开、幂等 |
| 跳转线着色 | `assist.JumpLineColor` | 按目标着色三模式：关闭 / 分散色 / 积木色 |
| @counter 指示线 | `mindustry.logic.CounterJumpOverlay` + `assist.CounterJumpIndex` + `SugarCompiler.compileRecorded` | 写入 `@counter` 的积木（set 的 `@counter = N`、op 的 `+=` / `-=`、Expr 卡）在**左侧**画类 Jump 的镜像跳转线指向目标积木，位置在结构引导线**更外侧**。纯展示，不改保存产物，因此不受联机门禁限制。见下节 |
| 隐藏内部变量 | `assist.VarDisplayFilter` | 过滤 MindustryX 变量浏览器里的 `__ls_*` 与 `_N`；只动展示用的 `allVars`，绝不碰 `executor.vars`（`sync` 指令的索引空间）；原版无 `allVars`，自动不生效 |
| 复制变量/打印缓冲 | `assist.VarClipboard` | 全精度 TSV 变量导出（按名排序）+ 打印缓冲；executor 经反射读取，失败则不显示入口。**入口在编辑菜单，不在底部按钮行**——原先两个固定宽按钮正是把底栏顶出窄窗口的原因，能力由 `SugarLogicDialog.installInspectionCopy()` 装配（旧的 public `VarClipboard.addButtons(Table, LogicDialog)` 已移除，能力迁到私有装配点） |
| 处理器状态指示 | `assist.ProcessorStatus` | drawOver 分帧轮询全图处理器（`Groups.build`，视野外按 hitbox 裁剪）：停止显示「已停在第 N 条」、长 wait 画进度圆环、断言失败显示消息；扫描预算按帧时长换算（`min(delta*60,5) × 每帧扫描数`，低帧率不爆发）；设置三滑杆（阈值 0 关闭 / 每帧扫描数 1–5000 档位 / 警告特效）+ 断点三开关（禁用断点 / 断言失败即断点 / 断点分离视角） |
| 单位 flag 显示 | `assist.UnitFlags` | 设置可选；drawOver 遍历 `Groups.unit`，在单位正上方绘制逻辑 `flag`。默认使用红色；打开 `logicsugar.colorizeUnitFlags` 后，不同 flag 按首次遇到顺序优先使用 10 种高对比度颜色，超出后分配高饱和度随机色；默认 0 / 非有限值不显示，视野外与迷雾中的单位跳过。纯展示，不改保存产物 |
| 结构引导线 | `SugarCanvas.StructureController` | 块结构竖线与折叠；`load()` 后必须重装引导层 |
| 编辑期标红 | `SugarCanvas.invalidSignature()` | 标红刷新走**签名门控**：`SugarCanvas` 比较语句的 `invalidSignature()` 是否变化来决定重标，不再按 `if`/`while`/`for` 显式列 `conditionExpr`——原实现漏掉声明卡与运算卡，改字段后不重标红；新增卡种从此不需要再改 `SugarCanvas` |
| 撤销/重做 | `assist.EditHistory` + `SugarLogicDialog` | 快照栈（最多 80 层）记录 `canvas.save()`；桌面 Ctrl+Z / Ctrl+Y，移动端底部 Undo/Redo 按钮。纯编辑器状态，不改保存产物 |

### @counter 指示线（左侧镜像跳转线）

原版 `jump` 卡片的跳转按钮在卡片**最右侧**（`StatementElem` 顶行：语句名 → `add().growX()` → 地址标签 → 操作按钮 → `JumpStatement` 的 `JumpButton`），`LCanvas.JumpCurve` 的曲线从目标积木折回源积木，所以原版跳转线整体在右侧。写 `@counter` 与 `jump` 在运行时是同一件事（`LExecutor.runOnce()` 先读后自增：`instructions[(int)(counter.numval++)].run(this)`，越界或负数在下一轮被重置为 0 ⇒ `set @counter N` 之后从指令 N 继续执行），因此本功能在**左侧**画出镜像的指示线。

**画法照抄原版 `JumpCurve`，只把方向镜像**，两处都必须是原版格式（2026-09 报告：早期版本自创了"外侧轨道 + 细线 + 圆点"，既不在积木上、也不是 Jump 的样子）：

- **端点 = 积木的正左侧**（元素局部坐标 `x = 0`，即左边缘的垂直中点），与结构引导线（`SugarCanvas.StructureGuideLayer` 取 `elem.inset + 6`）同一套坐标做法 —— 用一个 `Vec2` 交给 `localToAscendantCoordinates` 换算，缩进与 scroll 都由那一步带出来。**不要**取"所有卡片最左边缘"这类全局量：它只由最外层卡片决定，嵌套卡片右移后会错位，轨道还可能被推出 pane 左边界而被裁掉。
- **线宽 `Scl.scl(4f)`、目标端画 `Tex.logicNode` 箭头**，与 `JumpCurve.drawCurve` / `JumpCurve.draw` 一致。唯一差别是背包方向：原版按钮贴右缘、曲线朝右凸（`x + uiHeight`），本功能贴左缘、朝左凸（`x - bow`）。多候选时线更细更淡，表示"目标不唯一"。

**难点是"目标积木是哪一张"，而它不是积木序号。** `@counter` 的值是**最终产物**的指令下标，产物与画布积木不是一一对应：声明卡产出 0 条指令、`for` 的 step 与回跳落在 `blockend` 卡上、normal 模式函数体整体后置到 main 之后还要加尾部 `jump __ls_end`。所以链路分三层：

1. **`SugarFunctions.OriginRecording`（编译期来源通道）** — `lower()` 在每条语句前后量一次 `out` 的长度，只把 `[from, to)` 区间记进旁路；函数体与编译器自己发射的指令（入口 skip、hoist 前导跳、返回跳板）标成 `syntheticOrigin = -2`。**它绝不包装也不改写产物**（`StringBuilder` 在 `--release 17` 下无法被继承，因此不是 `Appendable` 包装），带记录与不带记录的编译产物逐字节相同 —— `originTest` 用逐字节比较钉住这一点。两个易踩的坑：`markSynthetic` **不能**预留数组容量（`synthetic.length` 参与行数计算，预留的空洞会被当成真实行，曾让 `compileRecorded` 因行数不匹配整体返回 null）；`flatten(totalLines)` 必须由调用方给出正文总行数（区间表只知道"有产出的语句"覆盖到哪里，末尾的收尾标签与入口 skip 不在任何区间里）。
2. **`SugarCompiler.compileRecorded(...)`** — 与 `compile(...)` 同一个 private 实现（`lastOriginRecording` 紧邻赋值，嵌套编译只会覆盖"来源"、不影响产物），产出 `CompileProvenance{code, origins[]}`；`origins[i]` 是产物第 i 条指令的发射语句下标，口径与 `stripMarkers(code)` 的指令流一致（不含标签行、标记块与载体）。纯原版程序走 `containsSugar` 的提前返回，那条路径逐行 1:1 直接给出来源，且**没有入口 skip**（skip 只为让 `__ls_*` 载体不执行，纯原版程序没有载体）。
3. **`assist.CounterJumpIndex`** — 纯文本工作，无画布依赖（`counterJumpIndexTest` 无头跑）：去标记块与载体行 → `LAssembler.read/write` 让标签变成数字目标 → 按 `MlogCFG.writes()` 的位置读 `@counter` 写入 → 算出目标。`set @counter <literal>` 给绝对目标，`op add/sub @counter @counter <k>` 给相对目标（`instruction + 1 ± k`，`+1` 是执行器的后自增），`op add @counter @counter <var>` 是 switch 跳转表、`set @counter __ls_*` 是函数返回跳板、末尾的 `set @counter 0` 是入口 skip，这三类只报形态不报目标。

**归属一律走来源通道，不用解析器的标签启发式。** `CounterJumpIndex` 拿到 `provenance` 时会把负值槽位当作"未知"再用 `__ls_stmt_<N>:` 标签补（没有来源时是唯一可用手段），但编辑器这边已经有确切答案，因此 `CounterJumpOverlay` 用 `provenance.originOf(write.instruction)` 定位卡片。

**`targets` 是指令下标，`elementAt(i)` 要的是语句下标 —— 两者绝不能混用。** 2026-09 的错位报告就是这里：`set @counter 3` 的线画到了第 4 张卡上，因为 3 被直接当语句下标用了。每个目标都必须先过一遍 `provenance.originOf(...)` 才能落到积木上；来源为 `-1`（函数体、编译器自己发射的指令）就是不可解析 —— 只画角标，绝不猜一条线。`originTest` 的 `counterTargetsResolveThroughProvenance` 钉住这条换算。

**绝不猜。** 目标唯一 ⇒ 实线（绿）；多候选 ⇒ 只画角标（琥珀），悬停该卡片时按候选画虚影线（`logicsugar.counterJump.candidates` 设置，默认开）；不可解析 ⇒ 灰色角标。角标本身可以点两下（悬停浮层），它回答"这张卡会改 `@counter`、执行将继续在指令 N"。**归属不到的写入不会消失**：函数体内部与编译器发射的 `@counter` 写出现在画布底部一列灰色小标（最多 6 行）上，悬停给出原因 —— 函数体里的下标只有在 hoisting 之后的产物里才有意义，指到调用方的某张卡上是错的。

**已知限制（明说，不装作支持）**：

- **函数体内部的 `@counter` 写只有底部小标，没有线。** normal 模式下函数体是所有调用点共享的一份、整体后置，`set @counter N` 的 N 是"后置后产物"里的下标；同一个函数在不同调用顺序下 N 的含义不变但"从哪进来"不可表达。`SugarDecompiler.firstDynamicCounterWrite` 正是把这类写（除三种已知形状外）当作 dynamic counter write 并放弃整个程序的结构化还原 —— 本功能与反编译器口径一致：认得出、标出来，但不假装能指到卡片。
- **`@counter` 写在 `for`/`while`/`if` 体内是可以画线的**（目标按产物下标解析，`originOf` 能给出正确的卡片），但那是"跳进/跳出结构"，会绕过循环条件或 exit 标签 —— 线本身不判断这是不是用户想要的，属于作者意图问题。
- **相对写入在 Expr 卡展开成多行时目标是一组候选**：卡片的指令区间有多个可能起点，因此按候选处理（琥珀 + 悬停列虚影线）。
- 目标落在折叠块内部时不画线（端点不可见 ⇒ 该曲线不绘制）。

#### 已踩过的坑（本功能实现过程中真实发生，别重犯）

1. **指令下标当语句下标用 → 线画到完全无关的积木上（2026-09 错位报告）。** `CounterJumpIndex.Write.targets` 是**产物指令下标**，`elementAt(i)` 要的是 `statements.getChildren()` 的**积木序号**。第一版把 `targets[0]` 直接塞进曲线的 `targetStatement`，于是 `set @counter 3` 的线指到第 4 张卡；两条线一起错位后，其中一条横跨整个程序，画面上就是一根贯穿全高的竖线。**修法**：目标解析只在 `rebuild()` 里做一次，且必须经 `provenance.originOf(target)` 换算；`originOf` 为 `-1` 就是不可解析（函数体、编译器自己发射的指令）⇒ 只留角标，绝不猜一条线。
   *为什么没被自测抓住*：静态检查、编译、归属断言全绿 —— 两个 int 空间在类型上无从区分，而"线画在哪张卡上"只有画出来才看得见。所以除了 `originTest` 的换算断言，这条还进了 [testing.md](testing.md) 的手测清单。
   *同源教训*：只要一个 `int` 可能同时是"指令下标／语句下标／行号"中的两种，就必须在变量名或注释里写清是哪一种。本功能里同时存在三种口径，是同一个陷阱的三个面：`originOfLine(line)` 收**正文行号**（标签行也占一行）、`CompileProvenance.origins[i]` 是**指令下标**（跳过标签行）、`Write.targets` 也是**指令下标**（但与语句下标空间不同）。混用任意两种都不会编译报错。
2. **`markSynthetic` 预留数组容量 → `compileRecorded` 对所有程序返回 null。** `synthetic.length` 参与 `flatten` 的行数上限计算，`Arrays.copyOf(..., length * 2 + 8)` 预留出来的空洞被当成真实行，`lineCount()` 于是大于正文行数，编译入口的一致性检查直接失败。**修法**：数组按需增长到 `high`，绝不预留。排查方式是打印 `flatten` 的三个输入（`totalLines` / 区间上界 / `synthetic.length`）—— 光看产物与归属断言的输出猜不出来。
3. **纯原版程序没有入口 skip。** `entrySkipLine` 只是为了让 `__ls_*` 载体不执行，而纯原版程序没有载体，`compile()` 因此原样返回源码。第一版按"sugar 路径也有 skip"的假设把最后一条指令标成 synthetic，导致纯原版程序最下面那张卡的角标消失。**教训**：`withEntrySkip` 是否真的落地取决于 `containsSugar` 走哪条分支，两条路径要各自断言（`originTest` 的 `vanillaProgramIsOneToOne`）。
4. **自创了"外侧轨道 + 细线 + 圆点"，而不是 Jump 的格式。** 第一版把端点外移到积木之外的 `-Scl.scl(5f)`（一列独立的"轨道"），线宽用 3.2f，目标端画 `Fill.circle`。结果是线悬在积木外面、既不像 Jump 也不贴在积木上（2026-09 报告："不应该是从积木的正左侧指向目标积木吗？不应该是 Jump 跳转线格式吗？"）。**教训**：这类"照原版的样子做"的需求，先去读原版那段代码（`JumpCurve.draw` / `drawCurve`），把 `Lines.stroke(Scl.scl(4f), color)`、`Tex.logicNode` 箭头、端点取法逐项抄下来再改方向；不要凭"看起来差不多"自己发明一套几何。

### 底部按钮行布局

逻辑编辑器底栏的单元宽度是固定的：普通按钮 `160x64`（与上游 `setup()` 的 `buttons.defaults().size(160f, 64f)` 一致），指令预算标签为 `180px` 内容加左右各 `8px` 内边距（合计 196px）。`SugarLogicDialog.layoutBottomButtons()`（`BottomBarLayout` 提供纯函数行打包，`bottomBarLayoutTest` 钉住）按可用宽度二选一：

1. **单行居中**（`available >= centered + 2*(debug + 12) + 16`）：操作组居中、检查控件贴右，两组是同一个 `Stack` 的两层，互不重叠。
2. **按行打包**：放不下时逐单元贪心装行，宁可多行也不把相邻单元挤在一起；行本身仍居中，因此窄屏下不会出现单行居中、多行左对齐的混搭观感。行数由真实可用宽度决定，不再有「上游两行形态」这一档——它只在应用组和检查组各自都放得下的宽度上生效，而那个宽度下打包本来也只得到两行。

**宽度判定不分设备。** `layoutBottomButtons()` 早期版本在 `Vars.mobile || isPortrait()` 时直接 return，保留上游那条固定宽度行；这正是手机端截断报告（2026-09）的成因：arc 的 `TextButton` 把 label 的 `minWidth` 钉成文字宽度（`add(label).expand().fill().wrap().minWidth(getMinWidth())`），整行因此压不到屏幕宽度以下，比屏幕还宽的那一行被 `Element.keepInStage()` 推到可见区之外（`centerWindow()` 之后它会把越界的右边缘拉回舞台内），返回键 / 打开函数库这类首尾控件就被裁掉。现在手机、竖屏、窄桌面窗口走同一条宽度驱动的路径，`update()` 里也按宽度变化（而非 `Vars.mobile`）触发重排。

**宽度必须只用一种单位：声明单位换算成场景单位后再比较。** arc 会把 `Cell` 的 `size` / `pad` / `margin` 全部乘上 UI 缩放（`Scl.scl(1f)`：桌面取设置值，手机按密度取 1.5 的整数倍档），所以源码里写的 `160` 是**声明单位**，2.5 倍缩放的手机上真实占 400 场景单位；而 `buttons.getWidth()`、`getPrefWidth()`、`Core.graphics.getWidth()` 量到的都是**场景单位**。行打包前必须把声明宽度过一遍 `BottomBarLayout.scaledWidths(Scl.scl(1f), widths)`，可用宽度、`barRowPad`、单行判定里的 `12f` 边距和预算标签的 `barBudgetWidth` 同样要 `Scl.scl`。2026-09 的第二次手机报告就是混用单位：七格按 `160` 计算"放得下" 1260px 的一行，真实那一行宽 2800 场景单位，居中溢出后首尾按钮（返回 / 添加）整颗落到屏幕外（截图里只剩中间三格）。桌面缩放为 1 时两者相等，所以这类错误只在手机和桌面 UI 缩放 > 100% 时显形。

两个必须保留的约束：① 上游 `setup()` 留在按钮行上的 `defaults().size(160f, 64f)` 同时设了**正的最大宽度**，落在该行的容器（`Stack`/换行 `Table`）会被压到单个按钮宽，固定宽度的子控件随即溢出并互相覆盖（2026-09 按钮重叠报告）；容器必须先把继承的最大宽度清掉（`Table` 的布局把 `maxWidth <= 0` 当作无上限）。② 改单元宽度或内边距时必须同步 `barButtonWidth` / `barBudgetWidth` / `barRowPad`，否则单行判定与行打包都会算错；改完还要确认它们仍只以声明单位出现在 `Cell` 上（实际布局）或经 `Scl.scl` 后进入比较（`scaledWidths`），两处不能对调。

窄屏不放指令预算标签：它是最宽的一格（196px），且在 640px 这类宽度上会独占一行——多出来的整行高度只为显示一个「超限有 toast 兜底」的读数。**只有这一格会让位**：其余可按的按钮在任何宽度下都保留（最坏一格独占一行），因为按不到的按钮是真实损失，读数是可替代信息。

### 调色板分类

Sugar 卡片不再全部挤在原版 Flow Control 里：

| 分类 id | 文案 | 卡片 |
| --- | --- | --- |
| `advcontrol` | Advanced Flow Control | For / While / Switch / If / Case / Default / Elif / Else / Break / Continue / BlockEnd / FuncDef / FuncCall / Return |
| `datastruct` | Data Structures | record / stack / queue / deque / bitset / map / uset / list / heap / chain |
| `arrayalgo` | Array Algorithms | array / matrix，以及**一张**批量运算卡（卡内按钮切换求和、平均值、最小值、最大值等 14 个运算）；旧 `arrayinit` 仅兼容读取 |
| `asserts` | Assertions | 既有断言卡 |
| 原版 `control` / `operation` | Flow Control / Operations | 原版 jump/end 与 `ExprStatement` |

**栏位 ≠ 分组**（`DataModules.paletteColumn`）：分组键（`DataModules.groupKey`，即各族 `LCategory` 的名字 `stackops` / `mapops` / …）只用来回答「哪几个运算属于同一结构」，它本身**不是选板栏**，只作分组标签与悬停标题；运算卡该进哪一栏由**该结构的声明卡在哪一栏**决定。规则表 `DataModules.GROUP_COLUMNS` 目前只登记数组组（`arrayAlgo`），未登记的结构一律落在 `dataStructures`——因为数组/矩阵是三处例外，它们的声明卡在「数组算法」栏，若不登记就会把声明卡与运算卡拆到两栏、跳转行滚动条配色也跟着错。**新结构若不在数据结构栏，必须在这张表里补一行**（`DataCallStatement.category()` 取用该查询）。

### 结构语句布局

`SugarStatements` 中的 `For`、`If`、`While` 和 `ElseIf` 将条件标签、条件编辑器和 `OP/Expr` 切换分别放在安全行；`For` 的循环变量、初值、步长和 `until` 也各自换行，折叠按钮单独放在末行。这样嵌套卡片只增加垂直高度，不依赖横向滚动，也不会让行尾控件被结构缩进推出卡片。条件字段使用紧凑宽度，`SugarCanvas.SugarStatementElem` 则按卡片实际宽度计算可用缩进，避免使用固定嵌套层数上限。布局行为需在不同方向、UI scale、语言和 MindustryX LogicSupport 侧栏状态下手测。

## 上游版本适配笔记（v160）

本分支运行口径是 Mindustry v160.1（`mod.json` 的 `minGameVersion`）；本节记录上游 v160 的 Logic 相关改动及本分支的兼容策略。默认 Gradle 依赖仍是工作区 `../Mindustry-master/desktop/build/libs/Mindustry.jar`，也可用 `-PmindustryJar=<path>` 指定另一份 API 包。每条给出上游变化、对 LogicSugar 的影响与适配动作；涉及 `mindustry.logic.*` 的成员访问必须与 `AGENTS.md` 保持一致，冲突时以 `AGENTS.md` 为准。

### 内存对象存储（上游 #12459，fac33d08d）

- **上游变化**：`MemoryBlock` 改为双数组（数字数组 + 对象数组 + 哨兵），logic 的 `read` / `write` 可存取对象（单位、方块等）；存档经 `TypeIO.writeObject` 序列化并带 version 迁移（旧存档全按数字读回）。**行为变化：越界 `read` 从返回 NaN 改为返回 null**。2026-09-09 的 Anuken/Mindustry master 已落地：`MemoryBuild.read` 越界走 `output.setobj(null)`。
- **影响/风险**：v155.4 / v159.7 的内存 cell 只存数字、越界 `read` 返回 NaN，LogicSugar 的产物与测试目前都建立在这套语义上（`assertTypeTest` 已为「内存对象存储」场景预留 number/对象分型）。栈/队列/双端队列的空 pop/peek 用越界 `read` 地址 −1 作为 NaN 哨兵，因此在已含 #12459 的 BE/master 上会读回 **null** 而不是 NaN；哈希表/集合的空槽仍靠 `op div 0 0` 写出 NaN 再用 `strictEqual` 判定，不受越界语义影响。`MlogLint` 当前只做 token 形状检查、不涉及内存语义。
- **适配动作**：v160.1 已确定含 #12459；空容器哨兵改为不依赖越界 `read`（例如 `op div 0 0`），并为 `MlogLint` 引入按版本分叉的越界语义。空槽判定保持 `strictEqual`，不要改成 `equal`。

### 本批功能与 BE 新 logic（deque / uset / 数组算法 / 撤销）

- **结论**：不依赖 BE 新增 opcode。声明卡与 bulk 运算仍降级为 `read`/`write`/`op`/`jump`/`funccall`；撤销/重做只改编辑器内存里的 sugar 文本，保存产物不变。结构卡文案走 `logicsugar.*`，原版菜单按 `logiclocalization` 使用 `localizedName()` / `statementKey()`。
- **本地化**：上游 `LStatement.statementKey()` 默认是 `typeName().toLowerCase()`；数据声明卡的 `typeName()` 是 token，因此键是 `instruction.deque` / `instruction.uset` 等，而不是类名 `dequedecl`。集合声明 token 必须是 `uset`，避免与原版 opcode `set` 冲突。
- **适配动作**：现在无需为这些功能分叉 v159.7 / v160 API。Sugar 自有卡片继续用 `name()`；原版语句菜单按 `localizedName()` / `statementKey()` 搜索与显示。bundle 同时保留 `instruction.<token>` 与旧的 `instruction.*decl` 别名。bump `minGameVersion` 时按上一节复查 MemoryBlock 对象存储即可。

### LAccess cleanup（b9189a5570 + 023a4ff1）

- **上游变化**：`LAccess.isPrivileged()` 方法删除，改为公共字段 `privileged`；可传感列表拆为普通 `senseable` 与特权 `senseablePrivileged`。
- **影响/风险**：`ExprCompiler` 不直接链接新增字段，而是反射读取 `senseablePrivileged`；旧版没有该字段时回退到完整 `senseable`，避免最低版本链接失败。编译上下文按当前处理器的 privileged 状态安装并在 `finally` 恢复。
- **适配动作**：新增或变更传感器列表时继续通过反射探测并保留旧版回退；运行 `v160SensorAccessTest` 与 `crossLoaderTest`。

### 新逻辑规则与 marker 控制

- **上游变化**：新增规则 `unitLight`（`set rule unitLight <bool>`，`LogicRule.unitLight`），开关单位灯光；marker 新增 `light` 控制（`LMarkerControl.light`）；world/minimap 的参数标签 "true/false" 改名 "truefalse"（纯 UI bundle key 变化）。
- **影响/风险**：均为新增枚举项/规则项，不改既有 opcode 与线格式；`truefalse` 改名只影响上游自身 UI 文案。Sugar 保存产物是原版可解析的 mlog，这类新字面量经原版 parse/save 往返即可携带。
- **适配动作**：现在无需改动；升级后新规则与新 marker 控制自动可用。

### 逻辑语句本地化（上游 #12158 + #12569）

- **上游变化**：新增设置 `logiclocalization`（默认开）；`LStatement` 增加 `bundle()` / `localizedName()` / `statementKey()`，卡片标题、语句菜单与搜索文案走 bundle，约定键 `instruction.<statementKey小写>`。
- **影响/风险**：LogicSugar 卡片标题跟随同一个 `logiclocalization` 开关；不再维护重复的 `logicsugar.localizeCards`。原生语句菜单搜索使用 `localizedName()`，提示键使用 `statementKey()`，Sugar 自有文案仍走 `logicsugar.*` bundle 键。
- **适配动作**：保持 `instruction.<statementKey小写>` 键名约定一致，升级时复查上游方法签名与菜单搜索行为。

### 语句卡片自动换行（上游 v160）

- **上游变化**：`LStatement.useWrapping()` 默认返回 `true`，`LCanvas` 对启用的语句使用 `WrapTable`；紧凑布局查询从 `useRows()` 迁移为 `isCompact()`。
- **影响/风险**：`WrapTable` 不保证显式 `row()`、`grow` 或 `colspan` 与普通 `Table` 相同。LogicSugar 的结构化卡片统一继承 `SugarStatement.useWrapping() == false`，保留自有行布局；`ExprStatement` 同样 opt-out。`ForBegin` 在自身普通表格内部按新版 `isCompact()`/旧版 `useRows()` 选择布局，两个方法均通过反射探测，失败时按上游宽度阈值回退。
- **适配动作**：新卡片默认继承 opt-out；只有完全由原子控件组成并验证过 `WrapTable` 行为的卡片才显式 opt-in。

### 字符串转义预览

- **适用范围**：`EscapePreview` 是只读浮层，只观察当前聚焦且仍在 Sugar 画布内的 `TextField`；仅接受完整 quoted mlog token，因此不会把变量名或表达式误报为字符串。
- **解码规则**：严格匹配上游的 `\\n`、`\\"`、`\\\\`、`\\uXXXX`；未知转义原样保留，坏的四位 Unicode 转义显示错误，Unicode 按 UTF-16 code unit 追加。现代能力通过反射实际调用运行时 `LAssembler.unescape` 探测，而不是只检测方法存在。
- **浮层限制**：预览不改写源文本、不参与卡片布局；滚动窗内无完整位置时隐藏，失焦、字段/画布不可见或画布销毁时清理。

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
src/logicsugar/           模组侧：入口与编辑器所有权判定（LogicSugarMod）、设置、函数库、FunctionLibraryDialog
src/logicsugar/assist/    编辑器辅助：BoxSelect、StatementClipboard、JumpLineColor、VarDisplayFilter、MlogLint、
                          VarClipboard、TextWrap/SugarTooltip、ProcessorStatus、UnitFlags、AssertInstructions、
                          EditHistory、InstructionBudget
src/logicsugar/assist/expr/  表达式子系统：ExprCompiler、ExprStatement、ExprHook、ArrayRegistry、
                          ShortCircuitCompiler、ExprIntrinsics 与各数据结构的 *Intrinsics
src/logicsugar/assist/data/  数据子系统：DataModule/DataModules/DataDeclaration 框架（含 paletteColumn 栏位查询）+
                          ArrayBulkModule、RecordModule、ContainerModule、BitsetModule、MapModule、SetModule、ListHeapModule
src/mindustry/logic/      与游戏同包名的扩展层：SugarCompiler、SugarDecompiler、SugarStatements、
                          SugarAsserts、SugarCanvas、SugarLogicDialog、SugarCoexist、SugarFunctions、
                          RecoveryPredicate、MlogCFG
test/                     与 src 同构的 main() 式自测（无 JUnit）
assets/bundles/           bundle.properties / bundle_zh_CN / bundle_zh_TW（用户可见文案）
```

注意源码目录是非标准布局：Gradle `sourceSets` 直接把 `src/`、`test/` 当根目录，没有 `src/main/java` 层级。

## 设计约束（务必保持）

- **兼容底线（项目所有者明文要求）：多人联机环境下必须兼容原版客户端**——保存到处理器的代码在任何原版客户端上都要能解析、能运行，优先级高于一切新功能。调试类功能（目前是 AssertEmit=emit）只在单机/编辑器（`!Vars.net.active()`）生效，联机会话一律回落原版行为，门禁在代码层强制（`SugarCompiler.currentAssertEmit`）。不提供改变处理器指令预算的能力：指令上限覆盖曾试做后被移除（处理器保存产物恒 ≤1000 条是硬不变式）。全局函数库文件 `functions.txt` 不是处理器产物，另有 `SugarFunctions.libraryInstructionLimit`（当前 10000 条语句）上限，见"函数与全局函数库"。残余风险：单机创建的调试构建若分享到多人环境，原版客户端仍会静默降级，代码无法阻止分享，只能靠设置描述与文档讲清。
- 用户可见文案一律走 `logicsugar.*` bundle key，不硬编码。
- 受保护游戏成员访问只走子类实例方法或反射（见上），静态辅助代码只用 public 游戏 API。
- 反编译恢复必须留在重编译/规范化流比对门后，失败方向是"多显示原版代码"。
- **新功能必须考虑重建**：会进存档的语法/卡片/注入函数，要能走载体还原（声明卡只活在 Sugar 源码里），或说明推断路径做不到、重开只显示原版。`destIndex` 是跳转注释，不以它为结构的唯一真相。
- 上游 API 依赖尽量做成可降级：核心路径硬反射，外围功能 optional 反射。
