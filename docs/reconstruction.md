# 重建规则（从原版 mlog 恢复 Sugar）

本文是“打开已保存处理器”这条链路的权威细节文档：载体还原、反编译推断、编辑器折叠层、卡片持久化与
对应测试。架构流水线概览见[架构总览](architecture.md)「重建（reconstruction）」；代理行为约束见
[根 AGENTS.md](../AGENTS.md) 与 [src/mindustry/logic/AGENTS.md](../src/mindustry/logic/AGENTS.md)。实现与命令事实以代码、
`build.gradle` 和 docs 为准。

## 完成定义与失败方向

**只要新增积木/卡片/功能会改变编译出的原版 mlog，或修改现有 lowering 会改变产物，就必须在同一次改动里
补齐对应的从原版代码重建逻辑；只改 lowering 不算完成。** “能编译”不是完成：保存的程序必须能重新打开并
恢复出源码级卡片/结构，或者把恢复缺口明确记录为“重开只显示原版”。

- 两条路径都必须过 verify 门：**载体还原**（优先、无损）与**反编译推断**（无载体时）。
- 门比较的是剥掉载体/标记后的**可执行 mlog 指令流**，不是 Base64 元数据。
- 失败方向永远是“多显示原版代码”，绝不改写未知程序。
- 新的可执行控制流形状要么能被 inference 恢复（并带 fixture），要么在本文明确记为 carrier-only。
- 新增卡片至少补一个 `reconstructionMatrixTest` carrier fixture；矩阵保持 100+。

## 安全门

`SugarDecompiler` 只有在把候选源码重新编译、并与输入做归一化可执行指令流比对通过（`verify`）后才允许返回
恢复结果；任何未识别的部分回退为原版语句。新恢复模式一律放在门后。

- **验证矩阵**：`verify` 跨完整的 `FuncMode × SwitchStrategy` 矩阵编译候选，因为程序可能在别的用户设置下
  保存；候选或原始程序含断言时再乘一维 `AssertEmit`（strip/emit）。无断言程序维持 2×2。
- **`backtrack`**：贪心候选验证失败后才运行的有界重试；在一个决策点提升一个替代候选，且每个被提升的结果
  必须再次通过同一道门。`Candidate.unitCards` 这层可关闭：unit 候选失败时先关层重跑，通过则保留旧读法，
  再落到 `backtrack`。
- **载体验证**：`SugarCompiler.verifyRestore` 重新编译恢复源码，与存储流逐条比较，比较经
  `matchesStoredStream` / `executableStream`（剥掉载体，并把 v1 的 `op add d s 0` 与 `set d s` 归一）。
  它会尝试当前与 legacy 两套 v5 API、`FuncMode` 各值、断言程序的两种 `AssertEmit`，以及有无入口 skip
  两个纪元；只有精确匹配才接受载体。
- **折叠验证**：`ExprCompiler.resolveArrayFolds` 的每个存活候选仍要过 `verifyArrayFold` 的重编译比对
  （`foldPlan`）。Provider 不可能折出编译器不会逐字重发的形状。

## 门的两次归一化

手写 mlog 或其它工具产出的程序既没有载体，也没有本编译器输出总带的两个归一化特征。两者缺失时，再好的
理解也会让整段程序回退成 flat vanilla；夹具 `test/fixtures/realworld-jump-table.mlog` 是历史案例。

### 入口 skip 的两个纪元

`SugarCompiler.compile` 会追加 `set @counter 0`（`entrySkipLine`），所以任何含 sugar 的候选都比输入多一条
指令。`verify` 现在对每个候选编译**两个纪元**（有 skip / 无 skip），与 `SugarCompiler.verifyLowering` 对
存储存档的做法一致；`compileWithoutEntrySkip` 是只给门用的公开入口。保存恢复后的视图仍会补上 skip——
这是有意的、语义不变的（程序跑出末尾同样回绕到 0）。

### 数字跳转链 + 标签穿线

