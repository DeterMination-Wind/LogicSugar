# logicsugar.assist.data — 数据子系统

作用域：`DataModule` / `DataModules` / `DataDeclaration` / `DataCallStatement` 与各结构 `*Module`。改这里前
先读本文件；实现说明见[架构总览](../../../../docs/architecture.md)「数据子系统」，玩家语义见
[高级数据类型教程](../../../../docs/tutorials/README.md)，恢复规则见
[重建规则](../../../../docs/reconstruction.md)。

## 声明卡与模块注册

- 声明卡是**编译期元数据**：lower 时整张剥离，不发射任何指令；所有操作只输出原版
  `read`/`write`/`op`/`funccall`/`jump`，保证原版客户端可解析、联机安全。
- 新结构是 3 个文件：`src/logicsugar/assist/data/<Module>.java`、
  `src/logicsugar/assist/expr/<Module>Intrinsics.java`、`test/logicsugar/assist/data/<Module>Test.java`；再加
  `LogicSugarMod.registerStatements()` 里**恰好一行** `DataModules.register(new <Module>())`。（`Provider`
  实现必须放 `expr` 包：`Node` / `Line` 是包私有类型。）
- 不在注册列表之外硬编码模块。`DataModules.registerParsers()` 负责装声明卡 parser；注册按 `id()` 幂等。
- 双端队列不是新模块：`ContainerModule` / `ContainerIntrinsics` 的扩展。无序集合 token 用 `uset`
  （原版 opcode `set` 禁用）。

## 注入函数（`__ls_builtin_*`）

- 模块通过 `builtinSugar()` 提供 `funcdef __ls_builtin_*` 源文本，经 `SugarFunctions.withBuiltins` 并入本次
  编译的 `LibraryIndex`。它们不得进入用户函数库或 `__ls_lib` 载体（`extractLibrarySource` 只看用户库文本）；
  未使用不进产物；normal 模式全程序共享一份子程序。
- **改函数体是兼容性决定，不是局部编辑**：函数体已经烘焙进旧存档，`SugarCompiler.verifyRestore` 用
  `matchesStoredStream` / `executableStream` 逐条比对，任一行变化都会让旧处理器过不了载体验证、重开成
  vanilla 视图（只活在载体里的声明卡随之丢失）。已接受的断代：`sortasc` / `sortdesc` 插入排序 → 希尔排序。
  改之前明确“接受断代”还是“给 body 做版本化”，并同步 `docs/architecture.md` 与对应教程。

## getter 两阶段解析

- lowering 用 `ExprIntrinsics.Provider.kindOf` / `methodIntrinsic` / `indexIntrinsic` 对当前模块注册表解析；
  analyze 用 `DataModules.declaredKinds(statements)` + `ExprIntrinsics.enterDeclaredKinds`，`collectCallNodes`
  才能把方法/下标解析成 intrinsic 并发出 root-intrinsic `CallSite`。
- builtin-backed 别名（`mapget`、`uhas`、`lfind`、`bcount`、`cnext`、`clen`）依赖 analyze 侧解析；缺了它
  normal 模式不会 hoist `__ls_builtin_*` 函数体。
- 下标糖只读：`l[i] = v` 是编译错误，提示 `lset` / `bset` / `cset`；不提供 mutator 方法糖。

## datacall 卡

- 每个注册的 intrinsic 同时是一张持久 `datacall` 卡；palette metadata 属于该 `DataModule`（参数默认串、
  是否有源级结果）。有结果的卡默认 `result = op(args)`；void 卡不写目标，lower 时把实现哨兵丢进私有
  `__ls_*datacall_discard`。
- lower 阶段必须复用 `ExprIntrinsics`，这样载体恢复能保留操作卡、可执行产物仍是 vanilla。
- 旧八槽 `arrayinit` 只保留可读兼容，不再提供新卡；新程序用 Array Algorithms 的 `fill(buf, value)`。
- **运算名是 canonicalize，不是简单别名**：`DataCallStatement.canonicalOperation()` /
  `DataModules.canonicalOperation()` 是卡面、tooltip 键、标题与写回载体的唯一来源；任何读原始 `operation`
  字段的显示 / `write()` 路径都会让旧名重新出现在编辑器与下一次保存里。未知名字保持不动，让编译错误准确。

## 编译期配对与校验边界

- `SugarCompiler.compile` 必须保留 `DataModules.collectAll(...)` / `DataModules.restore()` 的 `try/finally`
  配对，且 pairing flag 在 `collectAll` **之前**置位；任一模块 `collect` 抛异常也不能把注册表泄漏到下一次
  编译或编辑器渲染。
- 跨模块校验按设计是逐模块的：每个模块只校验自己的声明 + `array` / `matrix`。不同模块间的名字/区间冲突
  （如 stack 与 list 落在同一段内存）**不拒绝**；不要把它塞进单个模块。真要做，先给 `DataModules` 加程序级
  名字/区间表，并先在 `docs/architecture.md` 写清。
- 永不改变 `array` 四 token 线格式或任何声明卡的 token 数；隐藏状态变量保持
  `__ls_<kind>_<name>_<field>`（用户不得使用 `__ls_`）；空容器 pop/peek 返回 NaN。
- 修改声明/操作后跑 `dataFrameworkTest`、`dataCallTest`、`dataSubsystemTest`、`dataRuntimeTest` 以及对应
  结构测试；新增可执行语义时补 `dataRuntimeTest` 的真实 `LExecutor` 用例。

## 恢复与测试

新 module/card/intrinsic 完成的标准是：使用它的程序能走 carrier restore + `verifyRestore` 后声明卡仍在，
并在 `ReconstructionMatrixTest` 留 carrier fixture；每个 `datacall` 操作还必须被矩阵的自动检查覆盖。细节见
[重建规则](../../../../docs/reconstruction.md)「载体还原」「重建清单」。
