# 术语表

按主题归类的 LogicSugar 术语。每条说明它在本项目语境下的准确含义。

## 项目形态

### Sugar / Sugar 语句
LogicSugar 提供的结构化编辑语言：`ifbegin`/`elif`/`else`、`forbegin`、`whilebegin`、`switchbegin`/`case`/`default`、`break`/`continue`、`blockend`、`funcdef`/`funccall`/`return`。它们以卡片形式出现在编辑器里，`build()` 返回 `NoopI`（自身不产生指令），语义由编译器 lowering 成普通 mlog。

### mlog
Mindustry 原生逻辑指令集（`set` / `op` / `jump` / `read` / …）。LogicSugar 的硬约束是保存结果必须是原版兼容的 mlog：无模组客户端能运行、能重开。

### bekBundled
`LogicSugarMod` 上的静态布尔标记，表示"当前运行在 Neon 聚合环境中"。为 `true` 时模组跳过自建设置分类，改由宿主调用 `bekBuildSettings(...)` 收口设置；独立运行时为 `false`。

## 编译与持久化

### 载体（carrier）
保存到处理器里的持久化元数据，是真实的 `set` 语句（能挺过原版 parse/save 往返）：`set __ls_sugar "<base64>"` 存 Sugar 源码，`set __ls_lib "<base64>"` 存程序用到的库函数子集（跨机器可重编译）。单条上限 60000 字符。

### 注释标记块（marker block）
v2.0.0 旧程序的持久化方式：`# @logic-sugar-v1 begin` / `# @logic-sugar-line ` / `# @logic-sugar-v1 end`。现仅作读取兼容，新程序一律用载体。

### 入口跳过（entry skip）
编译产物在 main 末尾统一多出的一条 `set @counter 0`（`SugarCompiler.entrySkipLine`），让紧随其后的几 KB 载体 `set __ls_sugar` **永不执行**——否则 MDTX 逻辑面板的值列会把载体当成一条运行中的赋值显示出来。等价性依据：`runOnce()` 在 `@counter` 越界时本就「置 0 执行指令 0」，跳过条不改变语义。要点：① 这条 skip **是被存储的糖源码的一部分**（随载体一起保存，编译期不额外 append），旧版本重编译这段文本能原样复现，`verifyRestore` 仍然通过；② 代价是**有效指令上限变成 `maxInstructions − 1`**；③ 反编译侧位置是「形状的一部分」（跳过至多一条 hoist `jump` 之后只许剩载体），`isEntrySkip` 带位置约束。已知边界：源文本超过解析窗口（`LExecutor.maxInstructions` 条**语句**，注释与空行免费）时 `LParser` 只解析窗口内的部分、其余静默丢弃；`compile` 在解析后随即确认（头部带糖却没有 skip、或糖整体落在窗口之后）并抛 `IllegalArgumentException` 拒绝保存，纯 vanilla 的长程序则照常通过。详见[架构总览](architecture.md)「编译器」。

### FuncMode（函数模式）
函数展开方式。`normal` = 共享 `@counter` 子程序（函数体上提到程序尾部，一次定义多处跳转）；`inline` = 每个调用点展开一份副本，编译器临时名带 `__ls_i_<callId>_`。设置项 `logicsugar.funcMode`。

### SwitchStrategy（分派策略）
`switch` 的下降形态。`auto` = 整数 case 且值域跨度 ≤255 时按可执行指令成本在比较链与跳转表间二选一；`chainOnly` = 恒用比较链（与 2.3.1 之前输出逐字节一致）。设置项 `logicsugar.switchStrategy`。带 `raw` 的裸表不受它影响。

### AssertEmit（调试断言构建）
断言卡片的编译开关（设置项 `logicsugar.assertEmit`）。`strip`（默认）把断言编译掉，mlog 保持原版可解析；`emit` 把断言写回为真实自定义指令——原版客户端会把这些行降级为 InvalidStatement 占位（断言静默失效）。**仅单机/编辑器生效**：联机会话强制 `strip`（见"单机门禁"）。