`compile` 运行 `threadAlwaysJumpTargets`，把 `jump A always` 改写到 A 链的末尾。该 pass 按**标签**读链，
而手写程序用指令下标寻址、通常没有标签，所以旧的 `threadAlwaysJumpTargets(original)` 比较目标在这些程序上
是 no-op。`SugarCompiler.threadNumericJumpTargets` 在语句下标上应用同一不动点：只处理无条件跳转，环保持
原目标，行结构与指令数不变。

`verify` 对比较目标**两个 pass 都做**（先 numeric 再 label）：numeric 覆盖手写下标寻址程序，label 覆盖
存储的标签寻址输出，后者不受 numeric 影响。两个归一化都不削弱门：结果与输入行为等价，且都是编译器本来
就会对自己输出做的变换。

## 开屏决策

### `SugarDecompiler.openingSource`

`openingSource(code, privileged, librarySession)` 返回要载入的源码与模式：

- `stored`：有可信载体（`__ls_sugar` / `__ls_lib`）、旧标记块，或这是函数库文本——原样载入。
- `inferred`：没有载体可信时的**验证过的推断**——显示恢复提示，并保留 Original 视图可达。
- `raw`：原样载入代码（推断也没通过）。

推断只在没有载体可信时运行，且只对**处理器程序**运行；函数库文本是糖源码，不是程序，不推断。它是纯静态
方法，因为原来的缺口长在无头测试够不到的 UI 代码里；`decompileTest.editorOpensHandWrittenProgramsAsSugar`
在两种编辑器权限下都钉住这个决策（普通处理器 `privileged == false`，恢复测试常用 `true`）。

### `SugarDecompiler.openableSource`

开屏预检不能直接拒绝：保留中的草稿解析失败时，每次重开都会撞同一面墙，用户再也没有修程序的办法。
`openableSource(preferred, code, privileged, librarySession)` 的决策依次是：

1. `preferred`（草稿，或 `openingSource` 选出的源码）能解析 → 载入它。
2. 否则载入存储的 `code`——编译器产出，必然可解析——并置 `fallback = true`，调用方丢弃读不出来的草稿并
   弹提示说明。
3. 两者都解析不了才返回 `source == null`，保留原来的拒绝，并把解析错误带在 `OpenDecision.failure` 里。

同样是纯静态方法；`decompileTest.unreadableSourceFallsBackToTheStoredProgram` 钉住三条分支。

### 卡片 write/parse 闭合

回退只是安全网，**每张卡的 `write()` 必须能被同一张卡的 parser 读回，对编辑器能产出的每个值都成立**。

- 清空/乱填字段是**卡片状态**，不是解析错误；parser 不得对用户能编辑出的值抛异常（历史缺口：`funcdef`
  的返回声明与函数名，`span` / `array` / `matrix` / `arrayinit` 的名字与内存 cell）。
- 编译路径报带位置的具体错误，编辑器用 `SugarCompiler.invalidStatements` 标红。
- **LParser 陷阱**：`tokens` 是复用的静态数组，中间缺一个 token 会让后续槽位静默串到上一行的残留值。空槽
  必须写成 `~` 占位（写用 `optional()`，读用 `optionalValue()`）。
- `funcdef` 的 v5 形态用“第三个槽是不是整数”决定含义，所以非规范声明要**加引号**写回
  （`funcdef f a "c" 3`）；否则 `returns = "3"` 会被读回成 legacy `destIndex`。
- 签名规则住在 `SugarFunctions.funcDefDeclarationProblem`，编译路径（抛错并定位）与编辑器标红共用，两者
  不会各说各话。

## 载体还原

`__ls_sugar` / `__ls_lib` 是首选路径。`ifbegin` / `forbegin` / … 上的 `destIndex` 是**跳转注释**：若它过期，
但 `begin` / `blockend` 嵌套仍良构，就在 `validatePairs` 之前从最内层匹配重新配对。数据结构的声明卡只活在
这份载体里，从不出现在原版 mlog 中。

