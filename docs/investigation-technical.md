# feat/arrays-l10n-v160prep 调查报告（技术细节）

面向：后续实现与代码评审。对应无技术细节的概要见 [investigation-overview.md](investigation-overview.md)。

工作副本：`D:\User\Desktop\BEK`，分支 `feat/arrays-l10n-v160prep`（`bb2b0d5`，`v4.2.0-be.27771`）。相对 `origin/main`（`v4.0.0` / `minGameVersion: 155`）约 +15729 / −306 行。GitHub 账号 `AdvinxCNN` 对该仓库 `push: true`。工作区规则：`.cursor/rules/workspace-only.mdc`（`alwaysApply`），禁止把本仓库记忆写入全局 Cursor 规则。

---

## 0. 分支上实际有什么

本分支相对 main 的功能主体：

- 数据子系统框架：`DataModule` / `DataModules` + `ExprIntrinsics`
- 数组 / 矩阵 / `arrayinit` + `ArrayRegistry`（`buf[i]`、`m[i][j]`、`len(buf)`）
- 批量数组运算、record、stack/queue、bitset、map、list/heap、chain
- 卡片本地化 `logicsugar.localizeCards`（走 `SugarStatements.cardText` / `name()` 覆盖，**不**调用上游 `statementKey()`）
- 断言子系统对齐 MlogAssertions v0.8.2
- 发布口径改为 BE 27771

编译产物不变式（`AGENTS.md` / `docs/architecture.md`）：

- 保存到处理器的是原版可解析 mlog
- 联机 `Vars.net.active()` 时 `AssertEmit` 强制 `strip`
- **禁止**再引入指令上限覆盖（`ea97e00` 已撤）；`LExecutor.maxInstructions`（1000）是硬上限
- 注入函数名 `__ls_builtin_*`，经 `SugarFunctions.withBuiltins` 并入**当次编译**的 `LibraryIndex`；`extractLibrarySource` 只处理用户库文本，内置函数不进 `__ls_lib`、不进 `functions.txt`

---

## 1. 更多 C++ STL 式数据结构

### 1.1 现有模块与表达式 API

注册点：`LogicSugarMod.registerStatements()` → `DataModules.register(...)`。声明卡 lower 为 `NoopI`，不产指令。

| 模块 | 声明卡 | 用户表达式 | 注入函数（`__ls_builtin_*`） | 降级 |
| --- | --- | --- | --- | --- |
| `ArrayRegistry` + `array`/`matrix`/`arrayinit` | 有 | `buf[i]`、`m[i][j]`、`len(buf)` | 无（直接 `read`/`write`） | 地址 = base+i 或 base+i*cols+j |
| `ArrayBulkModule` | 复用 array/matrix | `sum/avg/min/max/count/indexof/fill/copy/sortasc/sortdesc` | `arrsum/arravg/arrmin/arrmax/arrcount/arrindexof/arrfill/arrcopy/arrsort` | 循环型 funccall |
| `RecordModule` | `record` 最多 8 字段 | `p.f1` 读写 | 无 | 普通变量 `name_field` |
| `ContainerModule` | `stack` / `queue` | `spush/spop/speek/ssize/sclear`、`qpush/qpop/qpeek/qsize/qclear` | `stkpush`、`quepush`（其余内联） | 内存 + `__ls_stk_*_top` / `__ls_que_*_{head,tail,count}` |
| `BitsetModule` | `bitset` | `bset/bclr/btest/bcount` | `bwrite`、`bitcount` | word=i//64，mlog double 只有 53 位尾数，高位不可靠 |
| `MapModule` | `map` | `mapset/mapget/maphas/mapdel/mapsize/mapclear` | 六个全是注入函数 | 开放寻址，键区+值区，`abs(key)%cap`，墓碑删除 |
| `ListHeapModule` | `list` / `heap` | `lappend/lget/lset/linsert/lremove/lfind/lsize`、`hpush/hpop/hsize` | `lst*`、`hep*` | 小顶堆 |
| `ChainModule` | `chain` | `cinit/cclear/cnew/cfree/cget/cset/cnext/clink/cshead/chead/clen` | `chn*` | 节点值槽+next 槽，空闲链 LIFO |

容量：`cellN` → 64，`bankN`/`worldN` → 512（大小写不敏感）。`base+size`（map 为 `base+2*capacity`，chain 为 `base+2*size`）超容量是编译错误。

