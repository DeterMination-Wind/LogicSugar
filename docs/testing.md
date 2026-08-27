# 测试指南

LogicSugar 的自动化测试是 `main()` 断言式的 JavaExec 回归任务（无 JUnit runner），全部挂接在 `check` 上。任何接线改动都不允许把自测任务从 `check.dependsOn` 摘掉；`test` 任务被显式禁用，属正常现象。

## 自动化任务

`build.gradle` 注册了七个自测任务，均 `dependsOn testClasses`：

| 任务 | 主类 | 覆盖内容 |
| --- | --- | --- |
| `selfTest` | `logicsugar.SugarCompilerSelfTest` | 编译器主回归：嵌套结构 round-trip、非法结构报错、语义错误定位、`break`/`continue` 就近退出、if/elif 链、注释往返、生成代码优化与 `@counter` 保护、`SwitchStrategy`（auto/chainOnly）公式与语义网格、跳转链穿线、表达式 `op` 链往返、函数（参数绑定、void/早退、返回值、嵌套/前向/循环内调用、临时名空间）、引号转义 |
| `ifElseTest` | `mindustry.logic.IfElseCompileTest` | `if` / `elif` / `else` / `while` 三段式条件的 lowering 冒烟（负分支取反、标签、出口跳转） |
| `decompileTest` | `mindustry.logic.SugarDecompilerTest` | 反编译恢复：vanilla 程序保持原样、各结构恢复 round-trip、跳转表识别、陈旧载体回退推断、短路谓词恢复、不支持的模式保持 flat、引号/转义、坏输入不崩 |
| `recoveryPredicateTest` | `mindustry.logic.RecoveryPredicateTest` | 谓词树模型：比较运算精确取反、`strictEqual` 不做有损取反、德摩根、优先级打印、求值方式影响代价 |
| `shortCircuitTest` | `logicsugar.ShortCircuitCompilerTest` | `&&` / `||` 下降为条件 `jump`：操作数顺序、OR 续接标签、嵌套括号、`===` 取反不丢精度、坏谓词拒绝 |
| `crossLoaderTest` | `mindustry.logic.CrossLoaderAccessTest` | 以 child-first 加载器复现"模组类与游戏类分属不同运行时包"的拓扑，断言子类访问受保护成员的模式不抛 `IllegalAccessError` |
| `boxSelectTest` | `logicsugar.assist.BoxSelectSelfTest` | 框选拖动策略纯函数：移动端 430ms 长按、桌面 8px slop、斜向/纵向阈值、边界含等 |

```powershell
.\gradlew.bat check        # 全部
.\gradlew.bat decompileTest   # 单跑一个
```

改动对应子系统时必须先跑相关任务；发版前七个全绿（见 [release.md](release.md)）。

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
6. **双形态设置**：独立安装时出现 `Logic Sugar` 设置分类；并入 Neon 后设置项只出现在 Neon 总设置页，无重复分类。
7. **安卓包**：安装 `build/libs/LogicSugar-v<version>.jar`（含 `classes.dex`）于安卓设备，确认能加载并打开逻辑编辑器。