### 单机门禁（single-player gate）
项目硬底线的执行机制：**多人联机环境必须兼容原版客户端**，因此会改变保存产物语义的调试类功能（目前是 AssertEmit=emit）只在 `!Vars.net.active()`（单机/地图编辑器）时生效，联机（已连接或自建）一律回落原版行为。门禁在代码层强制（`SugarCompiler.currentAssertEmit`）；不提供改变处理器指令预算的能力（指令上限覆盖曾试做后移除，处理器产物恒 ≤1000 条），不依赖用户自觉；纯展示类功能不受此限。全局函数库文件不是处理器产物，另有 `SugarFunctions.libraryInstructionLimit`（当前 10000 条语句）上限，见"函数库"。残余风险：单机创建的越界内容被分享到多人环境时原版客户端仍会截断/清空/静默降级，只能靠文档与设置描述讲清。

### 断言语句集（assertions）
移植自 cardillan/MlogAssertions v0.8.2 的八条运行时检查指令（`assertBounds`/`assertequals`/`assertflush`/`assertprints`/`asserttype`/`error`/`log`/`breakpoint`），线格式逐字节兼容：断言失败程序在失败行自旋并由 `ProcessorStatus` 显示消息（可经「断言失败即断点」改为在失败指令处暂停），`breakpoint` 暂停游戏、居中视角并按设置临时分离视角，全部 accumulator 在本帧结束后归还。与 MlogAssertions 并存时按"先到先得"跳过重复 opcode 注册。

### 断点（breakpoint）
暂停整个游戏并把视角定位到命中的处理器，用于在不改变处理器状态的前提下检查变量与内存。`ProcessorStatus.breakpoint` 负责暂停、视角、accumulator 冻结/归还与暂停期间的消息绘制；「禁用断点」让 breakpoint 与断点化断言变成空操作，「断点分离视角」决定是否临时改写原版 `detach-camera` 设置（取消暂停时恢复原值）。

### 线格式（wire format）
自定义指令在 token 流中的精确形状（opcode 拼写、参数顺序、引号约定）。断言语句集的线格式必须与 MlogAssertions/Mindcode 保持逐字节一致，由 `assertTest` 钉住；任何一侧漂移都会破坏互操作。唯一例外是 `asserttype` 的 `null` 类型：它是 LogicSugar 扩展（上游 v0.8.1 的 `asserttype` 只有六种类型，`valueOf` 不识别 `null`），其余 token 与上游完全一致。

### 跳转表（jump table）
`switch` 的一种下降结果：先做上下界守卫，再用 `op add @counter @counter` 按槽位分派；越界与空洞槽走默认路径。非整数或跨度过大的 case 集合自动回退比较链。

### 步长表（stride table）
`switchbegin … stride <n> <tmp> abs|rel`：手写 `@counter` switch 的另一种形态。`abs` 是 `op mul` 后 `op add @counter <tmp> K`，case `v` 落在指令 `v*n+K`；`rel` 是三条指令的相对派发，第一个 case 紧跟在派发之后。case 正文本身就是槽，每槽恰好 `n` 条指令（区域末尾允许一个较短的最后一槽）。`K` 在编译时按第一条正文的指令下标重算，不写进源码。选板不创建这种形态。

### 裸表（raw table）
`switchbegin … raw`：手写/第三方工具生成的无边界守卫跳转表，只发 `op add @counter @counter <切换值>` 加每条槽位一条跳转行，且无视 `SwitchStrategy`。恢复出来的表用它保持产物逐条不变；越界值不再被夹回默认分支（只有跨度内的空槽走 `default`）。

### 默认分支（default）
`default:` 卡片：没有 case 命中时执行的分支。比较链里它是末尾跳转的目标；跳转表里是所有空槽行的目标，带守卫形态下越界值也落到这里。每个 switch 至多一张。

### 跳转链穿线（jump threading）
lowering 之后对"无条件跳转到无条件跳转"的链做合并，减少冗余指令；带环检测保证循环与自跳转安全（`SugarCompiler.threadAlwaysJumpTargets`）。按标签读链的那一趟对手写 mlog（无标签、用指令下标寻址）是空操作，因此验证门另有 `threadNumericJumpTargets` 在语句下标上做同一套不动点。

### `__ls_` 前缀
编译器保留命名空间：函数返回变量、临时守卫、内联编号等都用它。用户函数/参数名不得使用；`VarDisplayFilter` 会把它们从 MindustryX 变量浏览器里隐藏。

