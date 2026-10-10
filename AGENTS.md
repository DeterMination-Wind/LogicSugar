# LogicSugar — Agent Notes

Mindustry Java mod that adds structured `for` / `while` / `switch` / function blocks to the
logic editor while storing vanilla-compatible mlog. Workspace-wide rules (build modes, dev
identity `LogicSugar-dev / 0.0.0`, release safety) live in the parent `codex/AGENTS.md`;
this file only adds what is specific to this project.

## 兼容底线（项目所有者明文要求，改任何功能前先读）

**LogicSugar 的硬底线：多人联机环境下必须兼容原版客户端。** 保存到处理器的代码在任何
原版客户端上都要能解析、能运行；这是整个 mod 的存在前提，优先级高于一切新功能。

- **调试类功能只在单机启用**：凡是会改变保存产物语义的功能（目前是调试断言构建
  `AssertEmit=emit`），必须在代码层限定为 `!Vars.net.active()`（单机/编辑器）才生效——
  联机（已连接或自建）一律回落原版行为。纯展示类功能（如处理器状态指示、单位 flag 显示、变量复制按钮）
  不产生存档差异，不受此限。不提供改变处理器指令预算的能力：指令上限覆盖曾试做后被移除
  （commit ea97e00），处理器保存产物恒 ≤1000 条是硬不变式，勿再引入。
- **函数库不是处理器产物**：全局函数库 `functions.txt` 不算"保存到处理器的代码"，不受上面
  1000 条硬不变式约束；它有独立上限 `SugarFunctions.libraryInstructionLimit`（当前 10000 条
  语句）。库文本必须用 `SugarFunctions.readLibrary` 解析（临时抬高 `LExecutor.maxInstructions`
  后 `finally` 还原，绝不放开处理器检查），超限由 `libraryOverLimit` 在保存/打开编辑器时明确
  拒绝。使用库函数的处理器产物仍然 ≤1000 条，只嵌入被调用到的函数子集。
- **残余风险必须写进文档**：单机里创建的越界内容（>1000 条程序、带 assert 指令的调试
  构建）若之后被分享到多人环境，原版客户端仍会截断/清空/静默降级——代码无法阻止分享，
  只能靠设置描述与文档把后果讲清（见 bundle 的 maxInstructions/assertEmit 描述）。
- 新功能提案先按此底线分类：不碰保存产物 → 正常实现；碰保存产物 → 必须加联机门禁，
  并在 bundle 与 `docs/architecture.md` 说明单机限定。
- **上游同步基线**：断言/断点子系统与调试工具（Vars / Memory / Properties 界面、快照、Profiler）对齐
  cardillan/MlogAssertions **v0.11.3**（参考源码 `../_upstream/MlogAssertions-0.11.3`，由
  `gh api repos/cardillan/MlogAssertions/tarball/refs/tags/v0.11.3` 取得；本地那份
  `../_upstream/MlogAssertions-pr` 停在 v0.8.2，只当历史参考，不要 `git fetch upstream`）。
  上游的指令上限覆盖（`max-instructions`）**不得**移植。UI/快照部分落在 `logicsugar.vars`
  （数据）与 `logicsugar.vars.ui`（对话框/入口）、Profiler 落在 `logicsugar.profile`，
  不改变保存产物，因此不受联机底线约束；
  但**方块配置面板必须与 MindustryX 共存**：探测到 fork 自带 `LogicSupport` 时先调用方块自己的
  `buildConfiguration` 再追加本 mod 的按钮（原则要求，不要改成替换）。线格式例外有三处：
  `asserttype` 的 `null` 类型是 LogicSugar 扩展（上游 `AssertionDataType` 不识别）、上游
  v0.10 起 `asserttype` 的 token 顺序为 `<type> <value>`（旧序仍可读，保存统一写新序）、
  以及 `snapshot` 的 5 槽文本（上游 v0.11.2 起 `type block steps message`；旧 3 载荷 token
  文本按第 4 槽写法分流仍可读）——改这一块前
  先看 `SugarAsserts.AssertTypeCard` 的注释与 `assertTypeTest`。

## Build & Test
```powershell
cd LogicSugar; ./gradlew check        # runs selfTest, ifElseTest, decompileTest, reconstructionTest, reconstructionMatrixTest, recoveryPredicateTest,
                                      # shortCircuitTest, crossLoaderTest, boxSelectTest, cfgTest, lintTest,
                                      # varClipboardTest, processorStatusTest, unitFlagsTest, assertTest, assertTypeTest, assertMessageTest, varsTest, varsUiTest, varsAccessTest, arrayTest,
                                      # arrayBulkTest, dataFrameworkTest, recordTest, containerTest, bitsetTest,
                                      # mapTest, setTest, listHeapTest, chainTest, dataSubsystemTest, dataCallTest, editHistoryTest,
                                      # bottomBarLayoutTest, escapePreviewTest, v160SensorAccessTest, funclibLimitTest, dataRuntimeTest,
                                      # exprTextImportTest, exprCardTest, conditionLabelTest, editorConflictTest, textWrapTest,
                                      # paletteHintTest, statementClipboardTest, canvasSourceTest, unitControlTest, spanTest, profilerTest
./gradlew check jar                   # build + dev jar at build/libs/ (copy to 构建/LogicSugar/LogicSugar-dev.jar)
```

`tools/maplab/` 是**独立于 Gradle 的演示地图生成器**（自己的 `build.ps1` + `demos/*.ls`，见 `tools/maplab/README.md`）：生成 100x100 的功能展厅地图（25 个处理器展台）。它不参与 `build` / `check`，改它不需要跑 Gradle；但它依赖 `build/classes/java/main` 里的编译器类，并且**目标游戏 jar 决定写出的存档格式**（新 reader 认老格式、老 reader 不认新格式），换 jar 后要重新生成。

The self-tests are `main()`-based JavaExec tasks (no JUnit runner). New regression coverage
should follow that convention and be added to `check.dependsOn`.

## Mod class loader vs. game classes (critical, easy to miss)

