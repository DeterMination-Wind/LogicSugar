# 版本与发布

LogicSugar 的版本号体系、本地构建产物链与发布资产规则。原则：**版本号与构建都在本地完成**；仓库内的 CI（`.github/workflows/pr.yml`）只做 `check` 门禁，不承担发布。

## 版本号体系

- 版本号写在 `build.gradle` 的 `version = "…"`（当前链上的值以该文件为准），产物文件名由它派生：`LogicSugar-v<version>.jar` 等。
- `mod.json` 是运行时身份：发布态为 `name: "LogicSugar"`、`version` 与 build.gradle 一致。
- **本地开发态**（工作区默认约定，见上级 `codex/AGENTS.md` 的 Mod Task Default Mode）：`mod.json` 临时改为 `name: "LogicSugar-dev"`、`version: "0.0.0"`，只出 `构建/LogicSugar/LogicSugar-dev.jar` 本地测试产物，不做发布打包。切回发布身份时两处要同步改回。
- 历史版本沿 `v<主>.<次>.<补丁>` 线演进；每个已发布版本在 GitHub Release 上有一份双语（中文 + English）正文，Release 正文即历史事实来源。仓库不保留 `release_notes_*.md` 副本：发布当次可临时生成，发布后删除（`release_notes_v2.3.1-dev.md` 一类的本地开发说明从未对应 Release）。
- 要求 **Mindustry v160.1+**（`mod.json` 的 `minGameVersion: "160.1"`）。v160 已去掉 `LCanvas.useRows()` 并把越界 `read` 改为 null，本仓库已按 v160 API 做兼容。

## 构建产物链

`build.gradle` 中的任务链：

```text
classes ──► d8InputJar ──► dexAndroid ──► jarAndroid ──► deploy ──► copyAndroidJar
            build/d8-input/  d8 --min-api 21  classes.dex +     合并 jar       复制并改名
            LogicSugar-      --release --lib    mod.json +      LogicSugar-    LogicSugar-dev.jar
            d8-input.jar     android.jar        icon + assets   v<version>.jar → 构建/LogicSugar/
```

| 任务 | 产物 | 性质 |
| --- | --- | --- |
| `jar` | `build/libs/LogicSugar-v<version>-desktop.jar` | **桌面中间产物**（纯 class 字节码），永不分发 |
| `d8InputJar` | `build/d8-input/LogicSugar-d8-input.jar` | D8 专用输入，无 dist 副作用 |
| `jarAndroid` | `build/libs/LogicSugar-v<version>-android.jar` | 中间产物（含 dex），不单独分发 |
| `deploy` | `build/libs/LogicSugar-v<version>.jar` | **唯一可分发形态**：桌面 classes + `classes.dex` + 描述符 + 资产 |
| `copyAndroidJar` | `构建/LogicSugar/LogicSugar-dev.jar` | deploy 后自动执行（`finalizedBy`）的本地开发副本改名 |
| `releaseZip` | `build/libs/LogicSugar-v<version>.zip` | 可选：deploy jar + README + LICENSE + sample 图 |

`build` 任务依赖 `deploy`，所以 `./gradlew build` 即可得到完整跨平台产物。

`dexAndroid` 要点：`--min-api 21 --release`，`--lib` 指向选中的 `android.jar`；同时把 compile/runtime classpath（含游戏 Mindustry.jar）以 `--classpath` 传给 d8，供接口/默认方法 desugar 解析——缺了游戏类路径，desugar 阶段无法解析游戏接口。D8 探测顺序 `D8_PATH` → `ANDROID_SDK_ROOT` / `ANDROID_HOME`，`android.jar` 从 SDK `platforms/` 下取最新一个。

## 发布前本地必做步骤

1. 确认版本号：`build.gradle` 与 `mod.json` 同步为发布身份（`LogicSugar` / `<version>`）。
2. 全量自测绿：`./gradlew check`（四十二个任务全过，见 [testing.md](testing.md)）。
3. 本地完整构建：`./gradlew.bat clean deploy`（需要 Android SDK 的 D8 + `android.jar`）。
4. 撰写发布正文（初稿可存为 `release_notes_v<version>.md`）：中英对照、只写当前版本，格式见下节「发布正文风格」；发布后删除该文件，正文以 GitHub Release 为准。
5. 核实产物：`build/libs/LogicSugar-v<version>.jar` 存在且含 `classes.dex`；`-desktop.jar` / `-android.jar` / d8-input jar 不进入发布流程。

