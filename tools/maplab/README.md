# LogicSugar 功能展厅地图生成器

一键生成一张 100x100 的 Mindustry 地图（`LogicSugar 功能展厅`）：5x5 共 **25 个处理器展台**，
每个展台演示一个 LogicSugar 功能，旁边一块**信息板**写说明、一块**输出板**显示程序运行结果；
顶部一块 `large-logic-display` 显示展厅标题。

```text
行 0  控制流       1. if/elif/else  2. while+break/continue  3. for  4. switch  5. 短路条件 exprsc
行 1  表达式与函数 6. Expr 卡        7. 随处表达式            8. funcdef 9. 函数库 10. 单位控制卡
行 2  数据结构 I   11. array          12. 数组批量运算          13. matrix  14. span   15. record
行 3  数据结构 II  16. stack/queue/deque 17. bitset            18. map/uset 19. list/heap 20. chain
行 4  调试与编辑   21. 处理器状态指示 22. 断言（调试构建）      23. @counter 指示线 24. 反编译重建 25. 编辑器辅助
```

## 怎么跑

```powershell
cd tools\maplab
powershell -ExecutionPolicy Bypass -File build.ps1              # 编译 + 生成 out\LogicSugar-Lab.msav
powershell -ExecutionPolicy Bypass -File build.ps1 -Check       # 只做编译自检，不出地图
powershell -ExecutionPolicy Bypass -File build.ps1 -Dump for    # 打印某个展台的三层展开
powershell -ExecutionPolicy Bypass -File build.ps1 -Install     # 生成后复制进游戏 maps 目录
```

拿**另一个**游戏 jar 复验已生成的地图（只读，不需要 LogicSugar 类）：

```powershell
javac -encoding UTF-8 --release 17 -cp "<game.jar>" -d out\classes (Get-ChildItem src -Recurse -Filter *.java)
java -cp "out\classes;<game.jar>" lab.Lab --verify-only out\LogicSugar-Lab.msav
```

输出里会先打印这次用的是哪个 jar，再列出读到的处理器 / 信息板 / 内存块 / 单位数量。

参数（都有默认值）：

| 参数 | 默认 | 说明 |
| --- | --- | --- |
| `-MindustryJar` | 本机安装的客户端 `jre/desktop.jar` | 目标游戏 jar，决定写出的存档格式 |
| `-LogicSugarClasses` | `..\..\build\classes\java\main` | 模组编译产物（提供 `SugarCompiler` 等） |
| `-Out` | `out\LogicSugar-Lab.msav` | 产物路径 |

也可以直接跑 class（跳过 PowerShell）：

```bash
javac -encoding UTF-8 --release 17 -cp "<game.jar>;<LogicSugar>/build/classes/java/main" -d out/classes $(find src -name '*.java')
java -cp "out/classes;<game.jar>;<LogicSugar>/build/classes/java/main" lab.Lab --demos demos --out out/LogicSugar-Lab.msav
```

## 产物