新 module/card/intrinsic 完成的标准是：使用它的程序能走 `restore` + `verifyRestore` +
`SugarDecompiler.decompile`（载体路径）往返，并且卡片仍在。`reconstructionTest` 钉住这道门；
`reconstructionMatrixTest` 用 100+ fixture 覆盖当前每个积木/卡片、每个 `datacall` 操作与断言/调试卡
（实际 fixture / gate 数量由测试运行时打印，测试强制 ≥100）。

**声明卡是身份元数据，不是对原版指令的声明。** `decl.array.exprcard` 钉住有标记的情形：载体视图保留
`# @ls-expr-card` 标记，使 `read x cell1 3` 成为 `x = buf[3]` 卡；`decl.array.vanillaRead` 钉住无标记的
情形：同样的声明 + `read x cell1 3` 必须继续作为原版读积木打开，绝不能凭声明变成 `buf[3]`。

### 重建清单

- 更新 carrier 路径，使新卡片/功能活过 `compile → save → restore → verifyRestore`。
- 形状能从原版跳转证明时补 decompiler inference；不能时在本文明确记录 “carrier-only / 重开显示原版”。
- 给 `test/mindustry/logic/ReconstructionMatrixTest.java` 至少补一个 carrier fixture；每个可证明的新形状
  再补 inference fixture。矩阵必须保持 100+，且新积木没有重建覆盖时应当失败。
- 声称功能完成前跑 `reconstructionTest reconstructionMatrixTest decompileTest`。

## raw leap table 与 `default`

手写 `@counter` 跳转表没有边界守卫：`op add @counter @counter <v>` 后跟每条槽位一条无条件跳转行。用编译器
的*带守卫*表去还原会多两条指令并夹紧越界值，等于静默改写程序，所以形态写进源码：

- `switchbegin <value> <dest> raw`（可选第 4 个 token；缺省 = 带守卫）只发射派发 + `span` 条槽位行，且
  **忽略 `SwitchStrategy`**——形态是程序属性，这也是门的策略矩阵能接受它的原因。非法裸表（非整数 case、
  跨度 > `MAX_TABLE_SPAN`）是编译错误。
- `default:` 是 switch 自己的“无 case 命中”卡片。比较链里它是末尾跳转的目标；跳转表里它是所有空槽行的
  目标，带守卫形态下边界守卫也指向它（越界值落在这里）。每个 switch 至多一个、必须在 switch 内；
  `defaultViolations` 是编译器、编辑器标红与函数库构建共用的一条规则。
- 裸表推断（`tryBareSwitchTable`）：槽位 *k* 直接寻址第 *k* 行，所以 case 值就是行号、跨度从 0 开始。
  一行的目标按无条件跳转链（作者自己的穿线）读取。目标落在体区之外的行是空槽，它们必须同指一处，该处
  成为 `default`——并且必须落在**已存在**的指令上（其自身跳转链终于同一处），这样重编译出的空槽行才会
  落到真实目标。跨度两端若正好是空槽，就用一个 case 标签钉住（标签与 default 共位，编译出的行相同）。
  任何不吻合都返回 null，视图保持 vanilla。
- 带守卫表的推断同样恢复 `default`：守卫与空槽行落在 switch *内部*的体上而不是出口，此时 switch 真正的
  结尾是各 case 体 break 跳转的目标（`switchEndBeyond`）。两种读法都作为候选给出——体里跳出 switch 的
  跳转在这一层与 default 无法区分——由门裁决。

## stride table

手写单位控制器常用乘法派发，而不是每槽一条跳转行：`op mul <tmp> <idx> <stride>` 后接
`op add @counter <tmp> <K>`（绝对：case `v` 是第 `v*stride+K` 条指令），或 `op add <tmp> @counter <tmp>`
加 `op add|sub @counter`（相对：第一个 case 是三条派发指令的下一行）。case 正文**就是**槽位，每槽恰好
`stride` 条指令；最后一个短槽只允许在它跑到区域末尾时存在。

恢复写 `switchbegin <idx> <dest> stride <n> <tmp> abs|rel`。常数 `K` 在编译时按第一条正文的指令下标重算，
所以产物逐字节不变、也不会存过期地址。不是两个完整槽、或槽尾没有共同的 `end` / 共同无条件跳转尾的，
一律保持 vanilla。动态 `@counter` 分诊只把 `recognizeStride` 接受的派发当成已知安全形状。