## 发布正文风格（Release body）

发布正文就是 GitHub Release 的正文原文（Neon 聚合侧对应 `RELEASE_NOTES.md`），**只写当前版本**、中英对照；仓库只在发布当次临时保留草稿文件。当前范式于 2026-09-25 二次收敛（v5.4.0 范例，用户改写）：正文只保留一行式用户条目，开发期反馈与未发布功能的内部问题不写进 Release。

````markdown
> [!NOTE]
> 需要 **Mindustry v160.1+**（桌面 / Android）
> Requires **Mindustry v160.1+** (Desktop / Android)

## 中文

### 本次新增

- **支持从原版 Mlog 中解析为 LogicSugar 特有的高级控制流**（例如 Switch / If）
- **使用 OP / Expr 对 @counter 进行常量操作时，可以在积木左侧预览类 Jump 跳转线的 @counter 跳转线**

## English

### Added

- **Parse vanilla mlog back into LogicSugar's structured control flow** (Switch / If, for example)
- **Constant @counter writes from OP / Expr cards preview a Jump-like @counter line on the left of the block**
````

写作规则：
1. **结构固定**：顶部只有一个 `> [!NOTE]` 两行引用块（中文一行 + 英文一行）写版本要求，然后 `## 中文`、`## English`。不再用 `> [!IMPORTANT]`，也不写构建命令、产物路径、测试数量与 commit 细节。
2. **小节**：`### 本次新增` / `### 本次修复` / `### 本次改动` / `### 已知问题`，英文对应 `### Added` / `### Fixed` / `### Changed` / `### Known issues`；只保留本版真正涉及的小节，中英小节一一对应。
3. **条目只写一行**：以 `**粗体短标题**` 开头（英文 `**Bold lead-in**`），必要时用括号补一个例子，一句话讲**用户能感知到的结果**；不解释技术细节（类名、方法名、测试名、内部机制与实现原因都不写，留在 commit message 与 `docs/`）。
4. **「本次修复」只写上一个已发布版本里用户能碰到的问题**：开发过程中用户反馈的问题、同一版本内新功能的内部缺陷，用户从未在任何已发布版本里见过，不构成「修复」，不写进 Release；功能首次发布只写它新增的能力。没有上一版真实缺陷时，整个「本次修复」小节省略。
5. **粒度**：一条一件事，同主题合并；宁可少写，也不堆细节。
6. **语言**：中文用中文标点与引号，英文用半角标点；两边各自通顺，不逐字直译。
7. **历史不回填**：新版本一律按本范式写；历史正文以各版本的 GitHub Release 为准（v5.3.1 是格式定稿依据，其正文已随 Release 上线）。

## Release 资产安全规则

Mindustry 游戏内安装器取 Release API 返回的**第一个 `.jar`**，且不按操作系统挑资产。因此：

- 一个 Release 必须有且只有一个 `.jar` 资产，即 `deploy` 的合并 jar（`LogicSugar-v<version>.jar`），发布前核实它含 `mod.json`、桌面主类 `logicsugar.LogicSugarMod` 与 `classes.dex`。
- 严禁把 `*-desktop.jar`、`*-android.jar`、`LogicSugar-d8-input.jar` 等中间产物传上 Release。
- 发布后用 API 复查：
  ```bash
  gh release view <tag> --json assets
  ```
  发现多余 `.jar` 立即删除。

## CI

`.github/workflows/pr.yml` 是仓库内唯一的 workflow：PR 与 main 推送触发，ubuntu + Temurin 17，检查固定使用 Mindustry v160.1 标签对应的 commit；本地可用 `-PmindustryJar=<path>` 指向同版本 JAR，再跑 `bash ./gradlew --no-daemon check`。它只做检查，不产出也不上传任何发布资产。