### 表达式临时变量（`_0, _1, …`）
表达式编译的栈式编号临时变量，每个一次写一次读形成线性链——这是反向把 `op` 链重建为表达式的关键前提。

### 内存对象存储（memory object storage）
上游 #12459（v160）的 `MemoryBlock` 改动：内部改为数字 + 对象双数组（含哨兵），logic 的 `read` / `write` 可存取对象（单位、方块等），存档经 `TypeIO.writeObject` 带版本迁移（旧档全按数字读回）；越界 `read` 从返回 NaN 改为返回 null。LogicSugar 当前按 Mindustry v160.1 基线生成原版 `read`/`write`，`assertTypeTest` 覆盖 number/对象分型；若未来扩展 `MlogLint` 内存/类型检查，需明确区分旧版与当前语义。详见[架构总览](architecture.md)「上游版本适配笔记」。

## 表达式与恢复

### 容量解析（capacity resolution）
声明卡的容量上限口径：`ArrayRegistry.capacityOf(memory)` 先向当前会话的处理器解析该变量链接到的方块（`LinkResolver`；mod 启动时用 `ArrayRegistry.setLinkResolverProvider(ArrayRegistry::processorLinks)` 装延迟提供者，按需从 `SugarLogicDialog.executor` 取），命中 `MemoryBlock` 就返回真实 `memoryCapacity`；解析到方块但不是内存块返回 0（确定不限制）；完全解析不到（无处理器上下文、变量缺失）才回落到按名字猜的 `memoryCapacity(String)`。三态由 `CapacitySource` 区分（`linked` / `notMemory` / `inferred` / `unknown`），只有 `inferred` 的错误信息会标注是猜的。函数库会话与无头自测没有处理器，因此回落分支必须保留；由 `arrayTest` 的 `resolvedMemoryCapacity` 钉住。

### 数组（array）
`array` 声明卡定义的纯 sugar 抽象：把内存块变量（如 `cell1`）上 `[base, base+size)` 的一段物理地址登记为命名数组。卡片本身不产出任何 mlog 行（lower 时剥离，产物保持纯原版指令）；表达式下标 `buf[i]` / 下标赋值 `buf[i] = x` 在编译期查 `ArrayRegistry` 换算物理地址（= base + 逻辑下标）后发射原版 `read` / `write`。v0 限制：base/size 仅接受整数字面量，重名与同内存块区间重叠是编译错误，数组名不得与函数重名。编辑器折叠只在注册表把 `read`/`write` 的内存块命中到已声明数组时把该行折回下标表达式——纯原版 mlog（无声明卡）不做数组推断恢复；字面量下标越界是编译错误，变量下标不做静态越界检查，运行时保持 v160.1 的内存语义（越界读返回 null）。由 `arrayTest` 钉住。

### 短路求值（short-circuit）
`&&` / `||` 按控制流顺序求值：右侧只在需要时执行。`ShortCircuitCompiler` 把短路谓词下降为条件 `jump`，不产生先行求值的布尔临时变量。

### 恢复 / 重建（recovery / reconstruction）
打开已保存处理器时，把原版 mlog 还原成 Sugar 积木。两条路径：**载体还原**（decode `__ls_sugar`，优先、无损，数据声明卡只走这条路）和 **反编译推断**（从 jump/op 认回 `if`/`for`/`while`/`switch`/函数）。详见[架构总览](architecture.md)「重建」。

### destIndex（跳转注释）
`ifbegin` / `forbegin` / `whilebegin` / `switchbegin` / `funcdef` 行尾的整数，指向对应 `blockend` 的语句下标。它是注释而不是结构本身：嵌套由 begin/end 配对决定。注释过期（越界、交叉）时按最内层 `blockend` 重配对，不因此把整份程序当成「被外部改过」。

### 安全门（verification gate）
恢复结果的强制闸口：候选 Sugar 必须重新编译并与输入的规范化**可执行**指令流比对一致才被接受（比对前剥掉载体与标记块）；不识别的内容回退为原样 vanilla 语句。失败方向永远是"多显示原版代码"。

### flat 模式
安全门未通过（或输入无结构）时的结果：逐语句保留规范化 vanilla mlog，不呈现任何 Sugar 结构。