## unit-control

`unitbind` / `unitnext` / `unitfor` / `unitfree` 以前是 carrier-only：没有载体的程序（把 `unitfor` 的产物
粘回 mlog）会重开成一叠 `ifbegin` 卡，忠实但不可用。现在由 inference 恢复，但不是从跳转形状猜：
`tryUnitCard` 按编译器自己的 lowering 逐条比对私有名字 `__ls_ub_uid` / `__ls_ub_<kind>_<index>`（与
`__ls_sw_*` 表同一思路），所以手写 `ubind` / `@flag` 代码永远不会命中。`unitfor` 帧保留作者写的体区——
卡片自己的投递行与 step/回边之间——所以体内的 `break` / `continue` 仍是语句，嵌套块、循环、提升的函数体
都能恢复。`unitnext` 也保留顶部的 `held` 复核，但 `take` 会清空 `held`；`unitNextFrame` 按这个一次性形状
匹配，扫描本身不接受任何非零 flag。

两条性质是承重的：

- **`unitIndexMatches`：宁可拒绝，不要恢复。** lowering 用“正在 lower 的那张语句表的序号”给临时变量编号
  （`__ls_ub_n_3`），编译器重编译恢复后源码时会按卡片的新位置重新生成这些名字；卡片若会落在另一个序号
  （它前面的零指令卡、函数体不同的 item base），就无法复现指令流。拒绝匹配会保留之前的读法；硬恢复却过不了
  门，会连累程序里其它所有可恢复结构。
- **这一层可关闭（`Candidate.unitCards`）。** unit 候选过不了门时，`infer` 关掉这层重跑；通过就采用旧读法
  （本层存在前的结果），仍失败才继续 `backtrack`。新层只能改善视图，不能弄丢已经工作的恢复。

夹具：`ReconstructionMatrixTest` 的 `unit.*` 条目是 `addInferred`（carrier + inference；`unit.for.body`
就是被报告的程序），`decompileTest.unitControlCardsRecoverFromTheirLowering` 双向钉住——恢复出的块，以及
改了一条扫描守卫、必须保持 vanilla 的 lowering。

## span

`span` 卡只活在载体里，索引表达式保存后的文本也是：`x = buf[i]` 写成 `op idiv` / `op mod` / N×`select` /
`read`/`write`（常量索引折成成员块上的一行 `read x cell1 3`）。Decompiler inference 故意不发明 span 卡，
所以能把这份文本变回用户编辑的卡片的只有 `ExprHook.foldAll`：载体文本*就是*程序的 sugar，折不回来的卡片
每次重开都会被静默换成裸块。三层都是承重的，由 `spanTest` 钉住：

- **`ExprCompiler.resolveArrayFolds` 的 span 视角**：把 memory/address 重新表达为 (span alias, logical
  address)。成员 + 字面量格内地址 → `k*C + local`；或前导段的成员表 + 每格容量，用
  `ArrayRegistry.findSpanByShape` 反查，`idiv` 的被除数（`q*C + r`）就是 logical address。前导段行标
  `CONSUMED`，随折叠消失。两个 span 都能解释同一展开 ⇒ null（“fold less, never fold wrong”）；
  `verifyArrayFold` 仍用重编译决定每个存活候选。
- **`ExprHook.foldsSpanPrologue`** 让前导 `select` 进入折叠链，但 `statementFor` 对 `SelectLine` 必须继续
  返回 null：`unfoldAll` 会把含不可映射行的链保留为 Expr 卡（`hasUnmappableLine`），这才能让画布卡与保存
  文本一致。
