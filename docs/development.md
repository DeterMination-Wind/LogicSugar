# 开发指南

环境、日常构建命令与代码风格要点。仓库级硬性约束（跨类加载器访问、反编译安全门、dev 身份）在根目录 [AGENTS.md](../AGENTS.md)，与本文冲突时以 AGENTS.md 为准；实现层面的解释见[架构总览](architecture.md)。

## 环境

- **JDK 17**（`build.gradle` 设定 `options.release = 17`，与 Mindustry v155 运行时一致）。CI 用 Temurin 17。
- **Mindustry 依赖**：编译与测试都指向工作区本地构建产物 `../Mindustry-master/desktop/build/libs/Mindustry.jar`（`compileOnly` + `testImplementation`）。本地没有该 jar 时无法编译；CI 的做法是检出 Mindustry `v155.4` 固定 commit 先跑 `desktop:dist`（同时需要配套 Arc 检出）。
- **Android 打包**（只有要出 dex 时才需要）：本地 Android SDK 的 D8 与至少一个 platform 的 `android.jar`，按 `D8_PATH` → `ANDROID_SDK_ROOT` / `ANDROID_HOME` 顺序探测；缺失时 `dexAndroid` 直接失败。
- `gradle.properties` 给 Gradle JVM 加了 `jdk.compiler` 多个包的 `--add-exports` 与 `--illegal-access=permit`，属环境基础设施，勿随意删减。
- Windows 下命令用 PowerShell（`.\gradlew.bat …`）；CI 在 Linux 上用 `bash ./gradlew`。

## 常用命令

```powershell
# 全部自测（十二个 JavaExec 任务，接线见 testing.md）
.\gradlew.bat check

# 单跑某个自测任务
.\gradlew.bat selfTest
.\gradlew.bat decompileTest
.\gradlew.bat crossLoaderTest

# 跨平台发布 jar（桌面 classes + classes.dex），并复制到 构建/LogicSugar/LogicSugar-dev.jar
.\gradlew.bat deploy

# 等价于完整构建（build 依赖 deploy）
.\gradlew.bat build

# 只编译检查（不打包）
.\gradlew.bat compileJava
```

注意：

- `test` 任务被显式 `enabled = false`（本项目不用 JUnit runner）；真正的回归全部是 `main()` 式 JavaExec 任务，挂接在 `check.dependsOn` 上。
- `jar` 任务产物 `LogicSugar-v<版本>-desktop.jar` 是**桌面中间产物**（纯 class 字节码，安卓无法加载），不要分发；可分发形态只有 `deploy` 的合并 jar。
- `releaseZip` 产出附 README/LICENSE/sample 的 zip，是可选的发布外包装。

## 产物链

```text
classes ──► d8InputJar ──► dexAndroid ──► jarAndroid ──► deploy ──► copyAndroidJar
            (仅 class,      (d8 --min-api 21 (classes.dex+      (合并 jar:      (复制到
             无副作用)       --release --lib     描述+资产)       classes+dex)   构建/LogicSugar/
                             android.jar)                                        LogicSugar-dev.jar)
```

细节（`--classpath` 传入游戏类做接口 desugar 等）见 [release.md](release.md)。

## 代码风格要点

- 跟随 Mindustry 上源风格：4 空格缩进、大括号不换行、`if(...)` 无空格；文件一律 UTF-8（构建已强制 `options.encoding`）。
- 用户可见文案一律走 bundle：新增 `logicsugar.*` key 时同步写入 `assets/bundles/bundle.properties`、`bundle_zh_CN.properties`、`bundle_zh_TW.properties` 三份。
- 命名空间纪律：`__ls_` 前缀是编译器保留区，用户函数/参数名不得使用；表达式临时变量固定 `_0, _1, …` 栈式编号（逆向重建依赖"一次写一次读"的线性链，勿破坏）。
- 触碰受保护游戏成员时遵守跨类加载器规则：实例方法放 `SugarStatement` 子类上，或走反射；`SugarCanvas` 的 optional 反射模式用于可降级功能，`SugarLogicDialog` 的硬反射模式用于无降级余地的核心字段。
- 反编译器的新识别模式必须放在 `verify` 重编译比对门之后。

## 调试建议

- 编译器/反编译器问题先跑对应自测任务再上游戏：`SugarCompilerSelfTest` / `SugarDecompilerTest` 覆盖大量 round-trip 场景，且不需要启动游戏。
- 运行时抛 `IllegalAccessError` 且栈顶是本模组静态方法 → 九成是跨类加载器访问陷阱，按 AGENTS.md 规则改为子类实例方法或反射；修完跑 `crossLoaderTest`。
- 反编译结果"少了结构"通常是安全门拒绝（`Result.notes` 会说明原因），这不是 bug：检查新模式是否绕过了 verify 或候选编译不等价。
- 设置项不显示：确认是独立态还是聚合态——聚合态由宿主调 `bekBuildSettings`，模组自建分类被 `if(!bekBundled)` 跳过。
- MindustryX 相关功能（变量过滤等）在原版客户端本来就不生效（依赖 X 侧的 `allVars`），不要当回归。
