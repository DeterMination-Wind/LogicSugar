# mlog2sugar —— Mlog → Sugar Code（逐字节等同游戏内输出）

`mlog2sugar.py` 是**单文件入口**：直接运行、粘贴原版 mlog、结束输入，糖码就显示在程序输出里。

```powershell
# 绝对路径，任意工作目录可用（这里以本机仓库路径为例）
python "C:\Users\华硕\Documents\codex\LogicSugar\tools\mlog2sugar\mlog2sugar.py"
# 粘贴 mlog，最后单独一行输入 EOF 回车（或 Ctrl+Z 回车 / Ctrl+D）
```

输出的文本与游戏内 LogicSugar 编辑器对同一份程序产生的文本**逐字节相同**——因为转换不是
重写的，而是脚本驱动一个极小的 Java 内核，直接调用 mod 现有代码里游戏编辑器打开/保存走的
那整条链路（内核源码内嵌在 `mlog2sugar.py` 里，运行时写进系统临时目录编译一次，用户只需
关心这一个文件）：

```text
SugarDecompiler.openingSource      载体恢复 / 反编译推断 / 原样（与 SugarLogicDialog.show 同一个判定）
SugarDecompiler.openableSource     打开前预检与「读不了就退回存档」的同一条兜底
ExprTextImport.attachCardMarkers   把标记块里的 # @ls-expr-card 提到它展开成的那条语句下面
ExprTextImport.plan                认不出的赋值行一对一换成哨兵 set（跳转下标不动）
LAssembler.read + truncate         等价于 LCanvas.load 的解析与指令上限截断
ExprTextImport.applyToStatements   哨兵 → 表达式卡
ExprHook.foldAll（判定全部走生产代码） 等价于 SugarCanvas.load 末尾的折叠
ExprHook.unfoldedText              等价于 SugarCanvas.save 的展开态文本（纯读取）
```

无头环境的唯一补丁是画布本来会提供的三份上下文（宽松数组注册表、声明的结构种类、用户函数表 +
`DataModules.collectAll`，正是 `SugarCanvas.save()` 里 `ArrayRegistry.canvasRegistry(canvas)`
与各模块 `active()` 的画布回退在该起的作用）。折叠判定（`ExprHook.isChainLine` /
`collectChain` / `foldableChain` / `foldPlan`）全部用生产代码那一份，工具只补冒号：
画布专有的 jump 区间检查与语句表元素增删。

## 怎么跑

```powershell
# 本文件（本机路径；也可换成相对路径，前提是当前目录在仓库内）
$m2s = "C:\Users\华硕\Documents\codex\LogicSugar\tools\mlog2sugar\mlog2sugar.py"

# 首选：不带参数，粘贴模式（结束方式：单独一行 EOF 或 .，或 Ctrl+Z 回车 / Ctrl+D）
python "$m2s"

# 文件 → 标准输出
python "$m2s" program.mlog
# 文件 → 文件
python "$m2s" program.mlog -o program.ls

# 标准输入 → 标准输出（输出是纯字节，不做 CRLF 转换；可安全重定向）
python "$m2s" < program.mlog > program.ls

# 看打开时的糖码原文（不跑画布保存那一步的规范化）
python "$m2s" --stage source program.mlog

# 自检：对 tools/maplab/demos/*.ls 全部演示跑「打开+保存 == 重开+保存」逐字节比对
python "$m2s" --selftest
```

路径不需要手敲：`python <本文件> -h` 的帮助信息里显示的也是本文件的绝对路径；脚本从
`__file__` 自己推导仓库根目录、游戏 jar、mod 类与缓存位置，所以从任意工作目录调用都一样。

交互模式下：提示与统计信息走 stderr，糖码本身走 stdout —— 所以把 stdout 重定向到文件就只得到
糖码，终端里复制粘贴也不会带上提示行。stdout 是终端时按控制台编码显示（中文正常），重定向/管道
时输出确定性的 UTF-8。输入接受 UTF-8（自动去 BOM）/本机编码，CRLF 会按游戏口径归一化成 LF。

依赖：**JDK 17+**（`javac`/`java` 在 PATH 上或设置 `JAVA_HOME`）、**一个 Mindustry v160.1
桌面 jar**（默认取 `../Mindustry-master/desktop/build/libs/Mindustry.jar`，可用
`--mindustry-jar` / `LOGICSUGAR_MINDUSTRY_JAR` 覆盖）以及本仓库**编译好的 mod 类**
（`build/classes/java/main`；缺失或过期时脚本自动跑一次 `gradlew classes`，`--no-build` 可禁止）。
内嵌内核只在第一次运行（或本文件更新后）用 `javac --release 17` 编译到系统临时目录
（`%TEMP%\mlog2sugar\<内容哈希>`，可用 `MLOG2SUGAR_CACHE` 覆盖），仓库工作区不会被写入。