### collapsed（`c` 后缀）
语句解析器的折叠式变体（如 `forbeginc`、`whilebegin exprsc …`），对应条件为折叠进卡片一行的表达式形式；`exprsc` 表示短路口径的表达式条件。

### RecoveryPredicate
无依赖的谓词树模型（`And`/`Or`/`Not`/比较原子），带 `EAGER` / `SHORT_CIRCUIT` / `UNKNOWN` 求值方式标注与 loss/score 度量，供恢复代码在触碰游戏 API 前构建、打分候选。

## 数据子系统

### intrinsic（表达式内建）
`ExprIntrinsics` 注册的表达式函数展开点：表达式里的函数名（`array_sum`、`stack_push`、`map_set`…）在编译期展开为原版 `op`/`read`/`write`/`funccall` 指令链，而不是用户函数调用。Provider 实现必须放在 `logicsugar.assist.expr` 包（`Node`/`Line` 是 `ExprCompiler` 的包私有类型）；同名用户 `funcdef`/库函数优先（intrinsic 被遮蔽），数组最值用 `array_min`/`array_max`，旧短名 `min`/`max` 仍按实参个数分派（1 参 = 数组运算，2 参 = 原版内置）。

### 数据模块（DataModule / DataModules）
一个数据结构 = 一个 `DataModule` 子类（声明卡解析器 + 编译期注册表 + intrinsic provider + 注入函数源文本）。`DataModules` 是统一驱动点：`register` 登记模块并注册 provider（按 `id()` 幂等），`registerParsers` 安装声明卡解析器，`collectAll`/`restore` 在每次编译前后配对建立/清理程序级注册表（`SugarCompiler` 的 `finally` 保证异常路径也恢复），`markInvalid` 供编辑期标红。

### 数据调用积木（DataCallStatement）
`datacall <operation> <destination> "<arguments>"` 是数据 intrinsic 的通用可编辑卡。其 operation/category/default arguments/`returnsValue` 来自模块的 `PaletteCall` metadata；有返回值时默认显示 `result = op(args)`，结果写入左侧变量；无返回值时显示 `op(args)`，不要求结果变量。当前数组 `array_fill/array_copy/array_sort/array_sort_desc/array_reverse/array_swap` 与结构清空操作属于无返回值卡；保存时仍进入 Sugar carrier，编译时复用 `ExprIntrinsics` 展开，因此不会把 `datacall` 或内置 `funccall` 泄漏到原版 mlog。**一张卡一个结构**：选板从 68 张运算卡收敛为 10 张，卡内按钮切换该结构的运算；参数元数据不另建表——`PaletteCall.arguments` 的默认实参串逐位就是参数名，`splitArgs` 做括号感知拆分，`argumentSlots()` 决定「每参数一框」还是「退回单框」。

### 栏位（palette column）
运算卡进入哪个选板栏由**该结构的声明卡在哪一栏**决定（`DataModules.paletteColumn`），规则表 `DataModules.GROUP_COLUMNS` 只登记数组组（`arrayalgo`），未登记的结构一律落在 `dataStructures`。**栏位 ≠ 分组**：分组键（`DataModules.groupKey`，如 `stackops`）只用来回答「哪几个运算属于同一结构」，本身不是选板栏，只作分组标签与悬停标题。新结构若不在数据结构栏，必须在这张表里补一行。

### 运算卡的 bundle 键（三族）
运算卡只引用三族键，每一族都只有一个取用点：卡片正文/卡内运算按钮/实参输入框占位取 `logicsugar.datacall.<规范运算名>` 与 `logicsugar.datacall.arg.<参数名>`（`DataCallStatement.operationLabel` / `argumentLabel`）；卡内运算选择器每一条的悬停取 `logicsugar.hint.datacall.<规范运算名>`；调色板按钮描述取 `logicsugar.lst.datacall.group.<分组键>`——**一张卡覆盖整组，所以不存在逐运算的 `lst.datacall.<运算名>`**。旧短名（`spush`/`sum`/…）在卡片解析时就被 `DataModules.canonicalOperation` 归一化成规范名，旧名那套键已随之删除。这条可达性不变量由 `DataCallTest.everyOperationBundleKeyIsReachable` 双向钉住（三族键集合 == 代码可达集合）：新增运算漏写某一族键，或重新引入取不到的键，都会直接变红。

