# Logic Sugar v5.3.0

> [!IMPORTANT]
> 最低要求 **Mindustry v160.1**（桌面 / Android）。
>
> 保存出去的程序仍然是普通原版 mlog：没装模组的客户端能运行，联机（加入或自建服务器）不受影响。

> [!NOTE]
> v5.3 线是功能版：**数据结构运算从 68 张卡收敛成 10 张卡**（卡内按钮切换运算），新增**编辑器归属四档**与**跨逻辑复制粘贴**，产物统一带一条让载体永不执行的入口跳过，并修掉容量检查、变量查看器、函数库合并、卡内参数编辑与 Android 侧的一批缺陷。

## 中文

**数据运算卡改版：一个结构一张卡，卡内切换运算**

- **68 张运算卡 → 10 张**（每个数据结构一张），同结构的卡在调色板里挨着；运算用卡内按钮切换，实参改成**每个参数一个输入框**，编辑期参数无法编译时整卡标红。
- 参数元数据**不另建表**：`PaletteCall.arguments` 的默认串逐位就是参数名，UI 与编译路径共用同一套拆分规则；尾部空槽丢弃、中间空槽保留（`array_swap(buf, , j)` 与 `array_swap(buf, j)` 语义不同，宁可如实报参数错）。
- **载体格式一字不改**：旧存档照常打开，运算名在打开时归一化，重新保存只写规范名。
- 参数个数写错时编译期直接报 `takes N argument(s) but got M`，不再只回一句 `Unknown function`。

**新增：编辑器归属四档（`logicsugar.editorConflict`）**

- 四档 **`ask`（默认）/ `takeover` / `stepaside` / `coexist`**：接管、让位、共存，或不打扰。
- 默认档是 **`ask`**：启动时问一次「用谁」，**点掉弹窗 = 让位**，绝不静默选择会摘掉对方编辑器的接管档；设置值缺失、为空或无法识别时一律回落 `ask`。
- **接管档有不可逆副作用**（会摘掉第三方逻辑编辑器 UI 并只留一条提示），因此不再作为默认；**共存档**把 LogicSugar 画布跑在别的 mod 的逻辑编辑器里，需要跨 classloader 反射读写包级字段，装不上时按既有约定回落接管档。
- 已知残余风险（另半边无法离线闭环）写在 `docs/architecture.md`：第三方若在构造期缓存了被换下的旧画布，其保存回调可能写回空程序；共存换画布时若对话框正开着，`install()` 直接拒绝并回落。

**新增：跨逻辑复制粘贴**

- 编辑菜单新增「复制选区 / 粘贴选区」，Ctrl+C / Ctrl+V 驱动同一实现；**剪贴板放的是糖源码**（不是编译后的 mlog），片段落地后仍可继续编辑。
- 跨程序粘贴时 `jump` 的数字目标是另一个程序的指令下标，因此**复制与粘贴双侧拒绝**；块配对在插入前校验，之后每帧自愈。

**新增：入口跳过（产物 main 末尾统一多一条 `set @counter 0`）**

- 让几 KB 的持久化载体**永不执行**（否则 MDTX 逻辑面板的值列会把载体当成一条运行中的赋值显示出来）。等价性依据：`runOnce()` 在 `@counter` 越界时本就「置 0 执行指令 0」。
- **这条跳过行是所存储糖源码的一部分**，因此旧版本重编译同一段文本能原样复现，`verifyRestore` 双向实测通过；唯一代价是旧版编辑器会多显示一行 `set @counter 0`（显示层瑕疵）。
- **有效指令上限变成 `maxInstructions - 1`**：跳过行与载体一起计入编译末尾的上限判断，顶格程序（恰好 1000 条）会由「能存」变成存不下并抛错。三语设置文案已写明这一点。
- **源文本达到解析窗口上限时明确报错**：原版 `LParser` 只解析前 1000 条语句、其余静默丢弃，糖源码可以在指令数远未超的情况下突破这个窗口（500 个空 `if` 块 = 1000 条语句、只编译出约 500 条指令）。现在两种丢失（追加的跳过行被丢、整份糖落在窗口之后）都会被拒绝保存并说明原因，纯原版长程序照常通过。

**修复：数据结构声明的容量检查按真实链接解析（issue #14）**

- 容量上限原先只按变量名猜，而原版给链接取名取的是方块名最后一个 `-` 之后的部分：**512 格的 world-cell 也被链接成 `cellN`**，于是所有 `cell` 前缀的声明都被按 64 格卡住，合法范围被误拒。
- 现在优先把变量解析到**它实际链接的方块**并取该方块的 `memoryCapacity`；解析到的容量**双向生效**（既消除误拒，也收紧误放），链接到非内存方块则完全跳过检查，只有真正靠猜的时候文案才写「推断」。函数库会话与无头自测没有处理器上下文，仍走名字启发式。

