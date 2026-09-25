# 测试指南

LogicSugar 的自动化测试是 `main()` 断言式的 JavaExec 回归任务（无 JUnit runner），全部挂接在 `check` 上。当前共有四十二个 JavaExec 自测任务。任何接线改动都不允许把自测任务从 `check.dependsOn` 摘掉；`test` 任务被显式禁用，属正常现象。

## 自动化任务

`build.gradle` 注册了四十二个自测任务，均 `dependsOn testClasses`：

| 任务 | 主类 | 覆盖内容 |
| --- | --- | --- |
| `selfTest` | `logicsugar.SugarCompilerSelfTest` | 编译器主回归：嵌套结构 round-trip、非法结构报错、语义错误定位、`break`/`continue` 就近退出、if/elif 链、注释往返、生成代码优化与 `@counter` 保护、`SwitchStrategy`（auto/chainOnly）公式与语义网格、跳转链穿线、表达式 `op` 链往返、函数（参数绑定、void/早退、返回值、嵌套/前向/循环内调用、临时名空间）、引号转义、载体分片（大源码 `__ls_sugar_N`/`__ls_lib_N` 分片还原、小程序单条形状回归）、`logic-sugar-v2` 标记与 `storedFormat`、`op add d s 0` ↔ `set d s` 拷贝归一化（v1 存档仍过验证）、函数返回声明（`~`/`value`/留空推断与调用点校验） |
| `ifElseTest` | `mindustry.logic.IfElseCompileTest` | `if` / `elif` / `else` / `while` 三段式条件的 lowering 冒烟（负分支取反、标签、出口跳转） |
| `decompileTest` | `mindustry.logic.SugarDecompilerTest` | 反编译恢复：vanilla 程序保持原样、各结构恢复 round-trip、跳转表识别（含手写无守卫裸表 → `switchbegin … raw` + `default`）、手写程序无入口 skip 也能恢复、数字跳转链穿线归一化、真实 655 条跳转表程序夹具（`test/fixtures/realworld-jump-table.mlog`）、**编辑器开屏决策**（`openingSource`：无载体的处理器程序必须走推断、两种编辑器权限都要验证、载体程序与函数库文本保持原样）、陈旧载体回退推断、短路守卫重建布尔树（单原子 / 顶层 `!` / 嵌套 `&&`\|\|` / 左深链 / `whilebegin`/`forbegin` 的 `exprsc` / `continue` 内部回边）、贪心候选失败后的回溯提升、验证矩阵（chainOnly 保存的程序在 auto 默认设置下仍验证）、动态 `@counter` 分诊保持 flat、函数区杂散跳转验证、不支持的模式保持 flat、引号/转义、坏输入不崩 |
| `reconstructionTest` | `mindustry.logic.ReconstructionFixtureTest` | 重建：过期 `destIndex` 按嵌套重配对后载体仍验证；两份世界处理器样例走载体还原出 `ifbegin`/`forbegin`；`array`/`stack`/`record` 声明卡随载体回来；剥掉载体后不发明声明卡、不把 `__ls_builtin_*` 恢复成用户函数 |
| `reconstructionMatrixTest` | `mindustry.logic.ReconstructionMatrixTest` | 重建矩阵：179 个 fixture / 1106 个 gate 断言，覆盖当前全部控制积木（if/elif/else/for/while/switch/break/continue/函数/折叠变体）、全部数据积木（array/matrix/arrayinit/record/stack/queue/deque/bitset/map/uset/list/heap/chain）、68 个 `datacall` 操作卡与 8 张断言/调试卡；每个 fixture 都断言 compile → carrier restore → `verifyRestore` → 载体反编译链路，并自动检查每个已注册 `datacall` 操作都有 fixture；对 decompiler 可证明的控制流形状额外断言无载体推断路径 |

| `recoveryPredicateTest` | `mindustry.logic.RecoveryPredicateTest` | 谓词树模型：比较运算精确取反、`strictEqual` 不做有损取反、德摩根、优先级打印、求值方式影响代价 |
| `shortCircuitTest` | `logicsugar.ShortCircuitCompilerTest` | `&&` / `||` 下降为条件 `jump`：操作数顺序、OR 续接标签、嵌套括号、`===` 取反不丢精度、坏谓词拒绝 |
| `crossLoaderTest` | `mindustry.logic.CrossLoaderAccessTest` | 以 child-first 加载器复现"模组类与游戏类分属不同运行时包"的拓扑，断言子类访问受保护成员的模式不抛 `IllegalAccessError` |
| `boxSelectTest` | `logicsugar.assist.BoxSelectSelfTest` | 框选拖动策略纯函数：移动端 430ms 长按、桌面 8px slop、斜向/纵向阈值、边界含等 |
| `cfgTest` | `mindustry.logic.MlogCFGTest` | 零依赖 CFG IR：leader 划分、条件/always 跳转边、可达性、支配树、自然循环与回边、多入口形态不误报、越界 jump 不崩、`@counter` 写入与 reads/writes 提取 |
| `lintTest` | `logicsugar.MlogLintTest` | Mlog 静态检查（advisory）：unknown-op（名单转录自 LogicOp）、参数个数（经 LogicIO 双端核对）、对字面量赋值、自跳转/越界跳转、坏 jump 形状、未知指令 INFO；干净程序零误报 |
| `varClipboardTest` | `logicsugar.assist.VarClipboardSelfTest` | 变量导出 TSV 格式：表头、按名排序、全精度数字、对象值走 PrintI 格式化（字符串原样、null） |
| `processorStatusTest` | `logicsugar.assist.ProcessorStatusSelfTest` | 状态指示纯函数：wait 阈值含等判定、阈值 0 关闭、扫描预算按帧时长换算（60FPS 一帧正好 perTick、240FPS 分数进位不丢、低帧率封顶 5×perTick）、扫描档位映射与旧版原始值到档位的一次性迁移 |
| `unitFlagsTest` | `logicsugar.assist.UnitFlagsSelfTest` | 单位 flag 叠加纯函数：0 / NaN / Inf 不绘制、非零有限值显示、整型去掉 `.0`、分数与超 long 范围保持 `Double.toString`、标签锚在 hitbox 上沿、前 10 个不同 flag 使用高对比度配色且后续使用鲜明随机色 |
| `assertTest` | `mindustry.logic.SugarAssertsTest` | 断言语句集：与 MlogAssertions 逐字节线格式、write/parse 往返幂等、`~` 占位定长 token、坏枚举干净报错、strip/emit 编译行为、verifyRestore 双形态、调试构建反编译 round-trip |
| `assertTypeTest` | `mindustry.logic.AssertTypeTest` | `asserttype` 卡（LogicSugar 原生语句）：emit 编译产物行格式与定长 token、emit 行 re-parse/assemble 出 `AssertTypeI` 的接线、strip 模式零泄漏且载体保留、verifyRestore 双形态、`AssertDataType.matches` 语义矩阵（number/null 对象/string/content/building/unit/team 互斥），并钉住未来「内存对象存储」（上游 #12459）场景的 number/对象分型 |
| `arrayTest` | `mindustry.logic.ArraySugarTest` | `array` 声明卡与 `buf[i]` 下标：字面量/变量下标的 `read` 精确行（地址 = base + 下标，base>0 先 `op add`）、下标赋值 `write` 行、越界字面量与未声明名报错、无注册表时退化为普通发射（纯原版不受影响）、严格校验（重名/同内存块重叠/非法 base/size）、声明卡不产行且载体往返、条件表达式 lowering 出 `read` 行、产物纯原版、unfold→fold 折回表达式且再编译流一致；容量口径：名字启发式（`cellN`=64、`bankN`/`worldN`=512、其余跳过）与**链接解析优先**（`resolvedMemoryCapacity`：注入假解析器验证 world-cell 式「512 格的 `cellN`」不再被误拒、解析到更小方块时按真实容量收紧、链接到非内存块返回 0 时不得回落猜测、解析不到才回落名字并把容量标注为推断值、`enterLinkResolver`/`restoreLinkResolver` 配对不泄漏） |
| `arrayBulkTest` | `logicsugar.assist.data.ArrayBulkTest` | 数组批量运算：`array_sum/array_avg/array_min/array_max/array_count/array_find/array_fill/array_copy/array_sort/array_sort_desc/array_reverse/array_replace/array_swap/array_lower_bound` 的表达式展开（实参必须是已声明数组，矩阵按行主序摊平）、旧短名 `min`/`max` 的 1 参（数组运算）与 2 参（原版内置）分派、错误实参编译期报错、内置函数注入与 normal 共享子程序、不进入 `__ls_lib` 载体、未使用不进产物、产物纯原版与往返 |
| `dataFrameworkTest` | `logicsugar.assist.data.DataFrameworkSelfTest` | F2 框架：`ExprIntrinsics` 注册/遮蔽/按 arity 分派/成员读写扩展点、`DataModules` 注册幂等与 collect/restore 配对、`DataDeclaration` 跳过 lower、注入函数并入 `LibraryIndex` 且排除出载体、markInvalid 接线 |
| `recordTest` | `logicsugar.assist.data.RecordTest` | 记录：`record` 卡定长 token 与 `~` 槽位、成员读 `set <tmp> p_f1` / 成员写 `set p_f1 …`、未声明成员的 sensor 回退不受影响、重名/字段冲突/保留前缀等严格校验、编辑期标红、声明卡不产行、产物纯原版、载体往返 |
| `containerTest` | `logicsugar.assist.data.ContainerTest` | 栈/队列/双端队列：`stack_push/stack_pop/stack_top/stack_size/stack_clear`、`queue_push/queue_pop/queue_front/queue_size/queue_clear`、`deque_push_front/deque_push_back/deque_pop_front/deque_pop_back/deque_front/deque_back/deque_size/deque_clear` 的展开（读类直线链、push 走注入函数）、隐藏状态变量与空/满边界（空 pop/peek 返回 NaN、满 push 不写入且返回 -1）、同内存块区间不重叠校验、声明卡不产行、产物纯原版、载体往返与重编译一致、方法糖 `s.top()`/`q.front()`/`d.back()` 与 intrinsic 一致 |
| `bitsetTest` | `logicsugar.assist.data.BitsetTest` | 位集：`bitset_set/bitset_reset/bitset_test/bitset_count` 展开为 and/or/shl/shr + read/write（每 word 64 位）、负下标/越界语义、`bitset_count` 注入函数、容量与区间校验、声明卡不产行、产物纯原版、载体往返、下标糖 `bs[i]` 与方法糖 `bs.test(i)` 与 intrinsic 一致 |
| `mapTest` | `logicsugar.assist.data.MapTest` | 哈希表：`map_set/map_get/map_contains/map_erase/map_size/map_clear` 展开（开放寻址、NaN 空槽、墓碑删除）、未命中返回 NaN（`map_erase` 未命中返回 -1）、NaN/±Inf 键拒绝、容量/布局校验、`map_clear` 初始化要求、声明卡不产行、产物纯原版、载体往返、`m.get(k)` / `m.has(k)` / `m.size()` / `m[k]` 方法糖与 `map_get` 可达性 |
| `setTest` | `logicsugar.assist.data.SetTest` | 无序集合：`uset` 声明卡与 `set_add/set_contains/set_remove/set_size/set_clear`（开放寻址、仅键区、NaN 墓碑）、满表/重复键/墓碑复用、NaN 键拒绝（`set_remove` 未命中返回 -1）、`set_clear` 初始化要求、声明卡不产行、产物纯原版、载体往返、`s.has(v)` / `s.size()` 方法糖与 `set_contains` 可达性 |
| `listHeapTest` | `logicsugar.assist.data.ListHeapTest` | 列表/小顶堆：`vector_push_back/vector_at/vector_set/vector_insert/vector_erase/vector_find/vector_size`、`heap_push/heap_pop/heap_size` 展开与边界（越界 NaN/失败 -1/未找到 -1/空堆 NaN）、计数回写、容量与区间校验、声明卡不产行、产物纯原版、载体往返、下标糖 `l[i]` 与方法糖 `l.get(i)`/`l.size()`、`h.size()` 与 intrinsic 一致 |
| `chainTest` | `logicsugar.assist.data.ChainTest` | 链表：`chain_init/chain_clear/chain_alloc/chain_free/chain_get/chain_set/chain_next/chain_link/chain_set_head/chain_head/chain_len` 展开（读类直线链、写内存与遍历走注入函数）、空闲链重建与 LIFO 分配、摘链/挂回空闲链、越界守卫（chain_get 返回 NaN、chain_set/chain_link/chain_free 失败返回 -1、chain_next 返回 -1）、必须显式 `chain_init` 的初始化要求、重名/保留前缀/容量/区间校验、声明卡不产行、产物纯原版、载体往返、下标糖 `c[i]` 与方法糖 `c.get(i)`/`c.head()` 与 intrinsic 一致 |
| `dataSubsystemTest` | `logicsugar.DataSubsystemIntegrationTest` | INT 生产注册路径：`LogicSugarMod.init()` 幂等（重复 init 不重复 `LogicIO.allStatements` 条目、卡片各一份、parser 与 intrinsic 全部可见）、混合结构端到端编译且 `stripMarkers` 产物纯原版、模块 `collect` 抛异常时 `DataModules.restore()` 仍配对执行且后续编译正常、调色板分类（Advanced Flow Control / Data Structures / Array Algorithms） |
| `dataCallTest` | `logicsugar.assist.data.DataCallTest` | 所有数据 intrinsic 的独立 palette metadata、结构族分类，以及 `datacall` 卡的编辑后持久化、carrier restore/verify 与纯原版 lower；v5 失败值/无结果卡改动后 pre-v5 存档走 legacy lowering 重新验证（篡改仍被拒绝）；运算卡引用的三族 bundle 键（`datacall.<规范名>` / `hint.datacall.<规范名>` / `lst.datacall.group.<组>` / `datacall.arg.<参数名>`）与代码可达集合双向对齐——取不到的键或多写了该有的键都会变红 |
| `editHistoryTest` | `logicsugar.assist.EditHistorySelfTest` | 编辑器撤销/重做快照栈：record/undo/redo、未提交改动并入一次撤销、新编辑清空重做、undo 后改写放弃重做、`applied` 对齐 fold 后文本、深度上限 80 |
| `bottomBarLayoutTest` | `logicsugar.assist.BottomBarLayoutTest` | 底栏行打包纯函数：单行/恰好放下/按容量换行、预算标签按 196px 计算、超宽单元独占一行不被吞、行宽不超限（除独占行）、不丢单元，`fitsOneRow` 与打包一致；手机/窄窗回归：5 个 160px 操作单元在 640px 栏里必须换行而非挤成一行、360/400/480/560/640/720/800/900px 各宽度下每一行都放得下且不丢单元、360px 竖屏操作组按 2/2/1 三行、640px 栏里预算标签会让位（8 个按钮 2 行 → 加标签 3 行）、1920px 宽栏仍是单行；UI 缩放：`scaledWidths` 在 1 倍时与声明宽度等价（打包结果不变），1 / 1.5 / 2 / 2.5 / 3 倍 × 360–1260px 各宽度下每一行都放得下且不丢单元，1260px 手机栏（2.5 倍）七按钮 + 标签按 3/3/2 打包，而未缩放的声明宽度会把七格塞进同一行（那一行真实 2800 场景单位 > 1220 行空间，即居中溢出、首尾按钮掉出屏幕的报告）；另含真实 arc 布局几何：上游 `size(160,64)` 默认值把容器压成一格、清掉继承上限后容器铺满整行、宽栏两组不重叠、窄栏确实会重叠（换行存在的理由）；UI 缩放几何：2.5 倍下 `size(160, 64)` 的声明单元格量到 400 场景单位、三格量到 1200（钉住行打包用的就是这一份宽度，而不是声明数字） |
| `escapePreviewTest` | `logicsugar.assist.EscapePreviewSelfTest` | quoted mlog 字符串转义预览：换行、引号、反斜杠、Unicode、未知/畸形转义及现代能力探测 |
| `v160SensorAccessTest` | `logicsugar.assist.expr.V160SensorAccessSelfTest` | `LAccess.senseablePrivileged` 的跨版本反射访问与旧版 fallback |
| `exprTextImportTest` | `logicsugar.assist.expr.ExprTextImportSelfTest` | 文本导入的一行表达式语句（`x = buf[3]` / `x = (a + b) * 2` / `buf[i] = 5`）：形状识别与保守边界（已注册 token、`==`/`!=`/`<=`/`>=`、注释、字符串、一行多语句、保留哨兵前缀）、哨兵替换保持语句条数与 jump 标签下标、降级成文档承诺的 `read x cell1 3` 且产物不再含 `noop`、载体往返与 `verifyRestore`、重开时 read 行仍能被 foldAll 折回、非法表达式仍是卡片并明确报错 |
| `exprCardTest` | `logicsugar.assist.expr.ExprCardSelfTest` | 表达式卡的画布侧（"添加积木 → Expr" 无反应回归）：调色板默认卡（`result = 0`，一条值拷贝 `set`）必须被识别为「保留卡片」而不是展开成 `set`/`op` 积木；任何一行链都必须有画布语句（未知 `RawLine` 由 `hasUnmappableLine` 上报，调用方保留卡片而不是删掉积木）；展开与不展开写出的文本逐字一致（单行卡跳过展开不改变产物与下标）；单行卡自带 `# @ls-expr-card` 自描述标记（多行卡与单行数组 read/write 不带标记）、标记经 `ExprTextImport` 一对一还原成同一张卡（含引号转义与空 dest）；载体往返：标记不泄漏进可执行产物、`verifyRestore` 仍通过、重开后卡片原样回来 |
| `funclibLimitTest` | `logicsugar.FunctionLibraryLimitTest` | 函数库行数上限：`readLibrary` 解析超过 1000 条语句不截断且用完还原 `LExecutor.maxInstructions`；`libraryOverLimit` 在 10000 条边界正确、`withLibraryLimit` 异常路径也还原；`sanitizedLibrary`/`buildLibrary`/`extractLibrarySource` 都能看到第 1000 条之后的库函数；处理器调用尾部库函数时只嵌入用到的子集并可重编译一致；函数库编辑会话整体 round-trip 不丢内容；单函数体超过 1000 条语句也能解析与校验 |
| `dataRuntimeTest` | `logicsugar.assist.data.DataRuntimeTest` | 数据结构整程序运行：真实 `LExecutor` + 假内存/消息块执行编译产物，覆盖栈/队列/双端队列/位集/列表/小顶堆/链表的 push/pop/peek、满/空边界、非零 base 环回、空容器 NaN 标记，并钉住 `whilebegin` 条件语义（`s.size()` 能抽干容器、`!s.size()` 一次都不进循环）与 v5 值语义（返回 / 堆往返保留对象与 NaN 标记）；另含数组排序内置函数 `__ls_builtin_arrsort`（希尔排序）的运行结果：`array_sort`/`array_sort_desc`、逆序/已排序/重复元素、size=1、非零 base 不越界 |
| `conditionLabelTest` | `logicsugar.ConditionLabelTest` | 循环条件字段的本地化标签：三份 bundle 键集一致、`while.condition`/`for.condition` 不得写成「结束条件 / 终止条件 / until」、提示语保持「为真时重复」，并断言 `WhileBeginStatement` 使用专用键而非通用 `condition`；另钉住数据操作提示的失败值措辞（13 个可失败操作的 `hint`/`lst` 提示在三份 bundle 里都必须写明 v5 的 `-1`，`map_contains`/`set_contains`/`bitset_test` 等查询类必须保持 0/1 而不出现 `-1`） |
| `editorConflictTest` | `logicsugar.EditorConflictTest` | 编辑器所有权与四档冲突策略：`classify` 把「尚未安装 / 游戏自带 `LogicDialog`」判为 native、自家对话框及其子类判为 sugar、别的模组的 `LogicDialog` 子类判为 foreign（不得误判成 sugar）、`EditorConflict.parse` 四档映射与大小写不敏感、缺失/空/垃圾值一律回落 `ask` 而绝不落到「编辑器被停用」、设置按钮 `next()` 一圈必须恰好访问全部状态并回到默认档（漏一个状态就是用户够不到的档）、设置键与四档标签在三份 bundle 里都存在、安装守卫无不对称、选板隐藏可逆、四档切换有接线、共存编译挂在对方关闭路径上且捕异常、退出共存还原对方画布、ask 弹窗三种答案齐全且不含接管分支的私有副本、聚合设置表单与独立设置项默认档一致、共存时重新绑定对方面板且对方面板仍可点击；源码钉子统一走共享的 `SourceNails.readSource()`（归一 CRLF） |
| `textWrapTest` | `logicsugar.assist.TextWrapTest` | `SugarTooltip` 的折行规则（测量函数换成字符数，因此无需图形上下文即可精确断言）：放得下原样返回、空串与 null 安全、只在空格处断且每行不超限、超长单词硬断不丢字符、markup 标签绝不被拆开、已有换行保留、折行幂等 |
| `statementClipboardTest` | `logicsugar.assist.StatementClipboardSelfTest` | 跨处理器剪贴板的片段格式（全程只有语句与字符串，无画布）：`write`/`parse` 对全部语句类型 round-trip、头部标记识别（无正文不算 payload）、接受 CRLF 文本与纯原版 mlog、拒绝不可读文本、`acceptable` 双向判定、`rebase` 把 jump 映射进片段局部下标、选区外跳转被识别为 escaping、无链接的 jump 既不映射也不报 escaping、`pairBlockEnds` 修复片段内 begin/end 配对并拒绝不成对的片段、越界目标计数 |
| `originTest` | `logicsugar.OriginRecordingTest` | 编译期来源通道（`@counter` 指示线的地基）：**产物逐字节不变**（`compileRecorded` 与 `compile` 的返回值在全部 fixture × 两种 FuncMode 下完全相同）、直线语句逐条对应、`for` 的 step 与回跳归到 `blockend` 卡而条件跳转归到 `for` 头、纯原版程序跑 1:1 路径且没有入口 skip、hoist 段（前导跳 / 函数体 / 返回跳板）整段是 `syntheticOrigin`、入口 skip 是 `syntheticOrigin`（hoist 了函数体时也是）、来源数组与正文指令流逐条对齐（口径 = 去标记块去载体去标签行）、带 `funcdef` 时可见主程序下标换算回画布下标、带标签纯原版程序的语句下标不偏位、候选虚影线/失败日志/关屏清理三条接线源码钉子 |
| `counterJumpIndexTest` | `logicsugar.assist.CounterJumpIndexTest` | `@counter` 写入的解析（纯文本工作，无画布）：字面量绝对目标、`op add/sub` 的相对目标（含执行器后自增的 `+1`）、switch 跳转表 / 函数返回跳板 / 变量赋值的形态识别、越界字面量的 `wrap`、载体行不计入指令数、`__ls_stmt_<N>:` 标签归属（经 `mainToCanvas` 换算回画布下标）与函数体内部归属为 `-1`、长度不匹配的 provenance 整体忽略并回落标签启发式 |