- **`ExprHook.hasExternalReads` 必须排除 `SpanAccess.scratchNames()`**。那三个名字是程序级 scratch，每次
  展开都会在访问前重写；另一张 span 卡提到它们不算外部读取——不排除的话两张相邻 span 卡互相拒绝折叠，
  谁也回不来。scratch 写入也不能结束链：`ExprHook.appendChainLine` 让 `__ls_span_q/r` 目标像临时量一样
  保持开放，building scratch 上的 `read`/`write` 必须入链（`ExprHook.foldsMemoryLine` → `isSpanScratch`）。
  少任一半，只有*常量*索引形态能折；文本级折叠测试看不到，因为它不跑链收集。

常量形态是单行，孤立的 `read`/`write` 行**永不折叠**（`ExprHook.foldableChain` 要求 `chain.length() >= 2`）。
声明只说明哪些 cell 属于数组，不说明某行是下标访问——数组卡默认区间（`cell1 0 8`）与原版 Read/Write 块默认
目标（`cell1` / 地址 `0`）重合，只凭声明表折叠会把用户手拖的原版块改写成 `x = buf[3]` / `buf[i] = 5` 卡。
单行卡靠自己的 `# @ls-expr-card` 标记还原；`spanTest` 同时钉住标记、`ExprTextImport.plan` 往返，以及
重建出来但孤立的 `read x cell2 0` 重开仍是原版行。运行时覆盖是 `dataRuntimeTest.spanRuntime`：展开的
`select` 必须选中**建筑对象**（纯数字 `select` 会让游戏里所有 span 访问失效，而所有形状测试仍通过）。

另有两个变量地址链的承重细节：前导段的 `op idiv` / `op mod` 目标是程序级 scratch（不是 `_n` 临时量），
不能结束链；尾部的 `read`/`write` 落在 building scratch 而不是 span 成员上，`isArrayMemory` 看不到它。
两处都缺失时，只有常量索引形态还能折。`spanTest.variableReadFoldsOnReopen` 通过真实链收集钉住
read/write/matrix 三种形态。

## container getter（`Provider.foldAt`）

容器 getter 卡（`x = q.front()`、`s.top()`、`d.back()`）是多行，保存文本就是展开、不带标记，所以走的
同一层恢复（编辑器折叠），不是 inference。一条 `op add _1 <base> <head>` … `read x cell2 _1` 链以前要么
拒绝折叠，要么折成一条游离的地址计算卡。

- **门不变**：`ExprCompiler.resolveArrayFolds` 先试 array/matrix/span 视角（不变），再把行交给
  `ExprIntrinsics.tryFoldAt`；每个存活候选仍过 `verifyArrayFold` 的重编译比对（`foldPlan`）。
- **`Provider.foldAt(ops, index)`** 返回源节点 + 从 `index` 往回数的窗口长度（最后一行被节点替换，之前的
  行标 consumed）。`ContainerIntrinsics.foldAt` 不手写形状表：它重新编译规范 getter 形式
  （`s.top()` / `s.size()` / `q.front()` / `d.front()` / `d.back()`）并逐行比对，pattern 的 `_0, _1, …` 槽
  是捕获位置（同槽 ⇒ 同实际操作数，编号本身可以不同），窗口最后一行的结果槽通配。正向 lowering 一改，
  反向匹配立刻大声失配；`containerTest` 的 `(kind × getter)` 表会红。
- **`Provider.declaresMemory`** 是链收集那一半：结构声明内存上的 `read` 必须入链，否则链在它前面断掉，
  `hasExternalReads` 会把地址临时量当成外部读取。`foldAt` 对已认领的 read 保持沉默是安全方向：链被跳过，
  什么也不改写。
- **别名收敛到规范形式**：`s.peek()`、`speek(s)`、`q.peek()`、`qpeek(q)`、`d.peekfront()` … 都编译成同一
  流，所以折回成 `s.top()` / `q.front()` / `d.back()`。`size` 族（`s.size()`、`ssize(s)`、`q.count()`）是
  单行：孤卡继续靠 `cardMarkerPrefix`，在更大的链里（`x = q.front() + q.size()`）由同一 provider 钩子在
  原位认领（`lines == 1`，不消耗行）。