### 隐藏状态变量
栈/队列/列表/堆/链表/双端队列等结构的运行时状态（如 `__ls_stk_<name>_top`、`__ls_que_<name>_head/_tail/_count`、`__ls_deq_<name>_head/_tail/_count`、`__ls_lst_<name>_count`、`__ls_chn_<name>_head/_free`）是普通 mlog 变量，用 `__ls_` 保留前缀声明，`VarDisplayFilter` 自动隐藏、用户不得使用同前缀命名。mlog 变量未赋值读取为 0，因此初始状态不需要初始化指令；代价是它们不随存档持久化——处理器代码重新载入后计数归零而内存块内容保留。链表是例外：`head`/`free` 读作 0 会被当成合法节点下标，首次使用前必须显式 `chain_init(c)` / `chain_clear(c)` 重建空闲链。

### 注入函数（`__ls_builtin_*`）
模块提供的 `funcdef` 源文本，经 `SugarFunctions.withBuiltins` 并入本次编译的函数索引。循环型/写内存型操作（push、sort、find、哈希探测等）走注入函数，normal 模式全程序共享一份子程序、未使用不进产物，且不会进入 `__ls_lib` 载体或用户函数库。

### 墓碑删除（tombstone delete）
哈希表 `map_erase` 的删除策略：只把 key 槽写成 NaN 标记、value 槽保留原值。探测是整表环形扫描，墓碑不会截断探测链，因此无需回填/重插。空槽判定用 `op strictEqual`（NaN 存回内存后是 null 对象，`equal` 会把数字 0 与 null 判等）。

### 记录（record）
`record <name> <f1>…<f8>` 声明卡定义的纯编译期结构：字段降级为普通变量 `<name>_<field>`（用户可见），成员读 `p.f1` 为 `op add <tmp> p_f1 0`、成员写 `p.f1 = expr` 为 `op add p_f1 <value> 0`。只有已声明为 record 的变量名才走成员展开，其余成员访问保持原版 sensor 语义。

### 哈希表（map）
`map <name> <memory> <base> <capacity>` 声明的开放寻址哈希表：键区 `[base, base+capacity)`、值区 `[base+capacity, base+2*capacity)`，`hash = abs(key) % capacity` 线性探测。首次使用前必须 `map_clear(m)`（未初始化槽读回 0 会被当作已占用 key=0）；不支持字符串键。

### 无序集合（uset）
`uset <name> <memory> <base> <capacity>` 声明的开放寻址键集合（token 不能是 `set`，那是原版 opcode）。只占用 `[base, base+capacity)`，探测与墓碑策略与哈希表相同；表达式 `set_add`/`set_contains`/`set_remove`/`set_size`/`set_clear`。首次使用前必须 `set_clear(s)`。

### 双端队列（deque）
`deque <name> <memory> <base> <size>` 声明的双端环形缓冲，状态变量 `__ls_deq_<name>_head/_tail/_count`。`deque_push_front`/`deque_pop_front`/`deque_front` 操作前端，`deque_push_back`/`deque_pop_back`/`deque_back` 操作后端；满 push 不写入，空 pop/peek 返回 NaN。

### 链表（chain）
`chain <name> <memory> <base> <size>` 声明的单链 + 空闲链结构：节点 i 的值槽在 `base+2*i`、next 槽在 `base+2*i+1`，`next = -1` 表示链尾；声明区间 `[base, base+2*size)`。`chain_alloc` 从空闲链 LIFO 取节点并返回下标（空闲链空返回 -1），`chain_free` 摘链后挂回空闲链（非法下标返回 -1 且不改状态），`chain_link` 只改 next 槽不校验目标（`-1` 合法），`chain_len` 沿 next 遍历计数。首次使用前必须 `chain_init(c)` / `chain_clear(c)`。

### 空闲链（free list）
链表的空闲节点单链，由隐藏变量 `__ls_chn_<name>_free` 指向链头（`-1` = 无空闲节点），`chain_init` / `chain_clear` 把它重建为 `0→1→…→size-1→-1`，`chain_alloc` 从中摘取、`chain_free` 挂回。它让节点分配/释放不依赖额外的计数变量；`chain_free` 不检测重复释放，重复释放同一节点会让空闲链成环。

## 函数