```powershell
.\gradlew.bat check        # 全部
.\gradlew.bat decompileTest   # 单跑一个
```

改动对应子系统时必须先跑相关任务；发版前四十二个全绿（见 [release.md](release.md)）。

## 新增测试的约定

- 保持 `main()` + 断言（失败抛 `AssertionError`）风格，新建 `test/` 下与被测类同包的类，并在 `build.gradle` 注册 JavaExec 任务、加进 `check.dependsOn`——这是 AGENTS.md 明文要求。
- 纯逻辑（编译、恢复、谓词、阈值策略）优先做成无头可跑的任务；需要游戏状态的部分模拟到能离线断言的程度（如 `crossLoaderTest` 手工构造加载器拓扑）。
- 触碰 `LAssembler` / 语句解析的测试开头先调 `SugarStatements.installParsers()`（与模组 init 共享的注册点）。
- **新增/修改积木导致编译出的原版代码变化时，必须同步补 `ReconstructionMatrixTest` fixture**：新卡片走 carrier 路径；控制流新形状同时补可证明的 inference fixture。fixture 数必须保持 100+；只改测试数字不算覆盖，必须有 carrier restore + `verifyRestore` + 载体反编译断言。

- 依赖渲染或交互的行为不写自动测试，走下面的手测清单。