- **`ExprHook.hasExternalReads` 扫卡片的源码，不扫展开**：展开里的 `_0/_1…` 是卡自己的 scratch；扫文本会
  让第一张折回的卡把后面同名临时量的链判成外部读取，第二张卡再也回不来。
- **变更类操作故意仍是 show vanilla**：`spop` / `qpop` / `dpopf` / `dpopb` / `spush` / `qpush` / `dpushb` /
  `dpushf` 没有 `foldAt` 条目。它们的链会写隐藏状态（容器在别处使用时 `hasExternalReads` 立刻判外部读取），
  结尾的 `read` 也不是 getter 窗口，所以这类卡重开仍是裸 op——显式接受的方向。要加就先扩 `getterMethods`
  并写对应的状态写回处理器，而不是另发明一条恢复路径。

**折叠决策必须能无头到达。** `ExprHook.isChainLine` / `collectChain` / `foldPlan` 是完整决策，故意 public；
画布循环只加元素手术与画布专属的跳转目标检查。`test/logicsugar/assist/expr/ExprFoldHarness.java` 在语句
列表上跑同一份判定，`containerTest` 与 `spanTest` 就是对着它断言——文本级 `rebuild` 测试看不到从未被收集
的链。

## Expr 卡持久化

**`ExprStatement` 卡必须活过 `save()`——按块处理，不是格式细节。** 每次调色板插入都会触发
`SugarCanvas.addAt → afterMutate → SugarLogicDialog.recordCanvasHistory → canvas.save()`，而 `save()` 是
**纯文本读取**（`ExprHook.unfoldedText`），从不碰画布。`canvasSourceTest.saveIsAPureTextRead` 钉住纯度，
`exprCardTest.unfoldedTextMatchesTheUnfoldedCanvas` 钉住文本。

- `ExprHook.unfoldedText`（画布入口）/ `unfoldedText(List<LStatement>, boolean)`（无头）只在**文本层**把
  多行卡展开成 op 行，使用与 `unfoldAll` 相同的 `ExprHook.toStatements` 展开和相同的“保不保留”决策。
  jump 与 begin 卡记录的是*画布*语句下标，所以这些 token 会临时改写为*文本*语句下标（`finally` 还原）；
  否则多行卡之后的 jump 在文本重新解析后会指向错误语句。
- 因此 `save()` 不能是 `unfoldAll() → super.save() → foldAll()`：那会在每次调用时重建语句元素，而它每 24
  帧（指令预算横幅）以及第三方编辑器共存态每帧被调用，会让聚焦的 Expression 输入框失焦、卡片闪烁；
  一次拒绝的折叠（外部读取、重建失败）还可能把卡片永久降级成裸 op。
- `unfoldAll`（画布）不再在保存路径上，只作为同一展开的*画布*形态（和测试）；**“保不保留”规则住在
  `cardLines` + `keepsCard` + `hasUnmappableLine`，两条路径共用**，不要在任一路径复制。
- `unfoldAll` 只有链的**每一行**都有 vanilla 语句时才替换卡片（`hasUnmappableLine`），否则保留卡片。
  `CopyLine extends RawLine`：v5 值拷贝形态（`x = 0`、`x = a` → `set x 0`）曾命中“未知 RawLine → skip”
  分支，而卡已经被移除，积木直接消失。任何新的 `ExprCompiler.Line` 子类都必须有 `statementFor` 分支。
- 单行卡一律不展开（`keepsCard`）：在保存文本里它本来就占一条语句，而任何折叠门都救不回孤行
  （`ExprHook.foldableChain` 对每条链都要求 `>= 2` 行，`read`/`write` 也不例外），展开只会把卡降级成普通块。
  `ExprStatement.write()` 两种形态写出的文本相同（`exprCardTest` 钉住相等）。

### 标记

持久化靠自描述标记 `# @ls-expr-card <dest> "<expr>"`（`ExprStatement.cardMarkerPrefix`）。它是注释：
可执行流、语句数、所有 `destIndex` 都不变；`ExprTextImport` 在载入时把它变回卡片。