空容器：pop/peek 空 → NaN（`op div 0 0` 或越界 `read`）。这绑定 **v155.4/v159 语义**（越界 read 返回 NaN）。上游 #12459（v160 前瞻）把越界 read 改为 **null**，并允许内存存对象。见第 3 节。

状态变量 `__ls_*` 不随存档持久化：代码重载后计数归零、内存块内容仍在。链表首次必须 `cinit`/`cclear`；哈希表首次必须 `mapclear`（未初始化槽读回 0 会被当成 key=0 占用）。

跨模块名字/区间冲突**不**校验（`AGENTS.md` 写明）。新 STL 若继续分模块，会继承这个问题。

### 1.2 与 C++ STL 的可实现性（按代价）

约束：1000 条指令、cell 64 / bank 512、没有指针只有下标、没有可靠字符串键、`equal` 有 1e-6 容差、normal 模式共享一份 `funcdef` 体（与调用次数线性、与结构个数无关）、inline 会复制函数体。

**建议做（增量小、可复用框架）：**

| 结构 / 算法 | 做法 | 指令形态 |
| --- | --- | --- |
| `deque` | 在 `ContainerModule` 上给 queue 补 `qpushfront` / `qpopback`（已有环形 head/count） | 少量内联 + 可能复用 `quepush` |
| `unordered_set` | `MapModule` 去掉值区，或 capacity 只占 `[base, base+cap)` | 复用 map 探测，内存减半 |
| `reverse` / `replace` / 下标 `swap` | `ArrayBulkModule` 新 intrinsic | 一条共享循环 builtin |
| `binary_search` / `lower_bound` | 要求已排序数组 | 循环 builtin，比线性 `indexof` 在大数组上省 **ipt**，指令条数仍是一份函数体 |
| union-find | 新模块：parent 数组 + 带压缩的 find builtin | 中等；非 STL 但逻辑向 |

**不建议做：**

| 结构 | 原因 |
| --- | --- |
| `std::map` / `set`（树） | 旋转+染色一次插入就能把函数体打到几十～上百条，再叠加 1000 上限 |
| `std::string` / `rope` | 没有字符类型；`print` 缓冲是另一套（`maxTextBuffer = 400`），不能当随机访问容器 |
| 迭代器 / ranges / allocator / `std::function` | 没有对象身份；「迭代器」只能是 `(base,i)` 对，和已有下标重复 |
| `multimap` / `unordered_multimap` | 探测表要链桶或开放寻址存重复键，容量与指令双贵 |
| `std::variant` / `optional` 作为一等对象 | record + 手写 tag 已能表达；BE/v160 对象内存会改变判空，现在不要绑 |
| 图（邻接表+BFS） | 可用 chain 手写；做成模块会把 ipt 与指令预算一起吃满 |

`min`/`max` 已按实参个数分派（1 参 = 数组，2 参 = 原版 `LogicOp`）。新算法不要再占这两个名字。

扩展路径已经标准化：三个新文件（`data/<Module>.java`、`expr/<Module>Intrinsics.java`、`test/.../<Module>Test.java`）+ `DataModules.register` 一行。不要在生产代码里写死模块列表之外的引用。

### 1.3 指令预算直觉

normal 模式下，每个**用到的** builtin 向产物加入一份函数体 + 每次调用若干 `set` 实参绑定 + `jump` 进/出。未引用的 builtin 经可达性分析不进产物（测试钉死：`unused builtin leaked into the product`）。

贵的是运行时 ipt，不是「有几个数组」：`mapset` 整表环形扫描、`arrsort` 嵌套循环、`hpush` 上滤，都会在 512 槽上跑很多 tick。新 STL 优先选「函数体短、最坏 ipt 可控」的。

---

## 2. 封装为 mlog 函数并显示在全局函数库

### 2.1 现状：两条平行管道

**用户函数库**（`FunctionLibrary` / `FunctionLibraryDialog`）：

- 路径：`<game data>/mods/config/LogicSugar/functions.txt`
- 只允许顶层 `funcdef … blockend`
- 库函数体写入的名字重整为 `__ls_func_<func>_<name>`（`@` 常量与 `cellN`/`bankN`/`memoryN` 豁免）
- 用到的子集嵌入 `set __ls_lib "…"` 载体，跨机器可重编译
- UI 目前**没有函数目录**：一个提示 +「在处理器中编辑」