## 手测清单

发版或大改动前，至少覆盖：

1. **加载**：模组在桌面客户端正常加载，打开逻辑处理器看到 Sugar 编辑器（`SugarLogicDialog` 接管），原编辑器上的外部浮层（如 MindustryX 面板）仍在。
2. **编译往返 / 重建**：写一段含 `if` / `for` / `while` / `switch` / 函数调用的程序，保存后重开——结构自动折回；"复制编译后代码"按钮拿到的是纯原版 mlog，且无模组客户端也能打开该处理器。含 `array`/`stack` 等声明卡的程序重开后声明卡仍在。纯原版 mlog（无载体）只恢复能验证的 `if`/`for` 等控制流，不凭空长出数据结构卡；手写程序（没有入口 `set @counter 0`）同样要开成 Sugar 视图，带无守卫跳转表的程序要把表折成 `switchbegin … raw`（含 `default`），而不是留在 flat 原版视图——`test/fixtures/realworld-jump-table.mlog` 就是这条的手动夹具（贴进处理器后直接打开编辑器，应看到 switch 卡与 `default`，并弹出恢复提示而不是「外部编辑」）。
3. **双视图**：Original / Sugar 视图切换正常，切换前未保存修改有保护。
4. **函数库**：设置入口打开函数库编辑、保存；把 `functions.txt` 改坏后重进，确认按函数抢救且警告可见。
5. **编辑器辅助**：框选（桌面 Ctrl+点击/拖动复制；移动端长按拖动）、跳转线着色、`__ls_*` 变量在 MindustryX 变量浏览器中隐藏、表达式语句错误标红；桌面 Ctrl+Z / Ctrl+Y 撤销重做，移动端底部 Undo/Redo 按钮。
6. **嵌套布局**：横屏/竖屏及 UI scale 100%/150%/200% 下，展开 1/2/3/4/6 层嵌套 `For`；确认 `variable`、`initial`、`step`、`until`、条件控件、`OP/Expr` 和折叠按钮均在卡片内可见且可点击。切换 MindustryX LogicSupport 侧栏显示/隐藏并重复检查；同时覆盖简体中文、繁体中文和 English。
7. **双形态设置**：独立安装时出现 `Logic Sugar` 设置分类；并入 Neon 后设置项只出现在 Neon 总设置页，无重复分类。
8. **安卓包**：安装 `build/libs/LogicSugar-v<version>.jar`（含 `classes.dex`）于安卓设备，确认能加载并打开逻辑编辑器。
9. **对话框按钮**：打开处理器编辑器两次以上——函数库入口、复制变量、复制打印缓冲按钮每次都在（vanilla `setup()` 每次 show 重建按钮行）；点复制变量得到按名排序的 TSV；函数库会话中两个复制按钮不出现。移动端每次打开都能看到撤销/重做按钮。窗口宽度不足时（例如 800×600 或更窄）按钮换到多行、彼此不重叠；把窗口拉宽后回到「操作居中 / 检查控件贴右」的单行形态。**手机/竖屏同一条宽度路径**：横屏与竖屏各打开一次，返回键与打开函数库按钮都必须完整可见可点（早期版本在这两种形态下退回固定宽度行，首尾按钮被推出屏幕）；竖屏窄于 212px 时指令预算标签不出现属预期，此时超限提示由 toast 承担。
10. **处理器状态指示**：造一个 `stop` 结尾的程序和一个长 `wait` 程序，确认停止处理器上方显示「已停在第 N 条」、长 wait 画进度圆环；把等待阈值滑到 0 后圆环消失；处理器极多的地图无可见卡顿。
11. **断言（调试构建）**：关闭「调试断言构建」时保存含断言的程序，产物 mlog 无 `assert*` 行且无模组客户端可正常打开；开启后保存，断言失败在地图上显示消息（含「(expected X, got Y)」）且程序原地自旋，`breakpoint` 命中时游戏暂停、视角居中到该处理器；开启「断言失败即断点」后失败改为在失败指令处暂停，开启「禁用断点」后 breakpoint 直接跳过；重开编辑器断言卡片完整。与 MlogAssertions 并存装时无重复注册报错。
12. **联机门禁（兼容底线）**：把断言构建设为 emit，然后加入或自建一个多人游戏——此时保存任何程序，产物必须 ≤1000 条且不含 `assert*` 行（与原版客户端互开无异常）；回到单机重新载入地图后，emit 设置恢复生效。指令上限覆盖功能已移除，保存产物恒 ≤1000 条。
13. **数组**：放一张 `array` 卡（如 `buf` / `cell1` / base 0 / size 8），写 `x = buf[i] * 2` 与 `buf[i] = 5`，保存后重开表达式卡折回、产物只有原版 `read`/`write` 行；重名或同内存块重叠的声明卡标红且保存被拦截；无模组客户端能运行同一程序。
14. **数据子系统**：依次放置 `matrix` / `record` / `stack` / `queue` / `deque` / `bitset` / `map` / `uset` / `list` / `heap` / `chain` 声明卡，再从各自分类放置 `array_fill(buf, value)`、`stack_push(s, 1)`、`queue_push(q, 1)`、`deque_push_front(d, 1)`、`bitset_test(bits, 0)`、`map_clear(map)` / `map_set(map, 1, 2)`、`set_clear(s)` / `set_add(s, 1)`、`vector_push_back(l, 1)`、`heap_push(h, 1)`、`chain_init(c)` / `chain_alloc(c)` 等操作积木。保存后重开：声明卡和 `datacall` 操作卡完整，产物只有原版指令且无模组客户端可运行；新增面板不再显示八槽 `arrayinit`，但载入旧 carrier 时仍能显示并正确编译旧卡。检查 `__ls_*` 隐藏变量过滤、记录字段变量可见，以及每类操作出现在对应分类而不是全部挤在 Data Structures。
15. **单位 flag**：设置里打开「显示单位 flag」，给单位设非 0 的 `flag`，确认头顶正上方出现红色数字；再打开「为单位 flag 着色」，给多个单位设置不同 flag，确认同一 flag 颜色一致、不同 flag 优先使用不同鲜明颜色，超过 10 个后仍会分配鲜明随机色；flag 为 0 的单位不显示；关掉显示设置后数字消失。视野外与迷雾中的单位不绘制。
16. **表达式语句文本导入**：清空处理器后把 `array buf cell1 0 8` + `x = buf[3]` 复制进剪贴板，用「加载剪贴板」导入——应得到一张数组声明卡和一张 `x = buf[3]` 的 Expr 卡（不是空的 `noop` 卡）；保存后产物只有 `read x cell1 3` + carrier，重开仍折回两张卡。同法验证 `x = (a + b) * 2`、`buf[i] = 5`，以及写错时（`x = (a +`）卡片标红且保存被拦截；确认普通 mlog（`set` / `op` / `read`）与 `x == 5` 这类比较行导入行为不变。
17. **@counter 指示线**：按 `set @counter 3` / `wait 0.5` / `wait 0.5` / `set @counter 1` / `wait 0.5` 摆五张卡（`wait` 用 0.5 秒以上，便于观察）。左侧应出现两个角标（`3` 与 `1`），各画一条绿线指向对应目标卡：`3` 指向第 4 张（`set @counter 1`），`1` 指向第 2 张（第一个 `wait`）—— **线的两端必须分别落在写入卡与目标卡的左边缘**，绝不能落在别的卡上（2026-09 的错位报告就是指令下标被当成积木序号用）。
    再补三种形状：① `op add @counter @counter 1`（相对跳转，目标应在下方）；② 卡片塞进 `for` 循环体内（目标按产物下标解析，仍应指向正确积木）；③ 函数体内写 `set @counter 1`（**只应有底部灰色小标 + 悬停说明，不应有线**）。最后确认：折叠目标所在块后线消失、框选拖动卡片时线跟随、关闭设置里的「@counter 候选跳转线」后不确定目标只剩角标。
    **锚点是卡片局部坐标，两轴都不含 `elem.x`/`elem.y`**（`localToAscendantCoordinates` 自己会加，写重就是重复计入）。这条只有肉眼能查：摆一段**长程序**，让写入卡在**末尾**、目标卡在**开头**（例如第一张就是目标），线必须仍然贴着两张卡的左缘中点；若目标端被甩到屏幕上方、脱离积木，就是 Y 轴重复计入了 `elem.y`（2026-09-25 报告）。`originTest` 的 `anchorsAreLocalCoordinates` 只钉住代码写法，位置仍以肉眼为准。
    **长程序必须一样能画**（2026-09-25「长逻辑里罢工、编辑一下只闪一帧」报告）：在上面那段长程序里，每张 `@counter` 写入卡都应同时有角标和线；随便点一张卡的按钮（复制/编辑/上下移）后线**不能**消失。查不到线时先看日志：这份功能任何失败都会打一行 `[LogicSugar] @counter indicator line disabled: <原因>`（`noteOnce` 去重），它就是"为什么没画"的答案 —— 静默失效是这条功能最贵的故障模式。每帧路径只允许走 `SugarCanvas.readonlyText()`（`originTest` 的 `overlayUsesReadonlySnapshot` 钉住），`save()` 每帧调会 unfold/fold 重建积木元素、文本还是展开态，且原版 `saveUI()` 对脱离的 jump 目标会抛 NPE。
    **轨道不能重叠**：摆三段互相重叠的 `@counter` 跳转（例如第 1 张跳到第 9 张、第 3 张跳到第 7 张、第 5 张跳到第 6 张），三条线必须落在**不同横向距离**上，不能挤成同一条竖线互相穿插；嵌套那次（第 3→7 在 1→9 内部）应更贴近积木。横向距离来自 `logicsugar.assist.JumpLanes`（原版 `setJumpHeights` 区间着色的镜像），层号本身由 `counterJumpIndexTest` 的 `jumpLanesSeparateOverlappingCurves` 覆盖，这里只看"有没有真的分开"。
    **箭头要咬住目标卡**：目标端的箭头必须**压在那张卡的左缘上**（跨缘约 3/4 在外、1/4 在内），并且**指向卡内**。若箭头整枚漂在卡片左侧、或箭头朝外，就是镜像只翻了 x 偏移没翻宽度（`Tex.logicNode.draw` 的负宽度同时管"跨缘"和"贴图翻转"），`originTest` 的 `overlayRailsFollowLanes` 钉住了这一行。