## 选项

| 选项 | 默认 | 说明 |
| --- | --- | --- |
| `input` | 省略 | 输入 mlog 文件；省略时：终端下进入粘贴模式，管道/重定向下读标准输入 |
| `-o/--output` | `-` | 输出文件；`-` 表示标准输出 |
| `--privileged` | 关 | 按**世界处理器/编辑器特权**解析。默认与游戏内普通处理器编辑器一致（`SugarLogicDialog.show` 里 `executor != null` 的那条路径，`privileged == false`） |
| `--stage canvas\|source` | `canvas` | `canvas` = 编辑器保存/导出（复制）出来的那份文本（`SugarCanvas.save()`，默认）；`source` = 打开时载入画布的那段糖码（`editable`），不做折叠后重新展开 |
| `--assert-emit` | 关 | 按**调试断言构建**展开表达式卡（对应设置 `logicsugar.assertEmit = emit`，仅单机）。开启后变量下标的数组/矩阵访问会照实写出自动越界断言卡，与游戏内 emit 模式一致 |
| `--mindustry-jar` | 自动探测 | 游戏 jar 路径 |
| `--classes` | `build/classes/java/main` | 覆盖 mod 类目录 |
| `--no-build` | 关 | mod 类缺失/过期时不自动 `gradlew classes` |
| `--verbose` | 关 | 把打开判定模式（`stored` / `inferred` / `raw`）、是否走了兜底、输出长度打到 stderr |
| `--selftest` | — | 跑自检（见下） |

## 与游戏输出的对应关系

- **`stored`**（程序带 LogicSugar 载体，最常见）：打开文本就是载体里的糖码（`restore` 还会
  按嵌套重配对 begin/blockend 的下标注释——demos 里手写的 `999` 占位因此被修正）；保存/导出
  即默认的 canvas 输出。
- **`inferred`**（纯原版 mlog，无载体）：输出的是反编译推断并**通过重编译安全门**的那份糖码
  （与游戏里「由原版指令推断」提示下的视图一致）；认不出的部分保持原版。
- **`raw`**（纯原版且没有可推断结构）：原样返回，注释/空行会被丢弃（注释本来就不是语句）。

`--stage source` 与默认的 `canvas` 输出在多数程序上逐字节相同；两者不同的情况是 mod 自身
既定的折叠规范化：`SugarCanvas.load` 末尾的 `ExprHook.foldAll` 会把 op 链折回表达式卡，
`save()` 再展开时可能以不同但等价的临时量重新生成展开行（例如 bitset 下标链会顺带做常量折叠）。
`--selftest` 断言的是**默认输出是编辑器往返（打开+保存）的不动点**——这正是用户反复打开、复制、
保存时看到的那份稳定文本。

## 自检

```powershell
python tools\mlog2sugar\mlog2sugar.py --selftest
```

对 `tools/maplab/demos/` 的全部演示（27 个，覆盖全部积木/卡片、函数库、表达式卡、断言卡、
单位控制、数组/矩阵/span/容器/位集/映射/堆/链、跳转线、反编译重建）断言：

1. 由演示编出的产物能通过自己的糖码验证（`verifyRestore`）；
2. 默认输出是保存周期的不动点：把它编成产物、再打开、再保存，得到同一段文本（逐字节）。

本次交付时外部交叉验证（`tools/maplab/build.ps1 -Dump <station>`，另一条入口：
`ExprTextImport → LAssembler.write → SugarCompiler.compile → restore`）：

- 27/27 站台的 `--stage source` 输出与 `restore(product)` **逐字节相同**；
- 27/27 站台的默认输出是保存周期不动点；
- `assert` 站台（emit 调试构建产物）在 `--assert-emit` 下同样逐字节相同。

## 兼容底线（本项目硬要求）

工具只输出**糖码文本**，不写处理器、不生成 mlog 产物、不改任何存档；产物里「纯原版可解析、
≤1000 条指令」的底线不受影响。`--assert-emit` 只是照实再现单机调试构建在游戏里会产生的
文本（对应设置项，联机时游戏自身会回落 strip），不会让工具产出调试指令。