At runtime this mod's classes load through a **mod class loader** while `mindustry.logic.*`
game classes load through the app loader. Same package name, **different runtime packages**:

- `protected` / package-private members of game classes (`LStatement.field`,
  `LStatement.showSelect`, `LogicDialog.privileged`/`consumer`, …) may only be accessed
  1. from **inside our own subclasses** — subclass access to protected members is legal
     across loaders (this is why `SugarStatement.fieldsHint` works), or
  2. **reflectively** via `Field.setAccessible(true)` (see `SugarLogicDialog.privilegedField`
     for the established pattern).
- A static helper in `SugarStatements` (or any non-subclass of ours) calling those members
  **compiles fine** — javac only sees the source-level package match — and throws
  `IllegalAccessError` at runtime the first time the UI renders. This bit the first
  `addCompactOp` implementation (crash 2026-08-27).
- Package-private *fields* trap identically and the subclass rule does not help: reading
  `LogicDialog.globalsDialog` straight from `SugarLogicDialog.openVars` crashed the client on
  the first click of 「内置变量」 (2026-10), while the same class's shadowing `executor` field
  only works because the source-level reference resolves to *our* declaration. Use the same
  reflection pattern (or an instance we own) for every package-private field.
- Rule: any helper that touches protected game-class members must be an instance method on
  the `SugarStatement` subclass (public if a static editor like
  `rebuildConditionEditor` needs to call it). Static code may only use public game API.
- `crossLoaderTest` guards both directions: the child-first loader simulation (behaviour) and
  a static scan of every compiled class's constant-pool member references resolved against the
  JVM's accessibility rules (shape) — a direct package-private access fails the build instead of
  a user's click.

## Decompiler safety gate

`SugarDecompiler` may only return recovered Sugar source after recompiling it and comparing
the normalized instruction stream against the input (`verify`). Anything unrecognized falls
back to raw vanilla statements. When touching recovery logic, keep every new pattern behind
that gate; failure direction must always be "show more vanilla code", never "rewrite unknown
programs". Two properties of the gate are load-bearing:

- `verify` compiles each candidate across the full FuncMode x SwitchStrategy matrix, because
  the program may have been saved under different user settings.
- `backtrack` (bounded retries that promote an alternative candidate at one decision point)
  runs only after the greedy candidate failed verification, and every promoted result must
  pass the same gate.

## Reconstruction (every new block/feature — mandatory)

**Rule: every time a new block/card/feature is added, or an existing one is changed, and the
change can alter the compiled vanilla mlog product, the corresponding from-vanilla-code
reconstruction logic must be completed in the same change.** "Compiles" is not done until the
saved program can be reopened and the source-level card/structure is recovered, or the
recovery gap is explicitly documented as "show vanilla". Do not land a lowering-only change.

Opening a saved processor must prefer restoring the structured Sugar the user edited.
There are two paths; both stay behind the verify gate (compare the *executable* mlog after
stripping carriers/markers, not the Base64 metadata):

1. **Carrier restore** (lossless): decode `__ls_sugar` / `__ls_lib`. `destIndex` on
   `ifbegin`/`forbegin`/… is a jump *comment*. If it is stale but `begin`/`blockend` nesting
   is well-formed, re-pair from innermost matching before `validatePairs`. Data-structure
   declaration cards live only in this source — they never appear in vanilla mlog — so a
   new module/card/intrinsic is not done until a program that uses it round-trips through
   `restore` + `verifyRestore` + `SugarDecompiler.decompile` (carrier path) and still
   shows the cards. `reconstructionTest` pins the gate; `reconstructionMatrixTest` pins a
   100+ fixture matrix covering every current block/card, every `datacall` operation and
   the assertion/debug cards. Declaration cards are also **identity metadata, not a claim on
   vanilla code**: `decl.array.exprcard` pins the marked case (the carrier view keeps the
   `# @ls-expr-card` marker that makes `read x cell1 3` a `x = buf[3]` card), and
   `decl.array.vanillaRead` pins the unmarked one (the same declaration + `read x cell1 3`
   must keep opening as a vanilla read, never as `buf[3]`).
2. **Decompiler inference**: pattern-match vanilla jumps back into `if`/`for`/`while`/
   `switch`/functions. Do **not** invent declaration cards or recover `__ls_builtin_*`
   trampolines as user `funcdef`s. Failure direction remains "more vanilla". A new
   executable control-flow shape must either be recoverable by inference (with fixture) or
   explicitly recorded as carrier-only in this section.

### The gate's two normalizations (so hand-written programs open as Sugar, not vanilla)

A program Logic Sugar never saved — hand-written mlog or another tool's output — carries
neither the carrier nor the two things this compiler's own output always has. Both were
missing from the gate, and together they made *every* such program fall back to flat
vanilla no matter how well it was understood (reported 2026-09-25 with a 655-instruction
jump-table program; fixture `test/fixtures/realworld-jump-table.mlog`):

- **Entry-skip era.** `compile` appends `set @counter 0` (`entrySkipLine`), so any candidate
  containing sugar gained one instruction the input never had. `verify` now compiles each
  candidate in *both* eras, exactly like `SugarCompiler.verifyLowering` does for stored
  saves (that is where the pattern comes from; `compileWithoutEntrySkip` is the public
  entry, used only by the gate). Saving a recovered view still adds the skip — documented,
  intended, and semantics-preserving (running off the end wraps to 0 either way).
- **Jump threading.** `compile` runs `threadAlwaysJumpTargets`, which rewrites
  `jump A always` to the end of A's chain. That pass reads chains off *labels*, and a
  hand-written program addresses jumps by instruction index with no labels at all, so the
  old `threadAlwaysJumpTargets(original)` comparison target was a no-op on exactly the
  programs that needed it. `SugarCompiler.threadNumericJumpTargets` applies the same fixed
  point on statement indices (unconditional jumps only, cycles keep their targets, line
  structure and instruction count unchanged). `verify` applies **both** passes to the
  comparison target (numeric first, then label threading): the numeric pass covers hand-written
  index-addressed programs, the label pass covers stored/label-addressed outputs, which the
  numeric pass leaves alone.