**修复：变量查看器里的隐藏变量**

- 原先只过滤 MindustryX 的浮层（`allVars`），编译器生成的 `__ls_*` 与 `_0/_1` 临时变量在**原版 `@variables` 对话框**与 MindustryX 处理器配置面板（两者都枚举 `executor.vars`）里仍然可见。
- 现在 `vars` 也在三条护栏下过滤：**联机会话完全不动它**（`vars` 是 `sync` 的实时索引空间，压缩会让同步落到错误变量）、会话开始时撤销单机遗留的过滤、**写存档期间恢复完整数组**（`LogicBlock.write` 序列化每一个非空 `vars` 条目，隐藏状态因此仍能持久化）。关掉设置双向生效。

**修复：函数库合并后追加的函数被静默丢弃**

- 打开函数库对话框时，若本地库文件比处理器的嵌入子集多出函数，会把新切片追加到嵌入子集之后；而 `funcdef` / `begin` / `jump` 的 `destIndex` 是**绝对语句下标**，未平移的切片会让每个追加函数的跳转指回前缀、被判损坏并从有效库中静默消失。现在按前缀语句数平移。

**修复：卡内参数输入框逐键丢字**

- 「拼回」与「拆分」不是互逆映射：某一格里打了顶层逗号时（`s` 与 `a,b` 拼成 `s, a,b`，再拆回来是三段），多出来的段会被并进最后一格，下一键拼回时又成为新的段，**逐键累积**（在第 0 格连打 `a,b,c,d` 会得到 36 字符的实参串）。现在槽位**派生一次即缓存**，任何绕过本类的写入都会让缓存自动失效；`copy()` 带着槽位布局走，撤销与剪贴板都能保住用户划的格边界。
- 未闭合的 `(` 或 `"` 不再吞掉后面那一格的内容（严格拆分把 `f(, value` 读成一段，写回第 0 格会连 `value` 一起覆盖）。拆分器新增**只认成对括号与成对引号**的宽松版本，仅用于从载体串派生槽位；编译器仍走严格版，未配平由标红与试编译拒绝。

**界面与文案**

- **tooltip 改为提前把文字折行**（新增 `SugarTooltip` / `TextWrap`，按 `min(屏宽×0.5, 560 design)` 预折、窗口尺寸变化时重折）；arc 的 `Tooltip` 只夹容器位置，比屏幕宽的容器仍会两边溢出。
- 底栏两个固定宽按钮（变量转储 / 打印缓冲）移到编辑菜单——它们正是把底栏顶出窄窗口的原因。`VarClipboard.addButtons(Table, LogicDialog)` 这个 public 静态方法随之移除，能力迁到私有实现。
- 运算卡的**栏位**与**分组**分开：落哪一栏由 `DataModules.paletteColumn()` 查表决定，数组/矩阵运算卡与声明卡不再分家（跳转行滚动条配色同步修正）。
- 断言/日志卡改为每行独立子表格 + 显式左对齐，修掉「字段被推到中间」的三层叠加成因。
- 运算卡与调色板按钮的悬停文案、教程与参考文档统一改用卡面短名；同时删掉三族**无代码路径可取**的历史 bundle 键（272×3 个），并加双向可达性测试钉住。

**Android**

- **汉化失效**：`Mods.buildFiles()` 用 `Locale.toString()` 拼要读的文件名，而 Arc 只按 `language_country` 命名；Android 的 ICU 会填 script（`zh_CN_#Hans`），于是去找不存在的文件、该轮不加载，只有本模组新增的语句回落英文。改为用 `getLanguage()/getCountry()` 主动加载。
- **D8 未开 core-library desugaring**：`Map/List/Set.of`（API 30）、`List.copyOf`（API 31）、`String.isBlank`（API 33）以及 `java.util.function.*`（API 24）在支撑下限上都是硬崩，已全量换成 API 21 就有的等价物（`Collections.*`、`trim().isEmpty()`、`arc.func.Func/Prov`、显式比较器 lambda），语义不变。

**测试与文档**

- 新增 `EditorConflictTest`、`StatementClipboardSelfTest`、`TextWrapTest` 并接入 `check.dependsOn`；自测任务总数为 **40**。
- 源码钉子不再依赖检出时的行尾（新增 `SourceNails.readSource()` 归一 CRLF）；方法体钉子按花括号配对取整块（`SourceNails.blockFrom` / `methodBody`），签名找不到或括号不平衡直接报错，不再用定长窗口——定长窗口会在源码长大之后静默变绿，把「没检查」伪装成「检查通过」。
- `reconstructionMatrixTest` 覆盖 **175 个 fixture / 1078 项 gate 检查**，并新增产物级入口跳过钉子（全部 fixture 的产物 main 必须含这条跳过、`restore()` 必须把它丢掉）。
- `docs/`（architecture / development / glossary / testing / release / tutorials）与双语 README 已同步到当前特性，含入口跳过的窗口边界、四档默认值、共存的两个已知限制、运算卡三族 bundle 键的可达性不变量。