- **每个被保留的卡都写它，包括单行数组 `read`/`write` 卡**（`x = buf[3]`、`buf[i] = 5`）：声明表只说哪些
  cell 属于数组，从不说某行是下标访问，所以标记是唯一的身份证据；没有它，卡片会静默重开成原版
  read/write 块（≤5.7.1 写的存档接受这一降级：产物逐字节相同，新建一张 Expr 卡就能带回下标）。
- 多行卡继续靠 `>= 2` 的折叠阈值，保存文本里**故意不带标记**（在那里合并 N 行会移动下标）；**剪贴板
  payload** 是唯一把多行卡内联写出的地方（`dest = expr`，经 `ExprTextImport.canWriteInline`），因为片段由
  我们自己的粘贴路径重新解析——每卡一行保持片段的跳转偏移有效，并原样恢复卡片（`statementClipboardTest`）。
  保存格式改变时给 `reconstructionMatrixTest` 补 fixture。
- **标记是证据，必须活过每次文本重写。** 单行卡展开后的行与普通 `set`/`op` 块逐字节相同，标记是唯一区别；
  两条会重新序列化语句的路径过去会连同注释一起丢掉它：
  - `SugarCompiler.rewriteStaleBlockDests`（存储载体里的过期 `destIndex` 注释）现在**原位**重新序列化
    （`writeStatementsKeepingComments`），注释、标签、空行保持在原处，而不是从解析后的语句表重建。
  - 反编译器结构恢复（`SugarDecompiler.infer`）在验证之后通过 `ExprTextImport.attachCardMarkers` 重新挂
    标记：只有恢复文本里已经包含该标记展开出的那条单行语句（用宽松函数检查器跑 `ExprCompiler.compile`，
    一行）才认领；每条语句至多被认领一次；过期标记（程序在 LogicSugar 外被改过）匹配不到任何东西就丢弃，
    而不是把语句改写成它从来不是的卡。插入的是注释，重编译门忽略它们，所以验证后挂是安全的。
    `decompileTest.expressionCardMarkersSurviveRecovery` 钉住两半。
  - 在保存的*产物*里，标记只嵌在注释标记块中（`# @logic-sugar-line # @ls-expr-card …`），而
    `ExprTextImport.plan` 只把标记与它正上方的代码行配对——所以 `SugarCanvas.load` 会先提升标记
    （`attachCardMarkers(asm, asm)`；幂等，已带标记的语句不动）。`ExprTextImport.cardMarker` 是格式的单一
    序列化点：`ExprStatement.write` 与恢复都走它。`exprCardTest.markerSurvivesTextRewrites` 钉住重写、
    提升与过期标记拒绝；`reconstructionMatrixTest` 的 `decl.exprcard.staleDest` fixture 钉住载体路径。
  - 标记表达式的注册表上下文来自文本自身（`ExprTextImport.textDeclarations` → `ArrayRegistry.lenientRegistry`），
    不是当前画布：重开/推断时画布还挂着上一个程序（或空），而产物里的声明卡只以
    `# @logic-sugar-line array …` 注释存在，用画布会让 `x = buf[3]` 编译成 `read x buf 3`，匹配不到任何东西。
    `exprTextImportTest.markerAttachmentUsesTheTextsOwnDeclarations` 双向钉住。

### `hasExternalReads`

`foldAll` 只有在链的临时量没有被链外**读取**时才折叠——**定义不算读**。规则只数真实读取：一条语句只写
一次该名字、从不读它，是*定义*；链外定义之后再读该名字，读到的是外部值，不是折叠链的值。未识别的语句
类型仍把每次出现都算读取——失败方向是“fold less, never fold wrong”。`exprCardTest` 的
`duplicateChainsShareTempsWithoutBlockingEachOther`、`arrayTest`、`spanTest` 双向钉住。

### 编译期常量 / 单位 getter 的重建边界

- `@__...` 常量在表达式字段里被替换成数字，产物只含 `set`/`op`；原始 token 只留在载体、条件源文本或单行 Expr 卡的 `# @ls-expr-card` marker 注释中，
  因此条件卡重开仍显示 `unit_family(u) == @__AIR__` 这类源文本。