### 方法糖 / 下标糖（method / index sugar）

Expr 模式下把只读 getter intrinsic 写得更像语言原生访问：`list[i]` → `vector_at(list, i)`、`stack.top()` → `stack_top(stack)`、`bitset.test(i)` → `bitset_test(bitset, i)`、`chain.head()` → `chain_head(chain)` 等。解析为 `ExprCompiler.Method` / `Index` 节点后，由 `ExprIntrinsics.Provider.kindOf` / `methodIntrinsic` / `indexIntrinsic` 按接收者的已声明结构类型解析；只映射无注入函数的 getter，因此可达性分析 `collectCalls` 直接跳过方法节点。已声明数组优先于同名结构的 `[i]`。下标糖只读，赋值必须用 `vector_set` / `bitset_set` / `chain_set`。

### 函数库（function library）
全局函数文件 `<game data>/mods/config/LogicSugar/functions.txt`，只含 `funcdef … blockend` 对，所有处理器共享。损坏时按函数逐个抢救为部分索引；在处理器编辑器内直接编辑，关闭自动校验保存。库文件不受处理器 1000 条上限约束，当前上限为 `SugarFunctions.libraryInstructionLimit`（10000 条语句）；库文本统一走 `SugarFunctions.readLibrary` 解析（临时抬高 `LExecutor.maxInstructions` 后还原），超限由 `libraryOverLimit` 在保存/打开编辑时明确拒绝；处理器仍然只保存 ≤1000 条，只嵌入被调用到的函数子集。

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

### 单位 flag 显示与着色
可选地图叠加：设置 `logicsugar.showUnitFlags` 打开后，在每个单位正上方绘制其逻辑 `flag`（`ucontrol flag` / `@unit.@flag`）。默认使用红色；再打开 `logicsugar.colorizeUnitFlags` 后，不同 flag 按首次遇到顺序优先使用 10 种高对比度颜色，更多 flag 使用高饱和度随机色，并在当前世界内保持映射稳定。默认 0 与非有限值不显示；视野外与迷雾中的单位跳过。纯展示，不改保存产物，不受单机门禁限制。由 `unitFlagsTest` 钉住判定、格式与配色分配。

### 逻辑语句本地化（logic localization）
上游 #12158 + #12569 的改动：`LStatement` 增加 `bundle()` / `localizedName()` / `statementKey()`，卡片标题、语句菜单与搜索文案走 bundle，约定键 `instruction.<statementKey小写>`（上游设置项 `logiclocalization`，默认开）。LogicSugar 自身卡片标题跟随同一个 `logiclocalization` 开关，文案仍使用 `logicsugar.*` 键，不再维护重复的 `logicsugar.localizeCards` 开关。详见[架构总览](architecture.md)「上游版本适配笔记」。

### 编辑器归属（EditorOwner）与编辑器冲突四档
逻辑编辑器是**一个全局引用**（`Vars.ui.logic`），扩展 `LogicDialog` 的模组都可以替换它，于是「两个模组只能有一个生效」。`LogicSugarMod.classify(LogicDialog)` 把当前 owner 判为 `vanilla`（`null` 与游戏自带 `LogicDialog`，可安全替换）/ `sugar`（自家 `SugarLogicDialog` **及其子类**）/ `foreign`（其余 `LogicDialog` 子类）。设置项 `logicsugar.editorConflict` 四档：`ask`(默认) / `takeover` / `stepaside` / `coexist`，`parse` 大小写不敏感，缺失/空/未知值一律回落 `ask`（**绝不回落到「编辑器被停用」，也不默默选带不可逆副作用的 `takeover`**），切换后立即生效。安装顺序是确定的：其它模组在构造函数里注册 `ClientLoadEvent`、本模组在 `init()` 里注册，`Core.app.post` 是 FIFO ⇒ 对方总是先安装。

### 共存画布（SugarCoexist）
`logicsugar.editorConflict=coexist` 档的实现：保留对方整套编辑器界面，把 LogicSugar 的画布**跑在对方的编辑器里**，跨 classloader 反射读写 `LCanvas`/`LogicDialog` 的包级字段（统一走 `SugarCoexist.field(Class,String)`），并把 MDTX 的逻辑辅助面板重新绑定到新画布。`push` 挂在对方对话框关闭时的原版 `hidden(...)` 出口上，因此捕 `RuntimeException`、绝不逃逸；放置失败时退回接管（`logicsugar.conflict.coexistfailed`）。**已知限制**：会话中途切档、第三方在构造期缓存画布两种形状可能写回空程序，详见[架构总览](architecture.md)「编辑器接管与共存」。