Neither normalization weakens the gate: both yield streams behaviorally identical to the
input, and both are transformations the compiler already applies to its own output.

### The editor must ask the decompiler at all (`SugarDecompiler.openingSource`)

The gate fix above made recovery *possible*; it did not make it *reachable*. `verifyRestore`
starts with `if(!hasSugarCarrier(code)) return true;` — for a program with no carrier there is
nothing to verify, so it answers "true", and `SugarLogicDialog.show` read that as "trusted
stored sugar" and loaded the vanilla text. The decompiler was only consulted when a carrier
existed but failed verification, i.e. never for hand-written/third-party programs. The
reported program therefore still opened as 251 raw jump cards after the gate fix.

The decision now lives in `SugarDecompiler.openingSource(code, privileged, librarySession)`,
returning the source to load plus a mode: `stored` (carrier, legacy marker block, or library
text — load as-is), `inferred` (verified inference: show the recovered notice and keep the
original view reachable), `raw` (load the code unchanged). Inference runs whenever there is no
carrier to trust, and only for a processor program — library text is sugar source, not a
program. It is a plain static method on purpose: the bug sat in UI code that no headless test
could reach, and `decompileTest`'s `editorOpensHandWrittenProgramsAsSugar` now pins the
decision for both editor privilege levels (an ordinary processor edits with `privileged ==
false`, while recovery tests tend to pass `true`).

### The editor must always open (`SugarDecompiler.openableSource`)

The open preflight used to just refuse: if the intended source failed to parse, `show()` hid the
dialog and reported the error. With a draft kept from a failed compile that is unrecoverable —
the draft stays, every later open hits the same wall, and the user can never repair the program
(reported 2026-10: a `funcdef` return declaration of `c`; the card accepted it, the parser
rejected it). `openableSource(preferred, code, privileged, librarySession)` now decides:
`preferred` (the draft, or whatever `openingSource` chose) when it parses; otherwise the stored
`code` — which the compiler produced and therefore always parses — with `fallback = true` so the
caller drops the unreadable draft and says so in a toast; `source == null` only when even the
stored program does not parse, and then the old refusal (with the parse error as the reason)
stays. Static for the same reason as `openingSource`; `decompileTest`'s
`unreadableSourceFallsBackToTheStoredProgram` pins all three branches.

That fallback is a safety net, not the primary fix: **every card's `write()` must be readable by
the same card's parser, for every value the editor can produce.** A cleared or mistyped field is
card state, not a parse error — parsers must not throw on it (they used to: `funcdef`'s return
declaration and function name, `span`/`array`/`matrix`/`arrayinit` names and memory cells), the
compile path reports the located error, and the editor marks the card red via
`SugarCompiler.invalidStatements`. Watch the LParser trap while doing this: `tokens` is a reused
static array, so a missing middle token silently shifts every following slot onto the previous
line's leftovers — an empty slot must be written as the `~` placeholder (`optional()` on write,
`optionalValue()` on parse). In `funcdef` the v5 shape picks the meaning of the third slot by
"is it an integer", so a non-canonical declaration is written back **quoted**
(`funcdef f a "c" 3`); without that, `returns = "3"` would come back as the legacy `destIndex`.
The signature rule lives in `SugarFunctions.funcDefDeclarationProblem`, shared by the compile
path (which throws it, located) and the editor's red marking, so the two can never disagree.

### Raw leap tables (`switchbegin … raw`) and the `default` case

A hand-written `@counter` jump table has no bounds guards — `op add @counter @counter <v>`
followed by one unconditional row per slot. Recovering it as the compiler's *guarded*
table would add two instructions and clamp out-of-range values, i.e. silently rewrite the
program, so the shape is carried in the source:

- `switchbegin <value> <dest> raw` (optional 4th token; absent = guarded) lowers to
  dispatch + `span` rows and nothing else. It ignores `SwitchStrategy` — the mode is a
  property of the program, which is also what lets the gate's strategy matrix accept it.
  Invalid raw tables (non-integer values, span > `MAX_TABLE_SPAN`) are a compile error.
- `default:` is the switch's own card for "no case matched". In the chain lowering it is
  the trailing jump's target; in a table it is the target of every hole row, and in the
  guarded form of the bounds guards too (an out-of-range value lands there). At most one per
  switch, inside a switch only; `defaultViolations` is the single rule shared by the
  compiler, the editor's red marking and the library builder.
