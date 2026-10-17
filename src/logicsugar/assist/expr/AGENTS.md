# logicsugar.assist.expr — 表达式子系统

作用域：`ExprCompiler`、`ExprStatement`、`ExprHook`、`ExprTextImport`、`ExprIntrinsics`、`ArrayRegistry`、
`SpanAccess`、`ShortCircuitCompiler` 与各结构 `*Intrinsics`。改这里前先读本文件；流水线背景见
[架构总览](../../../../docs/architecture.md)「表达式子系统」，恢复规则见
[重建规则](../../../../docs/reconstruction.md)。

## 折叠 / 展开契约

- `foldAll`（画布打开/粘贴后）、`unfoldAll`（画布形态）与 `unfoldedText`（保存文本）共用同一个“保不保留
  卡片”规则：`cardLines` + `keepsCard` + `hasUnmappableLine`。不要在任一路径复制一份判断。
- `unfoldAll` 只有链的**每一行**都有 vanilla 语句时才移除卡片；否则保留。任何新的 `ExprCompiler.Line` 子类
  都必须有 `statementFor` 分支（`CopyLine extends RawLine` 的教训：漏了会先删卡再跳过，积木直接消失）。
- 单行卡一律不展开（`keepsCard`）：在保存文本里它本来就占一条语句，而 `foldableChain` 要求链 ≥2 行
  （`read`/`write` 也一样），展开后没有折叠路径能把孤行折回。单行卡的持久化靠自描述 marker，不靠折叠。
- `save()` 走 `ExprHook.unfoldedText`，是**纯文本读取**：只在文本层展开多行卡，并把 jump/begin 记的画布
  语句下标临时换算到文本下标（`finally` 还原），不重建画布元素。不要改回
  `unfoldAll → super.save → foldAll`。emit 模式的自动断言卡只存在于某一次 unfold 内，同一次调用的 fold
  又会移除它，所以去掉画布 unfold 不丢可见内容。

## 链外读取（`hasExternalReads`）

- 扫的是 Expr 卡的**源码**（`expr` / `dest`），不是展开文本：展开里的 `_0/_1…` 是卡片自己的 scratch，按
  文本扫会让先折回的卡把后面同名临时链判成外部读取。
- “定义”不算读：一条语句只写该名字、不读它，就不是链外读取；但链外定义之后又读该名字，读到的不是折叠链
  的值。未识别语句类型仍把每次出现都算读——失败方向是 “fold less, never fold wrong”。
- 必须排除 `SpanAccess.scratchNames()`：三个程序级 scratch 每次展开都会在访问前重写，另一张 span 卡提到
  它们不算外部读取。相关链收集：`appendChainLine` 保持 `__ls_span_q/r` 目标开放，`foldsMemoryLine` +
  `isSpanScratch` 让 building scratch 上的 `read`/`write` 入链。

## span 折叠（编辑器折回，不是 inference）

- `ExprCompiler.resolveArrayFolds` 的 span 视角把“成员 + 格内地址”还原为 `k*C + local`，或按前导段成员序 +
  每格容量用 `ArrayRegistry.findSpanByShape` 反查；前导段行标 `CONSUMED`。两个 span 解释同一展开 ⇒ 返回
  null（“fold less, never fold wrong”），最终由 `verifyArrayFold` 重编译比对兜底。
- `ExprHook.foldsSpanPrologue` 让前导 `select` 行进入折叠链，但 `statementFor` 对 `SelectLine` 必须返回
  null：`unfoldAll` 因此把含不可映射行的链保留为 Expr 卡，画布与保存文本才一致。
- 常量下标是单行 `read`/`write`，**从不折叠**（孤行 `foldableChain` < 2）；靠 marker 还原。
  `dataRuntimeTest.spanRuntime` 还要求展开的 `select` 选中真实内存块对象，不能只是数字。
- 回归：`spanTest.variableReadFoldsOnReopen` 跑真实链收集；`ExprFoldHarness` 是无头入口。

## 容器 getter 的 `Provider.foldAt`

- `ExprCompiler.resolveArrayFolds` 先试数组/矩阵/span 视角，再交给 `ExprIntrinsics.tryFoldAt`；候选仍要过
  `verifyArrayFold` 重编译比对。
