# LogicSugar — Agent Notes

Mindustry Java mod：在逻辑编辑器里提供 `for` / `while` / `switch` / 函数等结构化积木，保存产物仍是原版可解析的 mlog。
工作区通用规则（构建模式、`LogicSugar-dev / 0.0.0` dev 身份、发布安全）在上级 [codex/AGENTS.md](../AGENTS.md)；
本文件只写项目级硬约束、构建入口与路由，模块细节见各目录 `AGENTS.md`。

## 硬约束

- **多人兼容底线**：保存到处理器的代码必须在任何原版客户端上可解析、可运行；这是 mod 的存在前提，优先级高于一切新功能。
  只有会改变保存产物语义的调试功能（目前是 `AssertEmit=emit`）需要单机门禁：代码层限定
  `!Vars.net.active()`，联机一律回落原版；纯展示类功能不受此限。处理器保存产物恒 ≤1000 条，是硬不变式，
  不提供指令预算覆盖（历史上曾试做，已移除）。全局函数库 `functions.txt` 不是处理器产物，另有
  `SugarFunctions.libraryInstructionLimit`（10000 条语句）；库文本必须用 `SugarFunctions.readLibrary`
  解析，超限由 `libraryOverLimit` 在保存/打开编辑器时拒绝；使用库函数的处理器产物仍 ≤1000 条。单机越界内容被分享到多人环境的残余风险，只能靠设置描述与
  文档讲清。新功能提案先按此底线分类：不碰保存产物 → 正常实现；碰保存产物 → 必须加联机门禁，并在
  bundle 与 `docs/architecture.md` 说明单机限定。实现细节见 [架构总览](docs/architecture.md)「设计约束」「函数与全局函数库」。
- **重建是完成的定义**：任何会改变编译产物的积木/卡片/功能改动，必须在同一改动里补齐 carrier 还原
  与可推断形状的 inference，或明确记为“重开只显示原版”。恢复必须过 verify 门，失败方向永远是
  “多显示原版”。新增/修改卡片至少补一个 `reconstructionMatrixTest` fixture（矩阵保持 100+）；
  改完跑 `reconstructionTest reconstructionMatrixTest decompileTest`。
- **跨类加载器**：模组类与 `mindustry.logic.*` 游戏类同包名、不同运行时包。受保护/包私有成员只能在
  本模组子类实例方法内访问，或经 `Field.setAccessible(true)` 反射访问；静态 helper 编译能过、运行时
  `IllegalAccessError`。改该目录前读 [src/mindustry/logic/AGENTS.md](src/mindustry/logic/AGENTS.md)，
  `crossLoaderTest` 会拦。
- **反编译 verify 门**：`SugarDecompiler` 只有把恢复源码重新编译、与输入归一化可执行指令流比对通过后
  才能返回；未识别的保持原版。新恢复模式一律放在门后；`backtrack` 只在贪心候选失败后运行且同样过门。
  细节见 [重建规则](docs/reconstruction.md)「安全门」。
- **上游基线**：断言/断点子系统与调试工具对齐 cardillan/MlogAssertions `v0.11.8`；跟进历史、明确不移植项
  与线格式例外见 [上游基线](docs/upstream.md)。

## 构建与验证

```powershell
.\gradlew.bat check    # 全部 main() 式 JavaExec 自测；完整清单以 build.gradle 的 check.dependsOn 与 docs/testing.md 任务表为准
.\gradlew.bat deploy   # 可分发合并 jar（桌面 classes + classes.dex）；需 D8/Android SDK；本地副本 构建/LogicSugar/LogicSugar-dev.jar
```

只做桌面编译检查可跑 `.\gradlew.bat jar`（不产出可分发 jar）。

自测约定（`main()` + 断言、注册 `check.dependsOn`）与手测清单见 [测试指南](docs/testing.md)；
`tools/maplab`、`tools/mlog2sugar` 独立于 Gradle，见 [开发指南](docs/development.md)。

## 模块与文档地图

| 路径 | 何时读 |
| --- | --- |
| [src/mindustry/logic/AGENTS.md](src/mindustry/logic/AGENTS.md) | 编译器/反编译器/语句卡/画布/对话框（同包扩展层） |
| [src/logicsugar/assist/expr/AGENTS.md](src/logicsugar/assist/expr/AGENTS.md) | 表达式、Expr 卡、span/getter 折叠与恢复 |
| [src/logicsugar/assist/data/AGENTS.md](src/logicsugar/assist/data/AGENTS.md) | 数据模块、声明卡、datacall、`__ls_builtin_*` |
| [src/logicsugar/vars/AGENTS.md](src/logicsugar/vars/AGENTS.md) | 调试 UI、快照、Profiler（含 `profile/`） |
| [docs/architecture.md](docs/architecture.md) | 实现结构、双形态、编译/恢复流水线、上游 v160 适配 |
| [docs/reconstruction.md](docs/reconstruction.md) | 重建规则：载体/推断、raw/stride、unit-control、span、getter、Expr 保存恢复 |
| [docs/upstream.md](docs/upstream.md) | MlogAssertions 基线、跟进历史、明确不移植项、线格式例外 |
| [docs/testing.md](docs/testing.md) | 自测任务表、新增测试约定、手测清单 |
| [docs/development.md](docs/development.md) / [docs/release.md](docs/release.md) | 环境/工具/产物链 / 版本与发布 |
| [docs/api-v5.md](docs/api-v5.md) / [docs/glossary.md](docs/glossary.md) | v5 契约 / 术语 |
| [docs/README.md](docs/README.md) | 文档导航与维护约定 |

## 按 diff 路由

| 改什么 | 先读 |
| --- | --- |
| `src/mindustry/logic/`（编译/反编译/卡片/画布/对话框） | `src/mindustry/logic/AGENTS.md` → `docs/reconstruction.md` |
| `src/logicsugar/assist/expr/` 或 Expr/span/getter 折叠 | `src/logicsugar/assist/expr/AGENTS.md` → `docs/reconstruction.md` |
| `src/logicsugar/assist/data/` 声明卡/datacall/builtin | `src/logicsugar/assist/data/AGENTS.md` → `docs/architecture.md`「数据子系统」 |
| `src/logicsugar/vars/`、`profile/`、调试 UI/快照 | `src/logicsugar/vars/AGENTS.md` → `docs/architecture.md`「变量/Profiler」 |
| 设置项 / Neon 聚合 | `docs/architecture.md`「双形态」 |
| 构建、测试、发布 | `build.gradle` → `docs/testing.md` / `docs/release.md` |
| 文档本身 | `docs/README.md` |

## Neon 聚合

本仓库是 Neon 聚合 mod 的 `ls` 子模组真源；双形态由 `LogicSugarMod.bekBundled` / `bekBuildSettings(SettingsTable)` 处理，
设置项必须在 `LogicSugarSettings.setup(...)` 与 `bekBuildSettings(...)` 两处都注册（或书面说明刻意例外）。
细节见 [架构总览](docs/architecture.md)「双形态」。

## Git 纪律

- 提交前 `git status --short` 与 `git diff --check` 干净；只 stage 本次相关文件，不提交 `build/`、`构建/` 等产物。
- 不 push；不修改上级工作区根 `AGENTS.md` 或其他兄弟仓库。
- 实现/命令事实以代码、`build.gradle`、docs 为准；AGENTS 只约束代理行为与流程，发现文档过时同步修正。