**数据子系统注入函数**：

- 源文本是完整 `funcdef __ls_builtin_* … blockend`（见各 `*Intrinsics.builtinSugar()`）
- `SugarCompiler.compile` 里 `withBuiltins(library, DataModules.builtinSugar())`
- 用户表达式 `sum(buf)` 由 intrinsic **先**解析声明卡，把 `buf` 换成 `mem, base, size` 再 `funccall __ls_builtin_arrsum`
- `SugarFunctions.paramsOf` 已能解析 builtin 参数名（编辑器提示），但名字仍是保留前缀
- 测试明确禁止 builtin 进入 `__ls_lib` 与用户 `LibraryIndex`

因此：用户在表达式里写的是 `spush(s, v)`，产物里是对 `__ls_builtin_stkpush` 的调用；**全局函数库文件里看不到它们**。

### 2.2 「显示在全局函数库里」的三种实现

**A. 写入 `functions.txt`（不推荐）**

- 库解析 `validateName(..., allowReserved=false)` 会拒绝 `__ls_*`
- 若改名成 `arrsum(mem,base,size)`：库 mangling 会改写函数体里的局部名；memory 设备名豁免，**状态变量 `__ls_stk_s_top` 不会当参数传入**，声明卡绑定丢失
- 用户可编辑、可损坏；损坏抢救逻辑会把「标准库」救成残缺子集
- 用到的函数进入 `__ls_lib` 载体，等于把标准库复制进每个处理器，和现在「compile-time 注入、不进载体」相反，浪费指令与 16KB 压缩额度

**B. 函数库对话框只读目录（推荐）**

- `FunctionLibraryDialog` 增加一页/一侧栏，数据源：`DataModules.builtinSugar()` + 各 `Provider.callNames()` + 声明卡 token
- 展示用户名（`sum(buf)`、`spush(s,v)`），内部名可折叠显示
- 只读，不写 `functions.txt`，不走 mangling
- 点击可插入表达式卡片或复制写法
- 不破坏「builtin 不进 `__ls_lib`」

**C. 双入口：声明卡 sugar + 通用 funccall**

- 通用形式今天已经能编译：显式 `funccall __ls_builtin_arrsum "cell1, 0, 8" x`（`builtinFunctionNames()` 让编辑器不当未定义函数）
- 可加不带 `__ls_` 的别名（仅编译期，仍不写库文件），给不用声明卡的人
- 声明卡路径保持主 UX

**结论：** 「封装为 mlog 函数」已经做了（注入 `funcdef`）。「显示在全局函数库里」应做成 **只读目录**，不要合并进 `functions.txt`。STL 新结构沿用同一套：intrinsic 用户名 + `__ls_builtin_*` 体 + 目录展示。

### 2.3 若强行做成库函数，还缺什么

需要改的硬点：

- `SugarFunctions.buildLibrary(..., allowReserved)` 对用户库仍必须 false
- `extractLibrarySource` 若开始抽出 builtin，载体体积与 1000 条上限会立刻变差
- 库函数无法读取 `ArrayRegistry` / 各模块 `collect` 的程序级注册表（那是**当前处理器**编译期状态）
- `FunctionLibrary.save` → `SugarFunctions.buildLibrary(LAssembler.read(text,true))` 不允许声明卡出现在库文件里（已有报错：`is not allowed in the function library`）

---

## 3. 处理器上限提示（已实现）

### 3.1 改之前的路径

原版 `LogicDialog`：`hide` → `hidden` → `canvas.save()` → `consumer`。

`SugarLogicDialog.submit` 在 consumer 里 `SugarCompiler.compile`。超限时：

```text
Compiled program has N instructions; maximum is 1000.
```

（inline 模式附加 `Switch to normal mode to share function bodies.`）

同时 `enforceStorageLimit` 用反射读取 `LogicBlock.maxCompressedLen`（失败则 16000），先试完整产物，再 `stripMarkers` 重压。仍超则抛错。

`hide()` **已经**会拦截不可编译表达式（`hasUncompilableExpression`），但**不会**拦截指令条数。超限发生在 `super.hide()` 之后的 consumer 里：编辑器开始关闭，再弹 `logicsugar.error.draft`。草稿按 Building 键进 `drafts`，下次打开能恢复，但体验就是「退出才报错」。