- `unit_family` / `unit_tier` / `unit_planet` 没有 `Provider.foldAt`：多行 Expr 卡按展开行保存，重开时由
  通用 op 链重建得到一个**等价公式**，不保证函数名回来。不要把「函数名可读」写进持久化承诺；需要稳定
  名字时写在条件卡里。
- 分类表冻结在 v160：扩范围或改语义会改变已保存条件/载体源的重编译指令流，`verifyRestore` 判失败后
  存档退回 vanilla 视图。语义变更应新增 intrinsic 名字/版本，而不是原地改表。

### 显示

`ExprStatement.highlight` 给每个 token 包 `[color]…[]`。Arc 的 markup 把 `[[` 当一个字面 `[`、不处理 `]`，
所以只转义 `[`；把 `]` 转义成 `]]` 会在卡上多渲染一个括号（`result = list[1]` 曾显示成 `list[1]]`）。
卡片的错误 label 同规则。`selfTest.highlightTextIsUnchanged` 剥掉 markup 后断言可见文本等于用户输入。

## 文本导入

`SugarCanvas.load` 先跑 `ExprTextImport.plan`：原版 `LParser` 无法分派的行（`x = buf[3]`、`buf[i] = 5`、
`result = (a + b) * 2`、`@counter = 0`）被换成唯一的 `set __ls_import_N 0` 哨兵（一行换一行，标签/跳转下标
不移动），解析后再把哨兵替换成 `ExprStatement` 卡。之后就是正常的 `unfoldAll`/`foldAll` 路径，所以产物仍是
纯原版 mlog，上文的载体覆盖原样适用。

- 新增糖行形态时保持保守 skip list 同步（见 `ExprTextImportSelfTest`）：首个 token 已被 `LogicIO.read` 或
  `LAssembler.customParsers` 占用的行、比较（`==`/`!=`/`<=`/`>=`）、字符串、注释与一行多语句都必须不动。
- `@` 开头的目标只接受唯一可写内建 `@counter`（`@unit = 5` 留给原版 parser——写它本来就是 no-op）。
- 剪贴板的单行形态（`canWriteInline`）**故意仍拒绝 `@` 目标**，因为 payload 可能被旧版 LogicSugar 读取，
  而旧版的 import 不认识它们。

## 夹具、测试与历史案例

- `reconstructionTest`：过期 `destIndex` 的世界处理器样例按嵌套重配对后载体仍验证；世界处理器样例走载体
  还原出 `ifbegin`/`forbegin`；`array`/`stack`/`record` 声明卡随载体回来；剥掉载体后不发明声明卡、不把
  `__ls_builtin_*` 恢复成用户函数。
- `reconstructionMatrixTest`：100+ fixture 覆盖全部控制积木、数据积木、`datacall` 操作与断言/调试卡；
  每个 fixture 断言 compile → carrier restore → `verifyRestore` → 载体反编译，并检查每个已注册 `datacall`
  操作都有 fixture。`unit.*` 是 `addInferred`；`decl.exprcard.staleDest`、`decl.exprcard.getter` /
  `decl.exprcard.getter.size`、`decl.array.exprcard` / `decl.array.vanillaRead` 是载体侧的身份与折叠钉子。
- `decompileTest`：`realworld-jump-table.mlog`（手写无载体、带无守卫跳转表）、
  `editorOpensHandWrittenProgramsAsSugar`、`unreadableSourceFallsBackToTheStoredProgram`、
  `unitControlCardsRecoverFromTheirLowering`、`expressionCardMarkersSurviveRecovery`。
- `spanTest`：`variableReadFoldsOnReopen`（真实链收集）、常量形态标记 + import 往返；`containerTest`：
  `getterFold`（kind × getter、别名、链内 size、载体端到端与反例）；`exprCardTest` / `exprTextImportTest` /
  `dataRuntimeTest.spanRuntime` 覆盖保存、导入与运行时对象选择。
- 声称完成前跑 `reconstructionTest reconstructionMatrixTest decompileTest`。
