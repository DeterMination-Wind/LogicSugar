# mindustry.logic — 同包扩展层

作用域：`SugarCompiler`、`SugarDecompiler`、`SugarStatements`、`SugarAsserts`、`SugarCanvas`、
`SugarLogicDialog`、`SugarCoexist`、`SugarFunctions`、`MlogCFG`、`RecoveryPredicate`。改这里前先读本文件；
实现背景见[架构总览](../../../docs/architecture.md)，恢复细节见[重建规则](../../../docs/reconstruction.md)。

## 跨类加载器访问（硬约束）

模组类经 mod class loader 加载，`mindustry.logic.*` 游戏类经 app loader 加载：同包名、**不同运行时包**。
游戏类的 `protected` / 包私有成员只能：

1. 在**本模组的子类实例方法**内访问——`SugarStatement.fieldsHint` 即此模式；`rebuildConditionEditor` 需要被
   静态编辑器调用，所以声明为 public 实例方法。
2. 经 `Field.setAccessible(true)` **反射**访问——`SugarLogicDialog.privilegedField` 是硬反射范式；
   `SugarCanvas` / `globalsDialogField` 用可降级的 optional 反射。

静态 helper 直接访问这些成员**能编译**，运行时 UI 首次渲染才抛 `IllegalAccessError`。包私有字段同理，且
子类规则帮不上忙：`LogicDialog.globalsDialog` 必须走反射；同名的 `executor` 只是因为本类自己声明了遮蔽
字段才安全。静态代码只使用 public 游戏 API。`crossLoaderTest` 同时守行为层（child-first 加载器拓扑）与
形状层（扫描全部已编译类常量池的成员引用，按 JVM 可访问性判），不要绕过。

## 反编译安全门

`SugarDecompiler.verify` 只有把候选源码重新编译、与输入做归一化可执行指令流比对通过后才允许返回恢复结果；
未识别内容回退 vanilla，失败方向永远是“多显示原版”。`verify` 跨完整 `FuncMode × SwitchStrategy` 矩阵
编译，含断言的程序再乘一维 `AssertEmit`。`backtrack` 只在贪心候选验证失败后运行，每次提升的候选同样过门，
且有次数预算。完整规则见[重建规则](../../../docs/reconstruction.md)「安全门」。

## 开屏与卡片闭合

- `SugarDecompiler.openingSource(code, privileged, librarySession)` 返回 `stored` / `inferred` / `raw`：
  有可信载体（或旧标记块、函数库文本）原样载入；没有载体时对**处理器程序**跑推断；函数库文本是糖源码，
  不推断。它是纯静态方法，`decompileTest.editorOpensHandWrittenProgramsAsSugar` 在两种权限下钉住。
- `SugarDecompiler.openableSource(preferred, code, privileged, librarySession)`：首选源码能解析就载入；
  否则载**存储的编译产物**（编译器产出必然可解析）并置 `fallback = true`，丢弃读不出来的草稿；两者都读不了
  才拒绝。`decompileTest.unreadableSourceFallsBackToTheStoredProgram` 钉住三条分支。
- **每张卡的 `write()` 必须能被自己的 parser 读回**（对编辑器能产出的每个值）。清空/乱填字段是卡片状态，
  不是解析错误；parser 不得抛异常，编译路径报带位置错误，编辑器用 `SugarCompiler.invalidStatements` 标红。
  LParser 的 `tokens` 是复用静态数组：空槽必须写 `~`（`optional()` 写、`optionalValue()` 读），否则中间缺
  token 会串到上一行。`funcdef` 非规范声明要引号写回（`funcdef f a "c" 3`），签名规则统一走
  `SugarFunctions.funcDefDeclarationProblem`（编译路径与标红共用）。

## 重建清单（改编译产物时）

- 新卡片/功能必须同时更新 carrier 路径，使 `compile → save → restore → verifyRestore` 后卡片还在；声明卡
  只活在载体里，是**身份元数据**，不是对 vanilla 指令的声明。
- 能从 vanilla 跳转证明的形状补 inference；不能的明确记录 “carrier-only / 重开显示原版”。inference 不得
  发明声明卡，也不得把 `__ls_builtin_*` 蹦床恢复成用户 `funcdef`。
- `test/mindustry/logic/ReconstructionMatrixTest.java` 至少补一个 carrier fixture，矩阵保持 100+；
  `reconstructionTest` 钉住过期 `destIndex` 重配对与载体还原。
- 跑 `reconstructionTest reconstructionMatrixTest decompileTest` 后再宣称完成。
- raw leap/stride、unit-control（`unitIndexMatches` 与 `Candidate.unitCards` 的拒绝/关层语义）的细节只在
  [重建规则](../../../docs/reconstruction.md)；span/getter/Expr 卡的操作规则见
  [expr AGENTS](../../logicsugar/assist/expr/AGENTS.md)，本文件不重复。

## 编辑器渲染不变量

- `SugarCanvas.save()` 必须是**纯文本读取**（`ExprHook.unfoldedText`）：不得
  `unfoldAll() → super.save() → foldAll()`。它被周期性调用，重建 `StatementElem` 会让表达式输入框失焦、
  卡片闪烁。
- 左侧地址标签顺序：先强制 `invalidate()/validate()`，再 `updateMlogAddresses()` 写 mlog 文本，最后清
  `WidgetGroup.needsLayout`。`Label.setText` 会重新 invalidate；顺序反了会让标签在语句下标与 mlog 地址之间
  每帧跳动并抖动表头。`canvasSourceTest` 两者都钉住。

## 测试入口与上游线格式

`crossLoaderTest`、`decompileTest`、`reconstructionTest`、`reconstructionMatrixTest`、`canvasSourceTest`、
`selfTest`、`assertTest` / `assertTypeTest`。断言线格式与上游基线的差异（`asserttype` 的 `null` 类型与
token 顺序、`snapshot` 5 槽）见[上游基线](../../../docs/upstream.md)；改 `asserttype` / `snapshot` 前先看
`SugarAsserts.AssertTypeCard` 的注释与 `assertTypeTest`。