`LAssembler.read` 会在 1000 行处静默截断。纯 vanilla 超长程序走 `containsSugar == false` 时 `compile` **原样返回**源字符串，不抛这个异常——上限只在真正装进 executor 时截断。`BoxSelect` 用的是**卡片数** vs 1000，不是 lowering 后的条数。

注释标记块在计数**之后**才 `appendMarker`，所以 `# @logic-sugar-v1` 行不计入 1000。载体 `set __ls_sugar` / `__ls_lib` **计入**。分片载体若自己把程序顶出上限，会退化丢弃分片（旧行为），lowering 本身超限仍抛错。

### 3.2 改动

| 文件 | 作用 |
| --- | --- |
| `src/logicsugar/assist/InstructionBudget.java` | 对 sugar 做与保存相同口径的编译，统计 `SugarCompiler.emittedInstructionCount`（先 `stripMarkers` 再按非空非标签行计数） |
| `SugarCompiler.emittedInstructionCount` | 公开上述计数 |
| `SugarLogicDialog` | 约 24 帧 debounce 刷新底部标签；首次超限 `showInfoFade`；`hide()` 在表达式预检之后、`super.hide()` 之前再检一次，超限则**不关闭** |
| 三份 bundle | `logicsugar.budget*`、`logicsugar.error.budget*`、`logicsugar.error.storage` |
| `SugarCompilerSelfTest.instructionBudgetDetectsOverLimit` | inline 超限 / normal 不超 / vanilla 超长 passthrough 仍标红 |

绑定了 `LogicBuild` 时，成功编译还会算 `LogicBlock.compress(compiled, relativeConnections()).length`，与 16KB 限制一起标红。

关闭拦截用独立文案，不用 `logicsugar.error.draft`（因为编辑器根本没关，没有「已保留草稿」这回事）。

### 3.3 未做 / 注意

- 没有改 `LExecutor.maxInstructions`，也没有恢复已删除的上限覆盖设置。
- debounce 全量编译在超大画布上会卡一帧；这是正确计数的代价（卡片数不是 lowering 条数）。
- 函数库会话（`executor == null` / `passThroughSugarOnError`）不拦截关闭，与表达式预检一致；标签仍会刷新。
- 本机工作区没有 `../Mindustry-master/desktop/build/libs/Mindustry.jar`，此次未跑 `gradlew check`。有 jar 后应至少跑 `selfTest`。

---

## 4. 最新正式版 vs 只能在 BE 上用

### 4.1 版本锚点

| 线 | 标识 | 含义 |
| --- | --- | --- |
| 本分支产物 | `mod.json` `version: 4.2.0-be.27771`、`minGameVersion: "27771"` | 游戏用 `Version.build` 比较；正式版 build=159，**不会加载**这个 jar |
| 本分支编译 | `build.gradle` `mindustryVersion = "v27771"`，依赖本地 `Mindustry-master/.../Mindustry.jar` | 开发者按 BE 桌面包编译 |
| CI（本分支仍带着的 `pr.yml`） | 检出 Anuken/Mindustry `6c53474`（文档称 v155.4）+ 配套 Arc | **功能代码被要求仍能在 v155.4 上编译** |
| `origin/main` | `4.0.0` / `minGameVersion: 155` | 稳定线 |
| 最新 GitHub Release | **v8 Build 159.7**（2026-07-19） | 当前「最新 Mindustry release」 |
| BE 编号 | `BNUM=$((GITHUB_RUN_NUMBER + 20000))`（Mindustry `push.yml`） | 27771 ⇒ run 7771，时间上晚于 159.7，内容是 master / v160 前瞻 |

`docs/architecture.md` 的「上游版本适配笔记」仍写「钉在 v155.4」，与本分支 `mod.json` 27771 不一致——文档滞后，不是第二套实现。

### 4.2 本分支功能对游戏 API 的真实依赖

编辑器挂钩（v155 起就有，v159.7 仍在）：

- `LogicIO.allStatements`、`LAssembler.customParsers`
- 子类化 `LogicDialog` / `LCanvas` / `LStatement`
- 反射：`LogicDialog.privileged`、`consumer`；`LogicBlock.maxCompressedLen`（缺失则回退 16000）
- `LStatement.typeName()` / `hidden()` / `name()`：已在 **v159.7** 的 `LStatement.java` 中存在（`typeName` 默认 `getSimpleName().replace("Statement","")`）

