# 版本与发布

LogicSugar 的版本号体系、本地构建产物链与发布资产规则。原则：**版本号与构建都在本地完成**；仓库内的 CI（`.github/workflows/pr.yml`）只做 `check` 门禁，不承担发布。

## 版本号体系

- 版本号写在 `build.gradle` 的 `version = "…"`（当前链上的值以该文件为准），产物文件名由它派生：`LogicSugar-v<version>.jar` 等。
- `mod.json` 是运行时身份：发布态为 `name: "LogicSugar"`、`version` 与 build.gradle 一致。
- **本地开发态**（工作区默认约定，见上级 `codex/AGENTS.md` 的 Mod Task Default Mode）：`mod.json` 临时改为 `name: "LogicSugar-dev"`、`version: "0.0.0"`，只出 `构建/LogicSugar/LogicSugar-dev.jar` 本地测试产物，不做发布打包。切回发布身份时两处要同步改回。
- 历史版本沿 `v<主>.<次>.<补丁>` 线演进（`release_notes_v2.1.4.md` 起到 `release_notes_v3.0.1.md`），每个已发布版本配一份双语 `release_notes_v<版本>.md`（中文 + English）。`release_notes_v2.3.1-dev.md` 属于本地开发验证说明，未对应 Release。
- 要求 **Mindustry BE 27771+**（`mod.json` 的 `minGameVersion: "27771"`）。本次 `v4.1.0-be.27771` 为 BE 预发布版本，不面向普通稳定版。

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
2. 全量自测绿：`./gradlew check`（二十五个任务全过，见 [testing.md](testing.md)）。
3. 本地完整构建：`./gradlew.bat clean deploy`（需要 Android SDK 的 D8 + `android.jar`）。
4. 撰写 `release_notes_v<version>.md`（中文 + English 双语，沿用现有格式）。
5. 核实产物：`build/libs/LogicSugar-v<version>.jar` 存在且含 `classes.dex`；`-desktop.jar` / `-android.jar` / d8-input jar 不进入发布流程。

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

`.github/workflows/pr.yml` 是仓库内唯一的 workflow：PR 与 main 推送触发，ubuntu + Temurin 17，检查使用的 Mindustry 版本以 workflow 中固定的对应 commit 为准；本次 BE 发布已使用 Mindustry BE 27771 本地验证，再跑 `bash ./gradlew --no-daemon check`。它只做检查，不产出也不上传任何发布资产。