- `Provider.foldAt(ops, index)` 返回源节点 + 从 `index` 往前数的窗口长度；末行被替换，前面的行标 consumed。
  `ContainerIntrinsics.foldAt` 不手写形状表：重新编译规范 getter（`s.top()` / `s.size()` / `q.front()` /
  `d.front()` / `d.back()`）逐行比对，`_0/_1…` 槽位是捕获位置（同一槽必须同一实际操作数），末行结果槽
  通配。正向 lowering 改了，反向匹配会立刻失配。
- `Provider.declaresMemory` 是链收集的另一半：声明内存块上的 `read` 必须能入链，否则 `hasExternalReads`
  会把地址临时量当外部读取。`foldAt` 对已认领的 read 保持沉默是安全方向。
- 别名（`s.peek()`、`speek(s)`、`q.peek()`、`d.peekfront()`）都折回规范形式；`size` 族是单行，孤卡靠
  marker，链内（`x = q.front() + q.size()`）由同一钩子在原位认领（`lines == 1`）。
- 变更类操作（`spop`/`qpop`/`dpopf`/`dpopb`/`spush`/`qpush`/`dpushb`/`dpushf`）**故意没有 `foldAt`**：链会写
  隐藏状态，容器在别处使用时会被判外部读取，重开仍是裸指令；这是显式接受的 show vanilla。要加就先扩
  `getterMethods` 并写对应的状态写回处理器。
- 无头决策层：`ExprHook.isChainLine` / `collectChain` / `foldPlan` 是完整判定，画布只做元素增删和 jump
  目标检查；`test/logicsugar/assist/expr/ExprFoldHarness.java` 跑同一份判定。

## Expr 卡持久化（marker / 重写 / hasExternalReads）

- 格式：`# @ls-expr-card <dest> "<expr>"`，由 `ExprStatement.cardMarkerPrefix` 定义、
  `ExprTextImport.cardMarker` 单一序列化；`ExprStatement.write()` 与恢复路径共用。注释不进入可执行流，
  不改变指令数 / 语句数 / `destIndex`。
- **每个被保留的卡都写 marker**，包括单行数组 `read`/`write`（`x = buf[3]`、`buf[i] = 5`）：声明表只说明
  cell 属于数组，不说明这一行是下标访问；没有 marker 时，单行卡会被原版读取积木取代。多行卡不写 marker，
  靠 ≥2 行阈值折回；剪贴板 payload 是唯一把多行卡内联为 `dest = expr` 的例外（`canWriteInline`，且拒绝
  `@` 目标）。
- marker 必须活过文本重写：`SugarCompiler.rewriteStaleBlockDests` 用 `writeStatementsKeepingComments` 原位
  重序列化；`SugarDecompiler.infer` 之后用 `attachCardMarkers` 只在“恢复文本里确实存在该单行展开”时认领，
  每条语句至多一次，过期 marker 丢弃。产物里 marker 嵌在注释块内，`SugarCanvas.load` 先
  `attachCardMarkers` 提升（幂等）。
- marker 的表达式上下文来自文本自身（`ExprTextImport.textDeclarations` → `ArrayRegistry.lenientRegistry`），
  不要用当前画布的声明表。
- `save()` 的文本层展开必须保持画布/语句下标一致：jump 与 begin 的 token 临时换算到文本下标，`finally`
  还原；单行卡写出的文本两种形态相同。

## 文本导入

- `SugarCanvas.load` 先跑 `ExprTextImport.plan`，把原版 parser 不认的 `x = buf[3]` / `buf[i] = 5` /
  `result = (a+b)*2` / `@counter = 0` 一对一换成 `set __ls_import_N 0` 哨兵，parse 后原位换成
  `ExprStatement`。
- 保持 skip list 与 `ExprTextImportSelfTest` 同步；`@counter` 是唯一可写内建，剪贴板内联拒 `@` 目标。
- 细节（哨兵、skip list、可写内建与旧 payload 兼容）见[重建规则](../../../../docs/reconstruction.md)
  「文本导入」。

## 显示与测试

- `ExprStatement.highlight` 只转义 `[`（Arc 把 `[[` 当一个字面 `[`，转义 `]` 会多画一个括号）；error label
  同规则，`selfTest.highlightTextIsUnchanged` 钉住。
- 主要回归：`exprCardTest`、`exprTextImportTest`、`spanTest`、`containerTest`、`arrayTest`、`dataRuntimeTest`。