### 跨逻辑剪贴板（StatementClipboard）
编辑菜单「复制选区 / 粘贴选区」（Ctrl+C/V 驱动同一实现）。**剪贴板放糖源码而不是编译后的 mlog**，片段落进另一个处理器后仍可继续编辑；片段文本带自描述头 `# @ls-fragment`，粘贴侧据此识别并 `rebase`。跨程序时 `jump` 的数字目标是另一程序的指令下标，因此**双侧拒绝**；块配对由 `pairBlockEnds` 在插入前校验，之后每帧 `syncStatementIndices` 自愈。

## 构建与发布

### D8 / classes.dex
把 Java 字节码转成安卓 `classes.dex` 的工具链。Mindustry 安卓端从 jar 内的 `classes.dex` 加载 Java 模组，桌面中间 jar 不含它、不能分发。构建用 `--min-api 21 --release --lib android.jar`，并传游戏类路径供 desugar 解析。

### deploy（合并 jar）
唯一可分发产物形态：桌面 classes + `classes.dex` + `mod.json` + 资产的合并 jar（`LogicSugar-v<version>.jar`）。`deploy` 完成后自动复制改名到 `构建/LogicSugar/LogicSugar-dev.jar` 作为本地开发产物。

### dev 身份（LogicSugar-dev / 0.0.0）
本地开发期 `mod.json` 的临时身份（工作区默认约定）；发布态改回 `LogicSugar` / `<version>`。详见[版本与发布](release.md)。

### bundle
Mindustry 的 i18n 文案文件。本模组在 `assets/bundles/` 下维护 `bundle.properties` / `bundle_zh_CN.properties` / `bundle_zh_TW.properties` 三份，key 以 `logicsugar.*` 开头，用户可见文案不允许硬编码。

## v5 API（logic-sugar-v2）

### 值拷贝（CopyLine）
v5 起「把一个值拷到另一个变量」统一用 `set <dst> <src>`（编译期的 `CopyLine`）。旧写法 `op add <dst> <src> 0` 会经 `LVar.num()` 读操作数，把对象折成 1、把空值（NaN 标记）折成 0，属于会丢值的实现细节，v5 已消除。

### 失败信号（-1）
可失败的数据操作（push/append/insert/delete/set/free 等）失败时统一返回 `-1`；成功仍返回各自有意义的值（计数、下标或 1）。查询类 `map_contains`/`set_contains`/`bitset_test` 保持 0/1；`bitset_test` 越界仍为 0（它是「位是否置位」的查询，-1 在 mlog 里是真值）。

### 无结果卡（`~`）
结果恒定、无信息量的操作不暴露目标变量，卡片可以写 `~`：`bitset_set`/`bitset_reset`/`chain_set_head`，加上本来就无结果的 `array_fill`/`array_copy`/`array_sort`/`array_sort_desc`/`array_reverse`/`array_swap`/`stack_clear`/`queue_clear`/`deque_clear`/`map_clear`/`set_clear`。旧存档若已带目标变量，编译时仍写进该变量，指令流不变。

### 返回声明
`funcdef f a ~ 3`（void，体内不得值返回）与 `funcdef f a value 3`（必须值返回一次）；不写声明（`funcdef f a 3`）沿用按函数体推断。声明写在参数之后、`destIndex` 之前，第三种槽是整数即为旧形态，因此老存档字节不变。

### logic-sugar-v2
v5 的持久化格式标记：新存档写 `# @logic-sugar-v2 begin`/`… end` 注释块。`SugarCompiler.storedFormat()` 返回 2（当前）、1（v1 标记）、0（标记被原版往返冲掉、只剩载体）。

### legacyApi（v1 lowering）
验证门用的 v5 之前 lowering。`verifyRestore` 先按 v5 重编译比对存储流，不一致再进入 `Api.v1` 复现旧指令流，只有精确匹配才接受载体 —— 老存档行为可复现，验证门没有被放宽。