- Inference (`tryBareSwitchTable`): slot *k* addresses row *k*, so a case value is its row
  index and the span starts at 0. A row's destination is read through unconditional-jump
  chains (the author's own threading). Rows that leave the body region are holes and must
  all share one destination, which becomes the `default` case — placed on an *existing*
  instruction inside the region whose own chain ends there, so the regenerated rows resolve
  to it. The table's span is pinned with a case on either end slot when a hole sits there
  (the label and the default share a position, so the row is identical). Anything that does
  not fit returns null and the view stays vanilla.
- Inference of the *guarded* table now also recovers a `default`: the guards and hole rows
  land on a body inside the switch instead of at its exit, and the switch's real end is then
  the destination the case bodies' breaks use (`switchEndBeyond`). Both readings are offered
  as candidates — a body jumping past the switch is indistinguishable from a default at that
  level — and the gate decides, the same way the rest of the recovery resolves ambiguity.

### Packed stride tables (`switchbegin … stride`)

Hand-written unit controllers often dispatch with a multiply instead of one jump row per
slot: `op mul <tmp> <idx> <stride>` then either `op add @counter <tmp> <K>` (absolute: case
`v` is instruction `v*stride+K`) or `op add <tmp> @counter <tmp>` plus `op add|sub @counter`
(relative: the first case is the instruction after the three-op dispatch). The case bodies
*are* the slots, each exactly `stride` instructions; a final short slot is allowed only when
it runs to the end of the region. Recovery writes `switchbegin <idx> <dest> stride <n> <tmp> abs|rel`.
The constant `K` is recomputed at compile time from the instruction index of the first body,
so the product stays byte-identical without storing a stale address. Anything that is not two
full slots with a shared `end` or shared unconditional-jump trailer stays vanilla. The
dynamic-`@counter` triage treats only a dispatch `recognizeStride` accepts as known-safe.

### Unit-control cards are inferred from the compiler's own lowering

`unitbind` / `unitnext` / `unitfor` / `unitfree` used to be carrier-only, and a program without a
carrier (the reported case: the product of a `unitfor` block pasted back as mlog) reopened as a
stack of `ifbegin` cards over the scan loop's guards — faithful per the gate, useless to the user.
They are now recovered by inference, but not by guessing from jump shapes: `tryUnitCard` matches
the lowering instruction for instruction by its private `__ls_ub_uid` /
`__ls_ub_<kind>_<index>` names (the same approach as the `__ls_sw_*` tables), so hand-written
`ubind` / `@flag` code never matches. A `unitfor` frame keeps the author's body — the region
between the card's own delivery line and its step/back edge — so `break` and `continue` inside it
stay items, and nested blocks, loops and hoisted function bodies all recover.

Two properties are load-bearing:

- **`unitIndexMatches` (refuse, do not recover).** The lowering numbers its temporaries with the
  statement index of the list being lowered (`__ls_ub_n_3`), and the compiler regenerates those
  names from the recovered card's position; a card that would land at another index (zero-instruction
  cards before it, a function body whose item base differs) cannot reproduce the instruction stream.
  Refusing the match keeps the previous reading, while recovering and failing the gate would cost
  the program every other structure it can recover — the failure direction stays "show more
  vanilla".
- **The layer is switchable** (`Candidate.unitCards`). When a unit candidate fails the gate,
  `infer` reruns with the layer off, keeps that result if it verifies (the reading that existed
  before the layer) and only then falls through to `backtrack`. The layer can improve a view; it
  can never remove a recovery that already worked.

Fixtures: the `unit.*` entries in `ReconstructionMatrixTest` are `addInferred` (carrier +
inference; `unit.for.body` is the reported program), and `decompileTest`'s
`unitControlCardsRecoverFromTheirLowering` pins both directions — the recovered block, and a
lowering with one scan guard edited that must stay vanilla.

**Checklist for any change that touches the compiled product:**

- Update the carrier path so the new card/feature survives `compile → save → restore → verifyRestore`.
- Update decompiler inference when the shape is expressible from vanilla jumps; otherwise
  document "carrier-only / show vanilla" here.
- Add fixtures to `test/mindustry/logic/ReconstructionMatrixTest.java`: at minimum one
  carrier fixture; add an inference fixture for every provable new shape. The test must stay
  above 100 fixtures and must fail when a new block has no reconstruction coverage.
- Run `.\gradlew.bat reconstructionTest reconstructionMatrixTest decompileTest` before
  claiming the feature complete.

### Multi-cell `span`: a third recovery layer (the editor fold, not inference)

A `span` card lives only in the carrier, and so does the saved text of an index expression over
it: `x = buf[i]` is written as `op idiv` / `op mod` / N×`select` / `read`/`write` (a constant
index folds to a one-line `read x cell1 3` on the member block). Decompiler inference
deliberately does not invent span cards, so the only thing that can turn that text back into the
card the user edited is `ExprHook.foldAll`: the carrier text *is* the program's sugar, so a card
that does not fold back is silently replaced by raw blocks on every reopen (reported 2026-09).
All three parts are load-bearing and pinned by `spanTest`:

