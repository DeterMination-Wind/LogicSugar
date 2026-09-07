# 测试指南

LogicSugar 的自动化测试是 `main()` 断言式的 JavaExec 回归任务（无 JUnit runner），全部挂接在 `check` 上。任何接线改动都不允许把自测任务从 `check.dependsOn` 摘掉；`test` 任务被显式禁用，属正常现象。

## 自动化任务

`build.gradle` 注册了十四个自测任务，均 `dependsOn testClasses`：

| 任务 | 主类 | 覆盖内容 |
| --- | --- | --- |
| `selfTest` | `logicsugar.SugarCompilerSelfTest` | 编译器主回归：嵌套结构 round-trip、非法结构报错、语义错误定位、`break`/`continue` 就近退出、if/elif 链、注释往返、生成代码优化与 `@counter` 保护、`SwitchStrategy`（auto/chainOnly）公式与语义网格、跳转链穿线、表达式 `op` 链往返、函数（参数绑定、void/早退、返回值、嵌套/前向/循环内调用、临时名空间）、引号转义、载体分片（大源码 `__ls_sugar_N`/`__ls_lib_N` 分片还原、小程序单条形状回归） |
| `ifElseTest` | `mindustry.logic.IfElseCompileTest` | `if` / `elif` / `else` / `while` 三段式条件的 lowering 冒烟（负分支取反、标签、出口跳转） |
| `decompileTest` | `mindustry.logic.SugarDecompilerTest` | 反编译恢复：vanilla 程序保持原样、各结构恢复 round-trip、跳转表识别、陈旧载体回退推断、短路守卫重建布尔树（单原子 / 顶层 `!` / 嵌套 `&&`\|\|` / 左深链 / `whilebegin`/`forbegin` 的 `exprsc` / `continue` 内部回边）、贪心候选失败后的回溯提升、验证矩阵（chainOnly 保存的程序在 auto 默认设置下仍验证）、动态 `@counter` 分诊保持 flat、函数区杂散跳转验证、不支持的模式保持 flat、引号/转义、坏输入不崩 |
| `recoveryPredicateTest` | `mindustry.logic.RecoveryPredicateTest` | 谓词树模型：比较运算精确取反、`strictEqual` 不做有损取反、德摩根、优先级打印、求值方式影响代价 |
| `shortCircuitTest` | `logicsugar.ShortCircuitCompilerTest` | `&&` / `||` 下降为条件 `jump`：操作数顺序、OR 续接标签、嵌套括号、`===` 取反不丢精度、坏谓词拒绝 |
| `crossLoaderTest` | `mindustry.logic.CrossLoaderAccessTest` | 以 child-first 加载器复现"模组类与游戏类分属不同运行时包"的拓扑，断言子类访问受保护成员的模式不抛 `IllegalAccessError` |
| `boxSelectTest` | `logicsugar.assist.BoxSelectSelfTest` | 框选拖动策略纯函数：移动端 430ms 长按、桌面 8px slop、斜向/纵向阈值、边界含等 |
| `cfgTest` | `mindustry.logic.MlogCFGTest` | 零依赖 CFG IR：leader 划分、条件/always 跳转边、可达性、支配树、自然循环与回边、多入口形态不误报、越界 jump 不崩、`@counter` 写入与 reads/writes 提取 |
| `lintTest` | `logicsugar.MlogLintTest` | Mlog 静态检查（advisory）：unknown-op（名单转录自 LogicOp）、参数个数（经 LogicIO 双端核对）、对字面量赋值、自跳转/越界跳转、坏 jump 形状、未知指令 INFO；干净程序零误报 |
| `varClipboardTest` | `logicsugar.assist.VarClipboardSelfTest` | 变量导出 TSV 格式：表头、按名排序、全精度数字、对象值走 PrintI 格式化（字符串原样、null） |
| `processorStatusTest` | `logicsugar.assist.ProcessorStatusSelfTest` | 状态指示纯函数：wait 阈值含等判定、阈值 0 关闭、扫描预算分数进位与 1.5×perTick 下限 |
| `assertTest` | `mindustry.logic.SugarAssertsTest` | 断言语句集：与 MlogAssertions 逐字节线格式、write/parse 往返幂等、`~` 占位定长 token、坏枚举干净报错、strip/emit 编译行为、verifyRestore 双形态、调试构建反编译 round-trip |
| `assertTypeTest` | `mindustry.logic.AssertTypeTest` | `asserttype` 卡（LogicSugar 原生语句）：emit 编译产物行格式与定长 token、emit 行 re-parse/assemble 出 `AssertTypeI` 的接线、strip 模式零泄漏且载体保留、verifyRestore 双形态、`AssertDataType.matches` 语义矩阵（number/null 对象/string/content/building/unit/team 互斥），并钉住未来「内存对象存储」（上游 #12459）场景的 number/对象分型 |
| `arrayTest` | `mindustry.logic.ArraySugarTest` | `array` 声明卡与 `buf[i]` 下标：字面量/变量下标的 `read` 精确行（地址 = base + 下标，base>0 先 `op add`）、下标赋值 `write` 行、越界字面量与未声明名报错、无注册表时退化为普通发射（纯原版不受影响）、严格校验（重名/同内存块重叠/非法 base/size）、声明卡不产行且载体往返、条件表达式 lowering 出 `read` 行、产物纯原版、unfold→fold 折回表达式且再编译流一致 |

```powershell
.\gradlew.bat check        # 全部
.\gradlew.bat decompileTest   # 单跑一个
```

改动对应子系统时必须先跑相关任务；发版前十四个全绿（见 [release.md](release.md)）。

## 新增测试的约定

- 保持 `main()` + 断言（失败抛 `AssertionError`）风格，新建 `test/` 下与被测类同包的类，并在 `build.gradle` 注册 JavaExec 任务、加进 `check.dependsOn`——这是 AGENTS.md 明文要求。
- 纯逻辑（编译、恢复、谓词、阈值策略）优先做成无头可跑的任务；需要游戏状态的部分模拟到能离线断言的程度（如 `crossLoaderTest` 手工构造加载器拓扑）。
- 触碰 `LAssembler` / 语句解析的测试开头先调 `SugarStatements.installParsers()`（与模组 init 共享的注册点）。
- 依赖渲染或交互的行为不写自动测试，走下面的手测清单。

## 手测清单

发版或大改动前，至少覆盖：

1. **加载**：模组在桌面客户端正常加载，打开逻辑处理器看到 Sugar 编辑器（`SugarLogicDialog` 接管），原编辑器上的外部浮层（如 MindustryX 面板）仍在。
2. **编译往返**：写一段含 `if` / `for` / `while` / `switch` / 函数调用的程序，保存后重开——结构自动折回；"复制编译后代码"按钮拿到的是纯原版 mlog，且无模组客户端也能打开该处理器。
3. **双视图**：Original / Sugar 视图切换正常，切换前未保存修改有保护。
4. **函数库**：设置入口打开函数库编辑、保存；把 `functions.txt` 改坏后重进，确认按函数抢救且警告可见。
5. **编辑器辅助**：框选（桌面 Ctrl+点击/拖动复制；移动端长按拖动）、跳转线着色、`__ls_*` 变量在 MindustryX 变量浏览器中隐藏、表达式语句错误标红。
6. **嵌套布局**：横屏/竖屏及 UI scale 100%/150%/200% 下，展开 1/2/3/4/6 层嵌套 `For`；确认 `variable`、`initial`、`step`、`until`、条件控件、`OP/Expr` 和折叠按钮均在卡片内可见且可点击。切换 MindustryX LogicSupport 侧栏显示/隐藏并重复检查；同时覆盖简体中文、繁体中文和 English。
7. **双形态设置**：独立安装时出现 `Logic Sugar` 设置分类；并入 Neon 后设置项只出现在 Neon 总设置页，无重复分类。
8. **安卓包**：安装 `build/libs/LogicSugar-v<version>.jar`（含 `classes.dex`）于安卓设备，确认能加载并打开逻辑编辑器。
9. **对话框按钮**：打开处理器编辑器两次以上——函数库入口、复制变量、复制打印缓冲按钮每次都在（vanilla `setup()` 每次 show 重建按钮行）；点复制变量得到按名排序的 TSV；函数库会话中两个复制按钮不出现。
10. **处理器状态指示**：造一个 `stop` 结尾的程序和一个长 `wait` 程序，确认停止处理器上方显示「已停在第 N 条」、长 wait 画进度圆环；把等待阈值滑到 0 后圆环消失；处理器极多的地图无可见卡顿。
11. **断言（调试构建）**：关闭「调试断言构建」时保存含断言的程序，产物 mlog 无 `assert*` 行且无模组客户端可正常打开；开启后保存，断言失败在地图上显示消息且程序原地自旋，`breakpoint` 命中时游戏暂停；重开编辑器断言卡片完整。与 MlogAssertions 并存装时无重复注册报错。
12. **联机门禁（兼容底线）**：把断言构建设为 emit，然后加入或自建一个多人游戏——此时保存任何程序，产物必须 ≤1000 条且不含 `assert*` 行（与原版客户端互开无异常）；回到单机重新载入地图后，emit 设置恢复生效。指令上限覆盖功能已移除，保存产物恒 ≤1000 条。
13. **数组**：放一张 `array` 卡（如 `buf` / `cell1` / base 0 / size 8），写 `x = buf[i] * 2` 与 `buf[i] = 5`，保存后重开表达式卡折回、产物只有原版 `read`/`write` 行；重名或同内存块重叠的声明卡标红且保存被拦截；无模组客户端能运行同一程序。
