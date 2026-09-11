# LogicSugar 文档

LogicSugar 的分类文档。文档以中文为主，功能名保留英文本名（`ifbegin` / `ExprCompiler` / `SugarDecompiler` 等），便于与代码和编辑器界面对照。

## 从哪里开始

| 你是 | 从这里开始 |
| --- | --- |
| 玩家 | [README_zh.md](../README_zh.md)（功能与安装说明） |
| 想了解 LogicSugar 怎么组织的人 | [架构总览](architecture.md) |
| 本分支三项调查（STL / 函数库 / 正式版 vs BE） | [概要报告](investigation-overview.md) · [技术报告](investigation-technical.md) |
| 想改 LogicSugar 代码 | [开发指南](development.md) 与[架构总览](architecture.md) |
| 改动需要验证 | [测试指南](testing.md) |
| 准备发版 | [版本与发布](release.md) |
| 遇到不认识的词 | [术语表](glossary.md) |

## 文档地图

```text
docs/
|-- README.md            本页：文档导航
|-- investigation-overview.md   feat/arrays-l10n-v160prep 调查报告（无技术细节）
|-- investigation-technical.md  同上，含实现与 API 细节
|-- architecture.md      架构总览：双形态、编译器、重建（载体/反编译）、表达式子系统、数据子系统、跨类加载器约束、上游版本适配笔记
|-- development.md       开发指南：环境、构建命令、代码风格、调试建议
|-- release.md           版本与发布：版本号体系、构建产物链、Release 资产安全
|-- testing.md           测试指南：JavaExec 自测任务、新增测试约定、手测清单
`-- glossary.md          术语表
```

## 相关文件

- [README.md](../README.md) / [README_zh.md](../README_zh.md)：面向玩家的功能与安装说明（英文 / 中文）。
- [AGENTS.md](../AGENTS.md)：仓库维护约束，重点是**跨类加载器访问陷阱**、**反编译恢复安全门**与**重建（每加功能都要考虑）**。
- [mod.json](../mod.json)：模组描述符（入口 `logicsugar.LogicSugarMod`、最低 Mindustry BE 构建 `27771`）。
- [build.gradle](../build.gradle)：编译依赖、二十六个自测任务与 Android 打包管线的唯一事实来源。
- `release_notes_v<版本>.md`：每个版本的双语（中文/English）发布说明，见 [版本与发布](release.md)。

## 维护约定

- 架构文档描述"怎么实现"，README 描述"用"。同一主题两边都出现时，README 从玩家操作视角写，架构文档从代码结构写。
- 涉及 `mindustry.logic.*` 游戏类成员访问的结论必须与 `AGENTS.md` 保持一致；两处冲突时以 `AGENTS.md` 为准并同步修订。
- 自测任务名以 `build.gradle` 为准；新增或删除任务后必须更新 [测试指南](testing.md) 的任务表。
- 版本相关数字（当前版本号、产物文件名）以 `build.gradle` 与 `mod.json` 为准，文档只写规则不写快照，避免过期。