- `ExprCompiler.resolveArrayFolds` has a **span view** that re-expresses memory/address as
  (span alias, logical address): member + literal local address → `k*C + local`, or the
  prologue's member list + per-cell capacity via `ArrayRegistry.findSpanByShape` with the `idiv`
  dividend (`q*C + r`) as the logical address. Prologue lines are marked `CONSUMED` so they
  vanish with the fold. Two spans explaining the same expansion ⇒ null ("fold less, never fold
  wrong"); `verifyArrayFold` still decides every surviving candidate by recompiling it.
- `ExprHook.foldsSpanPrologue` lets the prologue `select` lines into the fold chain, but
  `statementFor` must keep returning null for `SelectLine`: `unfoldAll` keeps a chain with
  unmappable lines as an Expr card (`hasUnmappableLine`), which is what keeps the canvas card and
  the saved text consistent.
- `ExprHook.hasExternalReads` must exclude `SpanAccess.scratchNames()`. Those three names are
  program-level scratch that every expansion rewrites right before its own access, so another
  span card mentioning them is not an external read — without the exclusion two adjacent span
  cards refuse each other's fold and neither ever comes back. The scratch writes themselves must
  not end a chain either (`ExprHook.appendChainLine` keeps `__ls_span_q/r` destinations open like
  temps), and the `read`/`write` whose memory is the building scratch has to enter the chain
  (`ExprHook.foldsMemoryLine` → `isSpanScratch`); missing either half meant only the *constant*
  index form ever folded — a text-level fold test cannot see it, because it never runs the chain
  collection (`spanTest`'s `variableReadFoldsOnReopen` now does).

The constant form is a single line, and a lone `read`/`write` line is **never** folded
(`ExprHook.foldableChain` requires `chain.length() >= 2`). A declaration says which cells belong
to the array, not that a line is a subscript access — the array card's default range
(`cell1 0 8`) and the vanilla Read/Write block's default target (`cell1` / address `0`) coincide,
so folding on the declaration table alone rewrote the user's hand-dragged vanilla blocks into
`x = buf[3]` / `buf[i] = 5` cards (reported 2026-10). The single-line card carries its own
`# @ls-expr-card` marker, and that marker is what restores it: `spanTest` pins the card's marker
plus the `ExprTextImport.plan` round trip, and (separately) that the rebuilt-but-lone `read x
cell2 0` stays a vanilla line on reopen. Runtime coverage is `dataRuntimeTest.spanRuntime`: the
expansion must select a **building object** (a numeric-only `select` would break every span
access in game while every shape-only test still passed).

### Data-structure getters: the fold's provider reverse hook (`Provider.foldAt`)

A container getter card (`x = q.front()`, `s.top()`, `d.back()`) is multi-line, so its saved text is
the expansion and carries no marker: the same recovery layer as the span case applies, and the
2026-10 report ("the getter cannot be reconstructed from the Expr card") was this layer missing.
An `op add _1 <base> <head>` … `read x cell2 _1` chain either refused to fold or was folded into a
stray address-computation card (`_1 = 8+__ls_que_q_head-(__ls_que_q_count<=0)*(9+__ls_que_q_head)`).
The pieces:

- **The gate is the same one.** `ExprCompiler.resolveArrayFolds` first tries the array/matrix/span
  views (unchanged), then hands the line to `ExprIntrinsics.tryFoldAt`, and every surviving
  candidate still has to pass `verifyArrayFold`'s recompile-and-compare (`foldPlan`). A provider
  therefore cannot fold anything the compiler would not re-emit identically.
- **`Provider.foldAt(ops, index)`** returns the source node plus the window length counting back
  from `index` (the last line is *replaced* by the node, the lines before it are marked consumed).
  `ContainerIntrinsics.foldAt` does not hand-write shape tables: it recompiles the canonical getter
  forms (`s.top()` / `s.size()` / `q.front()` / `d.front()` / `d.back()`) and compares them line by
  line, with the pattern's `_0, _1, …` slots acting as capture positions (same slot ⇒ same actual
  operand, the numbering itself may differ) and the window's last result slot wildcarded. Forward
  lowering changes therefore break the reverse match loudly instead of silently retiring it; the
  `(kind × getter)` table in `containerTest`'s `getterFold` fails if it ever does.
- **`Provider.declaresMemory`** is the chain-collection half: a `read` on a structure's declared
  memory must enter the fold chain (`ExprHook.foldsMemoryLine`), otherwise the chain breaks before
  it and `hasExternalReads` sees the address temporary as an outside read. `foldAt` staying
  silent for a claimed read is the safe direction: the chain is skipped, nothing is rewritten.
- **Aliases collapse to the canonical form.** `s.peek()`, `speek(s)`, `q.peek()`, `qpeek(q)`,
  `d.peekfront()` … all compile to the same stream, so they fold back as `s.top()` / `q.front()` /
  `d.back()`. The `size` family (`s.size()`, `ssize(s)`, `q.count()`) is one line: a lone card keeps
  relying on `cardMarkerPrefix`, and inside a larger chain (`x = q.front() + q.size()`) the same
  provider hook claims that line in place (`lines == 1`, nothing consumed).
- **`ExprHook.hasExternalReads` scans an Expr card's source, not its expansion.** The expansion's
  `_0/_1…` are the card's own scratch; scanning the text made the first folded card classify every
  later chain with the same temporary names as an "external read", so the second card never came
  back (the plain-op duplicate case was already fixed in 2026-10, the card case was not).

**Fold decisions must be reachable headlessly.** `ExprHook.isChainLine` / `collectChain` /
`foldPlan` are the whole decision, public on purpose; the canvas loop only adds element surgery and
the canvas-only jump-target check. `test/logicsugar/assist/expr/ExprFoldHarness.java` runs that same
pair over a statement list, which is what `containerTest` and `spanTest` assert against — a
text-level `rebuild` test cannot see a chain that never gets collected (that blind spot is why the
span variable-index and container getter gaps survived their own tests).

**Deliberately still "show vanilla": the mutating container operations.** `spop` / `qpop` /
`dpopf` / `dpopb` / `spush` / `qpush` / `dpushb` / `dpushf` have no `foldAt` entry. Their chains
write the hidden state variables (which `hasExternalReads` treats as outside reads as soon as the
container is used anywhere else) and the `read` that ends them is not a getter window, so such a
card still reopens as raw ops — exactly as it did before this layer existed, and that is the
accepted direction. Adding them means extending `getterMethods` plus a matching handler that
re-emits the state writes, not inventing a new recovery path.

### Multi-cell `span` (continued) — the variable-address chain is load-bearing

Beyond the prologue/select rules above, two chain-collection details decide whether a **variable**
index expression (`x = buf[i]`, `buf[i] = 5`, `m[i][j]`) survives a reopen at all: the prologue's
`op idiv`/`op mod` destinations are program-level scratch rather than `_n` temps, so they must not
end the chain (`ExprHook.appendChainLine`), and the trailing `read`/`write` sits on the building
scratch, not on a span member, so `isArrayMemory` cannot see it (`ExprHook.isSpanScratch`, gated on
the span registry being non-empty). Only the constant-index form (a one-line `read x cell1 3`) ever
folded without both. `spanTest`'s `variableReadFoldsOnReopen` pins read/write/matrix through the
real chain collection.

**Text import is another way into the same pipeline.** `SugarCanvas.load` runs
`ExprTextImport.plan` first: a line that vanilla `LParser` cannot dispatch (`x = buf[3]`,
`buf[i] = 5`, `result = (a + b) * 2`, `@counter = 0`) is swapped for a unique
`set __ls_import_N 0` sentinel
(one line for one line, so label/jump indices do not move) and the sentinel is replaced by an
`ExprStatement` card after the parse. Everything after that is the normal
`unfoldAll`/`foldAll` path, so products stay pure vanilla mlog and the carrier coverage above
applies unchanged. Keep the conservative skip list in sync when adding sugar line forms (see
`ExprTextImportSelfTest`): anything whose first token is already claimed by `LogicIO.read` or
`LAssembler.customParsers`, comparisons (`==`/`!=`/`<=`/`>=`), strings, comments and one-line
multi-statements must stay untouched. `@`-headed destinations are accepted only for the one
writable builtin, `@counter` (`@unit = 5` stays with the vanilla parser — writing it is a no-op);
the clipboard's one-line form (`canWriteInline`) deliberately still refuses `@` destinations,
because a payload may be read by an older LogicSugar whose import does not know them.

**An `ExprStatement` card must survive `save()` — treated as a block, not a formatting detail.**
Every palette insert triggers `SugarCanvas.addAt → afterMutate → SugarLogicDialog.recordCanvasHistory
→ canvas.save()`, and `save()` is a *pure text read* (`ExprHook.unfoldedText`) that never touches the
canvas (`canvasSourceTest`'s `saveIsAPureTextRead` pins the purity, `exprCardTest`'s
`unfoldedTextMatchesTheUnfoldedCanvas` pins the text). The card's text therefore has to be produced
by the card itself, and it has to keep the canvas/statement index parity:

- `ExprHook.unfoldedText` (canvas entry) / `unfoldedText(List<LStatement>, boolean)` (headless)
  expand a multi-line card into its op lines **in the text layer only**, using the same
  `ExprHook.toStatements` expansion and the same keep-the-card decisions as `unfoldAll`. Jump and
  begin cards record a *canvas* statement index, so those tokens are rewritten to the *text*
  statement index (temporarily, restored in a `finally`); a jump after a multi-line card otherwise
  targets the wrong statement once the text is parsed again. This is why `save()` must not be
  `unfoldAll() → super.save() → foldAll()`: that rebuilt the statement elements on every call, and
  it is called every 24 frames (instruction-budget banner) and every frame when a third-party
  editor wraps the consumer in the coexistence mode — the focused Expression text field lost focus
  and the card visibly flickered (2026-10 report). A fold that refused (external read, rebuild
  failure) could also degrade the card to raw ops permanently.
- `unfoldAll` (canvas) is no longer on the save path: it stays as the *canvas* form of the same
  expansion (and for tests), while **the keep-the-card rule lives in `cardLines` + `keepsCard` +
  `hasUnmappableLine`, shared by both paths** — never duplicate it in one of them. The emit-mode
  auto-assert cards only ever existed inside one unfold, and the fold in the same call removed them
  again, so dropping the canvas unfold loses nothing visible.
- `unfoldAll` only replaces a card when **every** line of its chain has a vanilla statement
  (`hasUnmappableLine`); otherwise it keeps the card. `CopyLine extends RawLine`, so the v5
  value-copy form (`x = 0`, `x = a` → `set x 0`) hit the "unknown RawLine → skip" branch while
  the card had already been removed: the block vanished instead of being added. Any new
  `ExprCompiler.Line` subclass needs a `statementFor` branch.
- Single-line cards are never unfolded (`keepsCard`): in the saved text they already occupy
  exactly one statement, and no fold gate can bring a lone line back (`ExprHook.foldableChain`
  needs `>= 2` lines for every chain, `read`/`write` included), so unfolding would downgrade the
  card to a plain block. `ExprStatement.write()` writes the same text either way — `exprCardTest`
  pins that equality. Persistence instead uses the self-describing marker
  `# @ls-expr-card <dest> "<expr>"` (`ExprStatement.cardMarkerPrefix`, a comment: executable
  stream, statement count and every `destIndex` stay untouched), which `ExprTextImport` turns back
  into the card on load. **Every kept card writes it, single-line array `read`/`write` cards
  included** (`x = buf[3]`, `buf[i] = 5`): the declaration table only says which cells belong to
  the array, never that a line is a subscript access, so the marker is the only identity evidence
  — without it the card silently reopens as a vanilla read/write block (accepted degradation for
  saves written by ≤5.7.1: the product is byte-identical, and a fresh Expr card brings the
  subscript back). Multi-line cards keep relying on the `>= 2` fold threshold and deliberately
  carry no marker in save text (collapsing N lines there would shift indices); a **clipboard
  payload** is the one place where they are written inline (`dest = expr` via
  `ExprTextImport.canWriteInline`) because a fragment is re-parsed by our own paste path — one
  line per card keeps the fragment's jump offsets valid and restores the card verbatim
  (`statementClipboardTest`). Add a fixture to `reconstructionMatrixTest` when the save format
  changes.
- **The marker is evidence, and it has to survive every text rewrite.** A single-line card's
  unfolded line is byte-identical to a plain `set`/`op` block, so the marker is the *only* thing
  that tells the two apart; two paths re-serialize statements and used to drop every comment with
  them (reported 2026-10: the card silently came back as a plain block even though the evidence was
  still in the text):
  - `SugarCompiler.rewriteStaleBlockDests` (stale `destIndex` comment in a stored carrier) now
    re-serializes statements **in place** (`writeStatementsKeepingComments`), so comments, labels
    and blank lines stay exactly where they were instead of being reconstructed from the parsed
    statement list.
  - the decompiler's structure recovery (`SugarDecompiler.infer`) re-attaches the markers through
    `ExprTextImport.attachCardMarkers`, which uses a marker only where the recovered text already
    contains the exact single statement it unfolds to (`ExprCompiler.compile` with a lenient
    function checker, one line); each statement is claimed at most once, and a stale marker (the
    program was edited outside Logic Sugar) matches nothing and is dropped instead of rewriting a
    statement into a card it never was. The inserted lines are comments, so the recompilation gate
    ignores them — which is why attaching after verification is sound. `decompileTest`'s
    `expressionCardMarkersSurviveRecovery` pins both halves.
  - in a saved *product* the marker only exists nested inside the comment marker block
    (`# @logic-sugar-line # @ls-expr-card …`), while `ExprTextImport.plan` pairs a marker only with
    the code line directly above it — so `SugarCanvas.load` hoists the markers out first
    (`attachCardMarkers(asm, asm)`; idempotent, a statement that already carries one is left
    alone). `ExprTextImport.cardMarker` is the single serializer of the format: `ExprStatement.write`
    and the recovery both go through it. `exprCardTest`'s `markerSurvivesTextRewrites` pins the
    rewrite, the hoisting and the stale-marker refusal; `reconstructionMatrixTest`'s
    `decl.exprcard.staleDest` fixture pins the carrier path. The registry context for the marker's
    expression comes from the text itself (`ExprTextImport.textDeclarations` →
    `ArrayRegistry.lenientRegistry`), not from the canvas: on reopen/inference the canvas still
    holds the previous program (or nothing), and a declaration card in a product exists only as a
    `# @logic-sugar-line array …` comment, so `x = buf[3]` would compile to `read x buf 3` and
    match nothing. `exprTextImportTest`'s `markerAttachmentUsesTheTextsOwnDeclarations` pins both
    directions (with the declaration the marker is attached; without it the vanilla read is left
    alone).
- **`foldAll` collapses a retry only when its temporaries are not *read* outside the chain — a
  definition is not a read.** `ExprHook.hasExternalReads` used to fail on any textual occurrence,
  so two identical chains (the second one produced by copying the card) redefined each other's
  `_0` and *both* refused to fold: after the next save the user's card was permanently raw ops
  (2026-10 report: “复制 Expr 积木后，马上转为了编译后形态”). The rule now counts only real reads:
  a statement that writes the name once and never reads it is a *definition*, and a read that
  follows an outside definition gets that value, not the folded chain's. An unrecognised statement
  type still counts every occurrence as a read — the failure direction stays “fold less, never fold
  wrong”. `exprCardTest`'s `duplicateChainsShareTempsWithoutBlockingEachOther`, `arrayTest` and
  `spanTest` pin both directions.
- **Rich-text escaping in the card display: escape `[` only.** `ExprStatement.highlight` wraps each
  token in `[color]…[]`; Arc's markup parser treats `[[` as one literal `[` and leaves `]` alone,
  so escaping `]` as `]]` renders an extra bracket on the card (`result = list[1]` showed as
  `list[1]]`). The same rule applies to the card's error label. `selfTest`'s
  `highlightTextIsUnchanged` case strips the markup and asserts the visible text equals what the
  user typed — keep new display code on that rule.

## Editor rendering paths pinned by source nails

`canvasSourceTest` (`logicsugar.CanvasSourceTest`) pins the two `SugarCanvas` rendering paths that no
headless test can see, both of which come from real reports: `save()` is a **pure text read**
(`ExprHook.unfoldedText`, see the Expression-card section above — it is called periodically, so
folding/unfolding there rebuilt the statement elements and stole focus from a field being edited),
and the address label never alternates between the two index spaces:

**The address label must not alternate between statement index and mlog address.** Vanilla's
`DragLayout.layout()` calls `StatementElem.updateAddress(i)` (the *statement* index); our
`SugarCanvas.updateMlogAddresses()` writes the *instruction* address (`1->2` for a multi-line
Expression card). The order is load-bearing: run the forced `invalidate()/validate()` first, then
write the mlog text, then clear `WidgetGroup.needsLayout` — `Label.setText` re-invalidates the
hierarchy, and the next `draw()`'s `validate()` would otherwise run `layout()` again and restore the
index text. Getting it backwards made the label flip between the two texts every frame (so the
instruction address was never even visible), forced a full statement layout per frame, and the
width change showed up as the card header shaking sideways — reported together with “clicking the
Expression edit area loses focus immediately” (2026-10), whose actual cause was the periodic
`save()` rebuilding the card (above).

## Data subsystem (arrays / matrix / record / containers / bitset / map / list / heap / chain)

The data subsystem is a compile-time abstraction layer: declaration cards are metadata and never
emit instructions; every operation lowers to plain vanilla `read`/`write`/`op`/`funccall`/`jump`,
so saved programs stay vanilla-parseable and multiplayer-safe.

- Framework: `logicsugar.assist.data.DataModule` / `DataModules` + `logicsugar.assist.expr.ExprIntrinsics`
  (`Provider` implementations must live in the `expr` package — `Node`/`Line` are package-private).
  A new structure is 3 new files (`src/logicsugar/assist/data/<Module>.java`,
  `src/logicsugar/assist/expr/<Module>Intrinsics.java`, `test/logicsugar/assist/data/<Module>Test.java`)
  plus exactly one `DataModules.register(new <Module>())` line in
  `LogicSugarMod.registerStatements()` (registration is idempotent by `id()`, and
  `DataModules.registerParsers()` installs the declaration-card parsers). Do not hardcode a module in
  production code outside that registration list. Deque is **not** a new module: it extends
  `ContainerModule` / `ContainerIntrinsics`. Unordered set uses token `uset` (vanilla opcode `set`
  is forbidden).
- Injected functions use the `__ls_builtin_*` prefix. They are merged into the compile-time
  `LibraryIndex` via `SugarFunctions.withBuiltins` but must never enter the user function library or
  the `__ls_lib` carrier (`extractLibrarySource` only sees user library text). Unused builtins stay out
  of the product; normal mode shares one `funcdef` body per operation.
- **Changing a `__ls_builtin_*` body is a compatibility decision, not a local edit.** The body is
  baked into the saved program, and `SugarCompiler.verifyRestore` compares the recompiled stream
  against the stored one instruction by instruction (through `matchesStoredStream` /
  `executableStream`, carrier stripped). Any body change makes every previously saved processor that
  used that builtin fail carrier verification and reopen as the vanilla view (the `array` / sort
  cards live only in the carrier, so they are lost). Accepted break: `sortasc` / `sortdesc` moved from
  insertion sort to Shell sort in the v5 line — documented in `docs/architecture.md` and the array
  tutorials. When touching a builtin body, update those docs and decide break-vs-versioning
  explicitly.
- Getter sugar (`list[i]`, `stack.top()`, `map.get(k)`, `chain.len()`) is resolved in two phases:
  **lowering** uses `ExprIntrinsics.Provider.kindOf` / `methodIntrinsic` / `indexIntrinsic` against
  the active module registry; **analyze** uses `DataModules.declaredKinds(statements)` plus
  `ExprIntrinsics.enterDeclaredKinds`, so `collectCallNodes` can resolve the method/index to an
  intrinsic and emit a root-intrinsic `CallSite`. Builtin-backed aliases (`mapget`, `uhas`, `lfind`,
  `bcount`, `cnext`, `clen`) depend on that analyze-side resolution: without it normal mode omits the
  `__ls_builtin_*` body. Index sugar is read-only; `l[i] = v` stays a compile error that points at
  `lset`/`bset`/`cset`. Mutator method sugar is intentionally not offered.
- Every registered data intrinsic is also exposed as a persistent `datacall` card. Palette
  metadata belongs to its `DataModule`; it records source-level argument defaults and whether
  the operation has a source-level result. Result-bearing cards default to `result = op(args)`;
  void cards omit the destination and lower their implementation sentinel into a private
  `__ls_*datacall_discard` variable. The framework registers the common parser and cards, and
  lowering must reuse `ExprIntrinsics` so carrier restore preserves the operation card while
  the executable product remains vanilla mlog. The legacy eight-slot `arrayinit` token stays
  parseable but is not offered for new programs; use the Array Algorithms `fill(buf, value)` card.
  **Operation names are canonicalized, not just aliased** (v5.2 rename): old carriers parse, but
  `DataCallStatement.canonicalOperation()` / `DataModules.canonicalOperation()` must be the single
  source for the card body, tooltip keys, title and the name written back to the carrier — a
  display or `write()` path that reads the raw `operation` field lets old names reappear in the
  editor and in the next save. Unknown names stay untouched so compile errors stay accurate
  (`dataCallTest` pins both).
- `SugarCompiler.compile` must keep the `DataModules.collectAll(...)` / `DataModules.restore()`
  pairing in its `try/finally`, with the pairing flag set *before* `collectAll` — a module `collect`
  exception must not leak compile-time registries into the next compile or editor render.
- Cross-module validation is per-module by design: each module validates its own declarations plus
  `array`/`matrix`. Name/range conflicts **between different modules** (e.g. a stack and a list on
  overlapping memory ranges, or the same name declared by two different structures) are NOT rejected.
  Do not push this into individual modules; if it is ever needed, add a program-level name/range table
  to `DataModules` and document it in `docs/architecture.md` first.
- Never change the `array` four-token wire format or any declaration-card token count; hidden state
  variables stay `__ls_<kind>_<name>_<field>` (users must not use the `__ls_` prefix), and pop/peek on
  an empty container returns NaN.

## Docs

Classified documentation lives in `docs/` (Chinese, feature names in English), styled after
the Neon main repo's docs:

- `docs/README.md` — navigation: reader-entry table, doc map, related files, conventions.
- `docs/architecture.md` — dual form (standalone / Neon bundled), compiler/decompiler
  pipelines, expression subsystem, cross-loader constraint, decompiler gate, layout map.
- `docs/development.md` — environment, Gradle commands, artifact chain, style rules.
- `docs/release.md` — version scheme, `deploy`/D8 pipeline, Release asset safety rules.
- `docs/testing.md` — the JavaExec self-test tasks (see `check.dependsOn` for the current list;
  count kept in sync there), new-test conventions, manual checklist.
- `docs/glossary.md` — project terminology (carrier, FuncMode, SwitchStrategy, gate, …).

Keep task names and version rules in sync with `build.gradle`; keep cross-loader and gate
wording consistent with this file (this file wins on conflict).

## Neon aggregation (bekBundled)

This repo is the source of truth for the LogicSugar submodule (id `ls`) bundled into the
Neon aggregate mod. The dual form is handled entirely by `LogicSugarMod`:

- `public static boolean bekBundled` is set by the Neon host. When `true`,
  `LogicSugarSettings.setup(...)` is skipped so the mod-owned `@logicsugar.settings`
  category never registers; the host calls `bekBuildSettings(SettingsTable)` instead,
  which currently aggregates func mode, the assert-emit toggle, the function-library entry,
  the processor-status sliders (including the snapshot-on-assertion/breakpoint switches),
  the unit-flag overlay and per-flag coloring, hide-vars, box-select, the vars/snapshot rows
  (`LogicSugarSettings.addVarsPrefs`) and
  jump-line-coloring rows. Do not re-add a self-registered category, and do not move
  `SwitchStrategySetting` into `bekBuildSettings` without updating Neon's sync assertions.
- No other code path branches on the aggregate form: behavior, compilation output and
  persistence are identical in both forms.
- The Neon side registers this repo via its submodule sync (`tools/submods.json` in the
  Neon repo) and asserts the injected structure (`bekBundled` + `bekBuildSettings`); if you
  rename either member, the Neon sync check will fail — coordinate the rename across both
  repos in one change.
- **Neon compatibility is a checklist item on every change, not a detail of the settings
  page (owner-declared requirement, failure seen in practice).** Anything a user can reach or
  change must be reachable and changeable in *both* forms. `bekBuildSettings(SettingsTable)`
  is the complete list of what a bundled user can touch: when `bekBundled` is true
  `LogicSugarSettings.setup(...)` never runs, so a setting registered only there **does not
  exist** for a bundled user — there is no row, no error, and no way to change it. Before
  claiming a feature done, answer both questions:
  - New setting / palette entry / button / overlay / preference row: does it have a
    registration line in *both* `LogicSugarSettings.setup(...)` and `bekBuildSettings(...)`?
    Registering in only one is a bug in the same change — unless the dual form is deliberate,
    in which case say so in writing in that same change (`docs/architecture.md` + the Neon
    side), not just in review talk. A `bekBuildSettings`-only row needs the same justification.
  - Is its default safe for a user who cannot reach the setting? A destructive default plus a
    missing row is the worst case: the bundled user is silently forced into it.
  - Do not add a second self-registered `@logicsugar.settings` category to compensate, and do
    not branch on `bekBundled` outside the settings/install path — behavior, compilation
    output and persistence stay identical in both forms.
- Recorded example (`editorConflict`, PR #15): the new setting was registered only in
  `LogicSugarSettings.setup(...)`, so under `bekBundled` it was unreachable while its default
  `takeover` detaches a third-party logic editor's UI. The fix was one row in
  `bekBuildSettings(...)` plus the matching Neon sync assertion — the class of bug this rule
  exists to prevent.
