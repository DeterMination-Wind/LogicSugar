# 上游基线（Mlog Dev Tools / 原 MlogAssertions）

**结论：LogicSugar 不再移植 Mlog Dev Tools（MDT，原名 MlogAssertions）。** 断言/断点指令卡与运行时、
Vars / Memory / Properties 界面、快照、Profiler、处理器状态指示、处理器日志窗口、三击入口和方块配置面板
都已删除；存档里已有的调试行只由 `mindustry.logic.SugarDebugLines` 做**文本直通**，运行时行为归 MDT。
本文记录这个决定、它成立的技术原因、仍然必须保持的线格式，以及明确不带走的部分。实现细节见
[架构总览](architecture.md)「MDT 调试行直通」；测试入口见[测试指南](testing.md)。

## 为什么移除

- **功能重叠**：MDT 自己提供同一批 opcode 的卡片、运行时和界面。两边同时安装时，同一个 opcode 只能有一个
  解析器生效（`LAssembler.customParsers` 先到先得），移植版实际上只是 MDT 的第二份、且必须逐版本追平
  它的线格式与运行时语义，维护成本全部落在本仓库，收益为零。
- **保存产物不需要它**：LogicSugar 的存在前提是产物在任意原版客户端可解析。调试 opcode 本来就不是原版
  指令，LogicSugar 做到「存档里的这些行不丢、不串行、不让后续跳转目标偏移」就够了，不需要自己执行它们。

## 为什么只能做文本直通（不能做语义层）

原版 `LParser` 把所有自定义解析器交给**同一个复用的 16 槽 token 数组**，并且**不告诉解析器本行实际写了
多少个 token**（`tokens.length` 恒为 16）。上游 v0.12.0 的破坏性改名（旧 `log` 写游戏日志文件、新 `log`
把消息存进处理器，旧语义改叫 `systemlog`）与 `snapshot` 新增 `steps` 槽，都要求解析器知道「这一槽在新版
是分类、在旧版是消息」才能正确解释；只靠 `tokens.length` 永远分不清，而按写法分流只能还原**行形状**、不能
还原语义。LogicSugar 既然不再拥有这些卡片，就不该继续维护那张语义表——那正是移植版要逐版本追平、也最容易
漂移的部分。

直通层只做两件事：

1. 记录该 opcode 的**定长线格式**（`snapshot`/`log` 两个家族按判别槽的写法选 4/5 或 12/13），把一行原样
   还原成同样的 token 序列；
2. 产出一条**惰性指令**（`NoopI`）占住一个指令槽。

详细规则见 `SugarDebugLines.tokenCount`（`src/mindustry/logic/SugarDebugLines.java:209`）与它的 arity
表；`SugarDebugLines.Line.build(LAssembler)` 永远返回 `NoopI`，绝不返回 `null`——`LAssembler` 会丢掉 null
指令，之后的每个跳转目标都会错位。

## 直通层的边界

- **没有卡片**：`installParsers()` 只写 `LAssembler.customParsers`，不往 `LogicIO.allStatements` 加任何
  条目，因此选板里没有调试积木，也没有分类。
- **没有运行时**：解析出的行在画布上是只读文本，装配成 `NoopI`；断言失败、断点、快照、Profiler 全部由
  MDT 自己的运行时处理。
- **注册顺序**：本模组在 `ClientLoadEvent` 之后的 `Core.app.post` 步骤里调用
  `SugarDebugLines.installParsers()` 并且**跳过已被占用的 opcode**，所以 MDT 先注册就永远是 MDT 的解析器
  （`src/logicsugar/LogicSugarMod.java:233`，位于 `installEditor` 内）。反编译的安全门在用户动作时也会确保解析器存在
  （`SugarDecompiler.installSugarParsers`），同样遵循先到先得。
- **保存产物**：`AssertEmit=emit`（调试构建）把糖源码里的调试行原样写进产物；`strip`（默认、也是联机强制
  口径）编掉它们。判据是**写出的文本**（`SugarDebugLines.isDebugStatement` / `writeDebugStatement`），
  不是本模组的 `Line` 类型：装了 MDT 时这些 opcode 归 MDT 的解析器，程序里拿到的是 MDT 自己的语句类，
  strip 仍按 opcode 文本把它编掉，emit 则逐字节调用它自己的 `write()`。糖源码本身始终在载体里，所以
  两种口径下重开都能拿回同一行。

## 明确不随移植带走的部分