- `out/LogicSugar-Lab.msav`：地图本体，直接放进 `%APPDATA%\Mindustry\maps\`（或 `-Install`）。
- `out/preview.png`：示意预览图（按方块类型上色的方格图，用来核对摆位；游戏自己的缩略图另由游戏生成）。

生成过程会自己读回校验（用游戏自己的 `SaveIO` 重新加载）：尺寸、每块处理器的代码是否逐字节一致、
链接是否指向真实方块、信息板文本是否完整、载体是否仍能通过 `verifyRestore`。

## 三层管线：一段糖码是怎么进地图的

编辑器保存时的真实链路是 `SugarCanvas.save()` ⇒ 画布文本 ⇒ `SugarCompiler.compile()`，
本工具复刻同一条链路（`demos/*.ls` 里写的是**可以直接粘进处理器的那份糖码**）：

```text
demos/*.ls（好读的糖码）
  │  ExprTextImport.plan            把 `x = a + b` / `buf[i] = 5` 换成哨兵 set
  │  LAssembler.read                （哨兵文本 ⇒ 语句表）
  │  ExprTextImport.applyToStatements  哨兵 ⇒ ExprStatement 卡
  │  LAssembler.write               在数组表 / 声明类型 / 函数上下文里序列化（= 画布文本）
  ▼
画布文本（载体里的那份源码：表达式卡已展开成 op/read 行，单行卡后面跟 # @ls-expr-card 标记）
  │  SugarCompiler.compile          产物 = 原版 mlog + 标记块 + set __ls_sugar / __ls_lib 载体
  ▼
处理器代码（任何原版客户端都能解析、能运行；装了 LogicSugar 打开编辑器看到的是积木）
```

序列化那一步必须自己装好编译期上下文（`ArrayRegistry` / `ExprIntrinsics.enterDeclaredKinds` /
`DataModules.collectAll`），否则 `buf[i]`、`m[1][2]`、`s.top()` 这类糖在写文本时会解析失败或退化成裸
`read/write`——这正是 `SugarCanvas.save()` 之外最容易漏的一环。

每个展台都会打印一行自检：

```text
  array           14 条指令  open=stored   verify=true  strategy=same
```

- `open=stored`：打开编辑器时走载体恢复（`SugarDecompiler.openingSource` 的判定），看到的是积木；
  `inferred` 表示源码里没有载体、由反编译推断（24. 反编译重建展台）；`raw` 表示程序本身就是纯原版。
- `strategy=same`：`SwitchStrategy`（auto / chainOnly）两种设置编出同一条指令流。否则用户改设置后
  `verifyRestore` 会失配、载体被丢弃、结构化视图退回原版，所以工具把它当错误处理。

## 目标游戏 jar 为什么重要（踩过的坑）

存档里的方块用的是**内容 id**、处理器实体用的是**实体修订号**，两者都由写出地图的那个 jar 决定：

- 内容 id 会在存档头里连名字一起写出（`content` 区域），所以不同版本的 id 偏移能被"按名字回填"接住；
- 处理器实体的修订号没有这层保护：**新 reader 认老格式，老 reader 不认新格式**。
  本工具踩过一次——用更新 master 构建的 `Mindustry.jar` 写出的地图，在较老的客户端上会在
  `SaveVersion.readMap` 里 `skipChunk` 报错（旧 reader 少读 2 字节 ⇒ 字节流错位）。

因此默认目标是**本机安装的客户端**（`-MindustryJar`），写出的地图在客户端、较新的 master 构建
（`Mindustry-master/desktop/build/libs/Mindustry.jar`）以及本地 MDTX（`MindustryX-main`）三种
reader 上都能加载——反过来则不成立。换 jar 后重新跑一次 `build.ps1` 即可。

实际验证过的组合（`--verify-only`，同一张地图）：

| reader | 结果 |
| --- | --- |
| 本机 Steam 客户端（写出地图用的那个 jar） | ✓ 100x100 / 27 处理器 / 51 信息板 / 2 单位 |
| 本地 `MindustryX-main`（MDTX）| ✓ 同上 |
| 较新的 `Mindustry-master` 构建 | ✓ 同上（新 reader 能读老格式）|

## 目录结构

```text
tools/maplab/
├── build.ps1              编译 + 运行（PowerShell）
├── demos/*.ls             25 个展台的糖码源码（+ library.txt 函数库、title.ls 标题牌、wait.ls 附加处理器）
├── src/lab/Lab.java       地图装配：地面 / 摆件 / 链接 / 写地图 / 读回校验 / 预览图
├── src/lab/LabStations.java  展台表：位置、说明板文案、道具、附加处理器、单位
└── src/lab/LabCompiler.java  糖码 ⇒ 画布文本 ⇒ 产物，以及编辑器打开路径的判定
```

## 加一个新展台

1. 在 `demos/` 里加一份 `<id>.ls`（就是能粘进处理器的糖码文本，`#` 注释随你写）。
2. 在 `LabStations.all()` 里加一条 `station("<id>", "标题", row, col, "logic-processor", null)...`：
   需要内存块用 `.prop("memory-cell", 2, 6, "cell1")`，需要第二个处理器用 `.extra(...)`，
   调试构建用 `.assertEmit()`，纯原版源码（反编译展台）用 `.vanillaCode()`，调用函数库用最后一个参数传库文件名。
3. `powershell -File build.ps1 -Check` 看编译与自检结果，再 `-Dump <id>` 核对三层展开。

## 注意

- 说明板文案上限 400 字（`MessageBlock.maxTextLength`），工具会检查并在超限时报错。
- 演示源码按 **LF** 读取（`Lab.read` 会归一 `
`），因此 Windows 上 `core.autocrlf` 检出的
  仓库也能直接跑；同时 `tools/maplab/.gitattributes` 把 `*.ls` / `library.txt` 钉成 `eol=lf`。
- **认不出的语句直接让生成失败**：文本导入层认不出的行会被原版解析器默默落成 `InvalidStatement`：
  产物里多一条 `noop`、编辑器里多一张红色的「无效」卡，载体里也照样存着（用户报过的 @counter
  指示线不画线就是这个原因）。`LabCompiler.rejectUnparsedLines` 会对**用户文本 / 画布文本 / 编译产物**
  逐行解析，命中就拿行号和原文报错。典型例子是 `@unit = 5` 这类写不进去的内建变量目标
  （`@counter` 是唯一被文本导入接受的内建变量）。
- 同一位演示者不要既让 `unitfor` 遍历某类单位、又给那类单位打自定义 flag：LogicSugar 的单位卡用
  **flag 认领协议**（只认 `flag 0` 或本处理器 uid），被打上自定义 flag 的单位下一轮会被当
  「别人的单位」跳过，数量直接变 0。10 号展台因此把 flag 演示换成了另一类单位（`@flare`）。
- 展台代码总指令数都远小于 1000；只有 22. 断言展台是 **emit 调试构建**（产物含非原版指令），
  它的信息板上写明了后果与还原方法。
- 地图是自带 `rules.editor` 的展示图（无限资源、无敌人波次、不会失败），打开后直接能改、能跑。