**兼容**

- 旧的载体、旧运算名、旧存档：全部照常打开与再保存。

## English

**Data-operation cards reworked: one card per structure, switch the operation inside the card**

- **68 operation cards collapse into 10** (one per data structure), grouped next to their declaration in the palette; the operation is chosen with in-card buttons, arguments became **one input field per parameter**, and the whole card is marked red at edit time when its arguments cannot compile.
- Parameter metadata needs **no separate table**: the default string of `PaletteCall.arguments` *is* the parameter list position by position, and the UI and the compile path share one splitting rule. Trailing empty slots are dropped while middle ones are kept (`array_swap(buf, , j)` differs from `array_swap(buf, j)`, so an arity error is reported honestly).
- **The carrier format is unchanged**: old saves open as before, operation names are canonicalized on load, and re-saving writes only the canonical names.
- A wrong argument count now reports `takes N argument(s) but got M` at compile time instead of a bare `Unknown function`.

**New: four editor-ownership modes (`logicsugar.editorConflict`)**

- **`ask` (default) / `takeover` / `stepaside` / `coexist`**: take the editor over, step aside, coexist inside the other mod's editor, or ask.
- The default is **`ask`**: it asks once per launch which editor to use, **dismissing the popup means stepping aside**, and a missing, empty or unknown setting value always falls back to `ask` rather than silently choosing the destructive branch.
- **Takeover has irreversible side effects** (it detaches the other mod's logic-editor UI and leaves only a notice), which is why it is no longer the default. **Coexist** runs the LogicSugar canvas inside another mod's logic editor and needs cross-classloader reflective access to package-private fields; when it cannot install it falls back to takeover by the existing convention.
- Known residual risks (the half that cannot be closed offline) are recorded in `docs/architecture.md`: a third party that captured the replaced canvas in its constructor may write an empty program back on save, and `install()` refuses while the dialog is open so a mid-session canvas swap cannot commit an empty canvas.

**New: copy and paste across logic blocks**

- The edit menu gains "copy selection / paste selection", driven by the same implementation as Ctrl+C / Ctrl+V; the clipboard holds **sugar source** (not compiled mlog), so a pasted fragment stays editable.
- A `jump`'s numeric target is another program's instruction index, so copying and pasting across programs **refuse jumps on both sides**; block pairing is validated before the insert and re-synced every frame afterwards.

**New: entry skip (one `set @counter 0` at the end of the compiled main body)**

- It keeps the multi-KB persistence carrier from ever **executing** (otherwise MindustryX's logic panel shows the carrier as a running assignment). It is equivalent to the previous behaviour because `runOnce()` already wraps to instruction 0 when `@counter` runs past the end.
- **The skip line is part of the stored sugar source**, so an older version recompiling the same text reproduces it byte for byte and `verifyRestore` passes in both directions (measured with an era probe). The only visible cost is that an older editor displays one extra `set @counter 0` line.
- **The effective instruction ceiling becomes `maxInstructions - 1`**: the skip counts toward the compile-time limit check together with the carrier, so a program that exactly filled the limit can no longer be stored and throws instead. The three-language setting text says so.
- **Reaching the parser window is now a hard error**: vanilla `LParser` parses only the first 1000 statements and silently drops the rest, and a sugar source can pass that window while its instruction count is far below the limit (500 empty `if` blocks are 1000 statements and only ~500 instructions). Both losses — the appended skip being dropped, or the sugar itself landing past the window — are now refused with an explanation, while a plain vanilla long program still passes.

**Fixed: declaration capacity resolved from the real linked block (issue #14)**

- The capacity check only guessed from the variable name, and vanilla names a link after the last `-` of the block name: a **512-slot world-cell is linked as `cellN`**, so every `cell`-prefixed declaration was capped at 64 and valid ranges were rejected.
- The variable is now resolved to **the block it is actually linked to** and uses that block's `memoryCapacity`; a resolved capacity applies in **both directions** (it removes false rejections and tightens false acceptances), a linked non-memory block skips the check, and the wording says "inferred" only when the value really is a guess. The function-library session and the headless self-tests have no processor context and keep the name heuristic.

**Fixed: hidden variables in the variable viewers**

- Only MindustryX's floating panel (`allVars`) was filtered, so the compiler-generated `__ls_*` and `_0/_1` temporaries stayed visible in the **vanilla `@variables` dialog** and in MindustryX's processor config panel (both enumerate `executor.vars`).
- `vars` is now filtered too, under three guards: **networked sessions leave it untouched** (it is the live `sync` index space, and compacting it would send syncs to the wrong variable), a single-player filter left installed when a session starts is undone, and the **full array is restored while the game is saving** (because `LogicBlock.write` serializes every non-null `vars` entry, hidden state still persists). Toggling the setting off restores the full arrays immediately.

**Fixed: functions appended during a library merge were dropped silently**

- Opening the library dialog with a local library file that has extra functions appends that slice after the processor's embedded subset; a `funcdef` / `begin` / `jump` `destIndex` is an **absolute statement index**, so an unshifted slice pointed every appended function back into the prefix, was rejected as damaged, and vanished from the effective library. The slice is now shifted by the prefix's statement count.

**Fixed: per-keystroke loss in the in-card argument fields**

- Joining and splitting are not inverse: with a top-level comma inside one slot (`s` and `a,b` join to `s, a,b`, which splits back into three), the extra segment was merged into the last slot and became a new segment on the next keystroke, **accumulating per keystroke** (typing `a,b,c,d` into slot 0 produced a 36-character argument string). Slot boundaries are now **derived once and cached**, any write that bypasses the class invalidates the cache automatically, and `copy()` carries the slot layout so undo and the clipboard keep the boxes the user drew.
- An unclosed `(` or `"` no longer swallows the slot behind it (strict splitting read `f(, value` as one slot, so writing slot 0 overwrote `value` too). The splitter gained a **lenient variant that only honours paired brackets and paired quotes**, used solely to derive slots from the carrier string; the compiler still uses the strict one and rejects unbalanced input through marking and a trial compile.

**Interface and wording**

- **Tooltips wrap their text up front** (new `SugarTooltip` / `TextWrap`, wrapping at `min(screenWidth*0.5, 560 design)` and re-wrapping on resize), because arc's `Tooltip` only clamps the container and a container wider than the screen still overflows on both sides.
- The two fixed-width bottom-bar buttons (variable dump / print buffer) moved into the edit menu — they were what pushed the bottom bar out of narrow windows. The public static `VarClipboard.addButtons(Table, LogicDialog)` was removed with that move and the capability became private.
- An operation card's **palette column** is now separate from its **group**: `DataModules.paletteColumn()` decides the column, so array/matrix operation cards no longer part ways with their declaration cards (the jump-line scrollbar colours follow).
- Assertion and log cards lay out as one sub-table per row with explicit left alignment, fixing the three stacked causes that pushed their fields into the middle.
- Hover text, tutorials and reference docs now use the short on-card operation names; three families of unreachable historical bundle keys (272×3) were deleted and pinned by a bidirectional reachability test.

**Android**

- **Translation did not load**: `Mods.buildFiles()` built the file name from `Locale.toString()` while Arc names bundles only `language_country`; Android's ICU fills in the script (`zh_CN_#Hans`), so the lookup asked for a file no mod ships, that round loaded nothing, and only this mod's newer statements fell back to English. It now builds the name from `getLanguage()/getCountry()`.
- **D8 does not desugar core libraries**: `Map/List/Set.of` (API 30), `List.copyOf` (API 31), `String.isBlank` (API 33) and `java.util.function.*` (API 24) all crash hard at the supported floor. They were replaced with API-21 equivalents (`Collections.*`, `trim().isEmpty()`, `arc.func.Func/Prov`, explicit comparator lambdas) with unchanged semantics.

**Tests and documentation**

- New `EditorConflictTest`, `StatementClipboardSelfTest` and `TextWrapTest`, wired into `check.dependsOn`; the suite now has **40** self-test tasks.
- Source nails no longer depend on the checked-out line endings (`SourceNails.readSource()` normalizes CRLF), and method-body nails take a whole brace-matched block (`SourceNails.blockFrom` / `methodBody`) that fails loudly when the signature or braces move, replacing fixed-length windows that would silently go green as the source grew — turning "not checked" into "checked and passed".
- `reconstructionMatrixTest` covers **175 fixtures / 1078 gate checks** and gained a product-level entry-skip nail: every fixture's compiled main must still contain the skip and `restore()` must drop it.
- `docs/` (architecture / development / glossary / testing / release / tutorials) and both READMEs are in sync with the current feature set, including the entry-skip window boundary, the new default mode, the two known coexistence limits, and the operation cards' bundle-key reachability invariant.

**Compatibility**

- Old carriers, old operation names and old saves all keep opening and re-saving as before.