本分支**没有**调用架构笔记里的 v160 API：

- `LStatement.bundle()` / `localizedName()` / `statementKey()`（#12158 / #12569）
- `LAccess.isPrivileged()` 删除（本仓库本就不调用）
- `MemoryBlock` 对象数组（#12459）
- `LogicRule.unitLight`、`LMarkerControl.light`

卡片本地化是 `name()` + `logicsugar.*` bundle + `logicsugar.localizeCards`。在 159.7 上可以工作；将来官方 `logiclocalization` 默认开时，需要再测两层开关叠加（架构笔记已列）。

数据子系统 lowering 只用 `read`/`write`/`op`/`jump`/`set`/`funccall` 形态的普通 mlog，**不需要新 opcode**。架构笔记也写本阶段 `LogicIO` 无新 opcode。

`MlogLint.KNOWN_KINDS` 含 `playmusic` 等较新 id；lint 是 advisory，多列 id 不会让 159.7 加载失败。

### 4.3 「优雅」的分界

**在 v159.7 上优雅：**

- 把 `minGameVersion` 改为 `159`（或继续 `155` 若仍要覆盖更旧 v8）
- 用 v159.7 `desktop:dist` 的 `Mindustry.jar` 跑 `./gradlew check`
- 发布说明不要写「仅 BE」
- 保存产物在原版客户端运行：已经满足

**在 159.7 上会不优雅 / 失败的，是当前这颗 BE 口径 jar：**

- `minGameVersion: 27771` 直接拒绝加载
- 若有人用更新的 BE（已含 #12459）跑本分支：空容器「越界 read → NaN」会变成「→ null」。`assertTypeTest` 已为对象内存预留分型，但 pop/peek 的 NaN 契约、`mapdel` 墓碑（NaN 存回内存后变成 null 对象、靠 `strictEqual`）必须按游戏版本分叉。**这个问题在 159.7 上反而不存在。**

**不必为了正式版重做的：**

- 数据子系统、函数 lowering、载体、反编译安全门、断言 strip 门禁
- 这些都不依赖 BE 独有指令

### 4.4 推荐的版本矩阵

| 产物 | minGameVersion | 编译对照 | 用途 |
| --- | --- | --- | --- |
| 稳定 | `159` | v159.7 jar | 正式版玩家 |
| BE 预发布 | `27771` | 对应 BE 桌面包 | 体验 v160 前瞻客户端 |
| CI | 与要合并的目标线一致 | 现在的 v155.4 pin 对「合回 main」仍有意义；合进 BE 线应改 pin | 防 API 漂移 |

两线应共用 `src/`，用 `minGameVersion` + 发布说明区分，而不是 fork 数据子系统。真正要分叉代码的是 **v160 内存对象语义**，时机是正式 bump `minGameVersion` 到含 #12459 的版本，而不是 159.7。

---

## 5. 建议的实现顺序

1. **已完成：** 编辑器指令/压缩预算的活体提示与关闭拦截。
2. **正式版包：** 改 `minGameVersion`、用 v159.7 跑 `check`，验证 `SugarLogicDialog` 反射字段仍在。
3. **函数库只读目录：** `FunctionLibraryDialog` 列出 `callNames()` + 声明卡，不改 `functions.txt`。
4. **STL 增量：** `deque` 两端操作 → `unordered_set` → `reverse/replace/swap/binary_search`；每个模块三文件 + 自测。
5. **v160：** 等正式版带上对象内存再改 NaN/null 与 `MlogLint` 分叉；现在不要把数据结构建立在对象槽上。

---

## 6. 关键代码索引

- 数据框架：`src/logicsugar/assist/data/DataModules.java`、`DataModule.java`
- 注入合并：`SugarFunctions.withBuiltins`、`SugarCompiler.compile` 中 `withBuiltins` + `extractLibrarySource`
- 函数库 UI：`FunctionLibrary.java`、`FunctionLibraryDialog.java`
- 上限：`SugarCompiler` 约 356–382 行；`SugarLogicDialog.hide/submit/enforceStorageLimit`；新 `InstructionBudget`
- 上游适配笔记：`docs/architecture.md`「上游版本适配笔记（v155.4 → v160 前瞻）」
- 兼容底线：`AGENTS.md` 首节