| 能力 | 现在归谁 | 本仓库的处理 |
| --- | --- | --- |
| 断言/断点/`error`/`log`/`systemlog`/`snapshot`/`profile`/`restart` 的运行时语义 | MDT | 只保留文本直通；`SugarDebugLines` 不解释 token |
| 断言指令卡、`assert` 卡、失败消息占位符引擎 | MDT | 卡片与消息格式化代码已删除 |
| Vars / Memory / Properties 对话框、三击入口、方块配置面板按钮 | MDT | 全部删除；原版「变量」按钮原样保留 |
| 快照（isolated / connected / recording / global）与快照队列 | MDT | 删除；`snapshot` 行只作文本保存 |
| Profiler、指令包装器、覆盖率/配额统计 | MDT | 删除；`profile`/`restart` 行只作文本保存 |
| 处理器状态指示、处理器自带日志窗口、wait 指示圈 | MDT | 删除；`log`/`systemlog` 行只作文本保存 |
| `max-instructions`（指令预算覆盖） | 不提供（本仓库硬不变式：产物恒 ≤1000 条） | 见[架构总览](architecture.md)「保存预算钉」 |

本仓库**保留**的调试相关能力只有两项，都不依赖 MDT：

1. 调试行文本直通（上面全部内容）；
2. `AssertEmit=emit` 构建下表达式层自己发射的数组/矩阵越界检查（`assertBounds`，KEEP(P4)），它的线格式
   签名与 strip/emit 规则见[重建规则](reconstruction.md)「生成式越界检查」。

## 首次启动的 MDT 推荐（`MdtRecommendation`）

解耦后单独安装 LogicSugar 就没有调试界面了，所以独立形态首次启动时会弹一次推荐：建议玩家再装 MDT。
它**不碰保存产物**（只下载/安装另一个 mod，不改变任何编译结果），因此没有联机门禁；但它会改磁盘上的
`mods/` 并重启游戏，所以实现上刻意保守：

- **只弹一次**：`ClientLoadEvent` 后延后约 1 秒，标志 `logicsugar.mdt.recommendShown` 在对话框
  `shown()` 时写入——按 Esc 关掉也算看过，不会下次启动再弹。检测到 MDT 已安装（旧名
  `mlog-assertions*`、新名 `mlogdevtools`、显示名、或 `mod-<name>-repo` 指向
  `cardillan/MlogDevTools` 任一命中）时直接写标志、不弹下载窗；装在但禁用也算已安装，因为再下一份副本
  并不能解决问题。
- **最低版本 0.12.3**（`minMdtVersion`）：0.12.3 之前的 MDT 加载项在窗口尺寸变化时会击穿 LS 编辑器
  （MDT PR #11 修的就是这个）。MDT 0.12.3 自身要求 **Mindustry 160.5+**，低于该版本的玩家只能安装更旧的
  兼容 MDT。所以「已安装但版本过低」走另一条**一次性**提示
  （`logicsugar.mdt.updateNoticeShown`，文案 `logicsugar.mdt.update`）：只给 GitHub / 模组浏览器两个手动
  更新入口，**不提供下载**（那会并排再装一份）。版本比较是 `MdtRecommendation.versionAtLeast` 的纯
  数字点段比较：`-` / `+` 后缀忽略，缺段按 0，null / 乱码一律判 false（未知版本不算够新）。
- **下载也过版本门**：镜像可能落后于最新 release，所以 `readMeta` 之后、`importMod` 之前用同一比较器
  拒绝低于 0.12.3 的 jar，删掉文件并弹 `logicsugar.mdt.tooold`。
- **不打断别的弹窗**：同一事件里编辑器冲突询问也会弹窗，所以本弹窗延后并在
  `Core.scene.hasDialog()` 时重试（最多 20 次、每次约 2 秒）；重试耗尽则本次启动放弃且**不写标志**。
- **不在聚合形态出现**：`bekBundled`（Neon）自带模组选择，不该再指路去装一个聚合包外的 mod；headless
  服务器也没有玩家可问。这是刻意的双形态差异，Neon 侧不需要对应入口。
- **四个按钮**：`不了` / `跳转到 Github Release` / `跳转到模组浏览器` / `下载`。前两个手动通道永远可用，
  也是「最新 MDT 要求更新的游戏」时的唯一出路。
- **`下载` 按 IP 分流**：并行探测 Cloudflare trace、`api.ip.sb`、`ipapi.co`（任一命中中国即按中国处理，
  全部失败则回落到系统 `Locale` 的 CN 判定）。中国网络走镜像池立即下载并重启；非中国网络直接打开
  游戏内模组浏览器并把搜索框填成 `Mlog Dev Tools`（GitHub 本身可达，浏览器也是正常更新通道）。
- **镜像池**：取 Xenon `MirrorDownloader.MIRRORS` 的子集并按 2026-11 实测排序（`gh-proxy.com`、
  `github.chenc.dev` 同时支持 API 与 jar，其余只支持 jar），每条约写成 `<mirror>/<完整 github URL>`，
  **直连 GitHub 永远是最后一条**。先经镜像读 release 元数据拿到带版本号的资产地址（资产在 v0.12.0
  由 `MlogAssertions.jar` 改名成 `MlogDevTools.jar`，写死一个名字迟早失效），元数据拿不到才回落到
  `releases/latest/download/MlogDevTools.jar`。每个响应都校验 zip 头与 `Content-Length`（实测有镜像
  200 后只吐 49KB），坏响应换下一条；元数据 25 秒、jar 90 秒各自独立预算，取消按钮随时生效。
- **版本门**：导入前用 `Vars.mods.findMeta(new ZipFi(file))` 读 MDT 的 `minGameVersion`，低于
  `Version.isAtLeast` 就拒绝并提示两个版本号——直接导入只会得到一个游戏不认的 mod。
- **安装与重启**：走游戏自己的 `Vars.mods.importMod(file, true)` 并补 `setRepo(repo)`（否则模组浏览器
  认为没装、也不会给更新提示），随后 `showInfoOnHidden` + `Core.app.exit()`，与游戏导入 mod 后的
  `mods.reloadexit` 流程一致。

实现见 `src/logicsugar/MdtRecommendation.java`，无网自测见[测试指南](testing.md) 的
`mdtRecommendTest`。

## 历史线格式例外（改前先读）

这些形状是**已有存档里真实存在**的文本，直通层必须逐字节保留它们；它不判断语义，因此没有「读成哪张卡」
的问题，但**任何归一化都会改写用户存档**，所以下面每一条都必须在 `debugPassthroughTest` 与
`ReconstructionMatrixTest` 的调试行 fixture 里钉住：

1. **`asserttype` 的 token 顺序**：上游 v0.10 起是 `<type> <value> <message>`；旧存档里仍可能是
   `<value> <type> <message>`。直通层两者都按同一 arity（4）原样保留，不猜测哪个是类型名。
2. **`asserttype` 的 `null` 类型**：LogicSugar 曾扩展「空值断言」（线上写 `null`，上游
   `AssertionDataType` 不识别）。直通层不解释类型表，这个 token 与其它 token 一样原样保留。
3. **`snapshot` 的两种形状**：当前是 `snapshot <type> <block> <steps> <message>`（5 token，v0.11.2 起，
   含 `recording`）；旧文本是 `snapshot <type> <block> <message>`（4 token，LogicSugar ≤5.7.2）。判别靠
   **第 4 槽的写法**：`~` / 引号开头 / `null` / 空只能是旧式消息，因此旧存档不会多长出一个 `steps` 槽。
4. **`log` 的两种形状**：当前是 `log <level> <category> <msg> <p1..p9>`（13 token，v0.12）；旧文本是
   `log <level> <msg> <p1..p9>`（12 token，没有分类槽）。判别同样靠**第 3 槽的写法**（`~` / 引号开头 /
   `null` / 空 = 旧式消息）。这是上面「16 槽复用数组」问题的直接后果：按 token 数量永远分不清，只能按写法。
5. **`~` 占位**：空槽在线上写 `~`（LogicSugar 的既有约定）。直通层保留 `~` 原样，不把空槽归一成别的
   占位符。

## 参考源码

- 历史基线快照仍在工作区 `../_upstream/`：`MlogDevTools-0.12.1`（最后的移植基线）、`-0.12.0`、
  `MlogAssertions-0.11.8` / `-0.11.7` / `-0.11.6` / `-0.11.3` 与 `MlogAssertions-pr`（v0.8.2）。
- 它们现在只用于**核对线格式**与回答「旧存档里这一行为什么是这个样子」，不再是本仓库的行为基线，也不再
  要求逐提交跟进。不要 `git fetch upstream`：本仓库不保留上游 remote。
- MDT 的运行时与界面以 MDT 自己的发布为准；本仓库不改写、不替代它。
