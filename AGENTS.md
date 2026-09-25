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
- **上游同步基线**：断言/断点子系统对齐 cardillan/MlogAssertions **v0.8.2**（本地副本
  `../_upstream/MlogAssertions-pr`，`git fetch upstream` 更新）。上游的指令上限覆盖
  （`max-instructions`）**不得**移植。唯一线格式例外：`asserttype` 的 `null` 类型是
  LogicSugar 扩展，上游 `AssertDataType.valueOf` 不识别——改这一块前先看
  `SugarAsserts.AssertTypeCard` 的注释与 `assertTypeTest`。

## Build & Test

```powershell
cd LogicSugar; ./gradlew check        # runs selfTest, ifElseTest, decompileTest, reconstructionTest, reconstructionMatrixTest, recoveryPredicateTest,
                                      # shortCircuitTest, crossLoaderTest, boxSelectTest, cfgTest, lintTest,
                                      # varClipboardTest, processorStatusTest, unitFlagsTest, assertTest, assertTypeTest, arrayTest,
                                      # arrayBulkTest, dataFrameworkTest, recordTest, containerTest, bitsetTest,
                                      # mapTest, setTest, listHeapTest, chainTest, dataSubsystemTest, editHistoryTest,
                                      # bottomBarLayoutTest, escapePreviewTest, v160SensorAccessTest, exprTextImportTest,
                                      # exprCardTest,
                                       # funclibLimitTest, originTest, counterJumpIndexTest
./gradlew check jar                   # build + dev jar at build/libs/ (copy to 构建/LogicSugar/LogicSugar-dev.jar)
```

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
- Rule: any helper that touches protected game-class members must be an instance method on
  the `SugarStatement` subclass (public if a static editor like
  `rebuildConditionEditor` needs to call it). Static code may only use public game API.
- `crossLoaderTest` simulates this loader split headlessly (child-first loader defines our
  classes, `LStatement` stays on the parent) and fails with guidance if the pattern
  regresses.

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
   the assertion/debug cards.
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
  structure and instruction count unchanged) and that is what `verify` compares against.

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

**Checklist for any change that touches the compiled product:**

- Update the carrier path so the new card/feature survives `compile → save → restore → verifyRestore`.
- Update decompiler inference when the shape is expressible from vanilla jumps; otherwise
  document "carrier-only / show vanilla" here.
- Add fixtures to `test/mindustry/logic/ReconstructionMatrixTest.java`: at minimum one
  carrier fixture; add an inference fixture for every provable new shape. The test must stay
  above 100 fixtures and must fail when a new block has no reconstruction coverage.
- Run `.\gradlew.bat reconstructionTest reconstructionMatrixTest decompileTest` before
  claiming the feature complete.

**Text import is another way into the same pipeline.** `SugarCanvas.load` runs
`ExprTextImport.plan` first: a line that vanilla `LParser` cannot dispatch (`x = buf[3]`,
`buf[i] = 5`, `result = (a + b) * 2`) is swapped for a unique `set __ls_import_N 0` sentinel
(one line for one line, so label/jump indices do not move) and the sentinel is replaced by an
`ExprStatement` card after the parse. Everything after that is the normal
`unfoldAll`/`foldAll` path, so products stay pure vanilla mlog and the carrier coverage above
applies unchanged. Keep the conservative skip list in sync when adding sugar line forms (see
`ExprTextImportSelfTest`): anything whose first token is already claimed by `LogicIO.read` or
`LAssembler.customParsers`, comparisons (`==`/`!=`/`<=`/`>=`), strings, comments and one-line
multi-statements must stay untouched.

**An `ExprStatement` card must survive `save()` — treated as a block, not a formatting detail.**
Every palette insert triggers `SugarCanvas.addAt → afterMutate → SugarLogicDialog.recordCanvasHistory
→ canvas.save()`, and `save()` runs `ExprHook.unfoldAll()` + `foldAll()`. Two invariants keep the
card alive (both were violated by the 2026-09 regression where "Expr" in the add-block dialog
appeared to do nothing):

- `unfoldAll` only replaces a card when **every** line of its chain has a vanilla statement
  (`hasUnmappableLine`); otherwise it keeps the card. `CopyLine extends RawLine`, so the v5
  value-copy form (`x = 0`, `x = a` → `set x 0`) hit the "unknown RawLine → skip" branch while
  the card had already been removed: the block vanished instead of being added. Any new
  `ExprCompiler.Line` subclass needs a `statementFor` branch.
- Single-line cards are never unfolded (`keepsCard`): in the saved text they already occupy
  exactly one statement, and `foldAll` cannot fold a lone `op`/`set` line back (its single-line
  rule covers array `read`/`write` only), so unfolding would downgrade the card to a plain block.
  `ExprStatement.write()` writes the same text either way — `exprCardTest` pins that equality.
  Persistence instead uses the self-describing marker `# @ls-expr-card <dest> "<expr>"`
  (`ExprStatement.cardMarkerPrefix`, a comment: executable stream, statement count and every
  `destIndex` stay untouched), which `ExprTextImport` turns back into the card on load. Multi-line
  cards keep relying on the `>= 2` fold threshold and deliberately carry no marker (collapsing
  N lines would shift indices). Add a fixture to `reconstructionMatrixTest` when the format changes.
- **Rich-text escaping in the card display: escape `[` only.** `ExprStatement.highlight` wraps each
  token in `[color]…[]`; Arc's markup parser treats `[[` as one literal `[` and leaves `]` alone,
  so escaping `]` as `]]` renders an extra bracket on the card (`result = list[1]` showed as
  `list[1]]`). The same rule applies to the card's error label. `selfTest`'s
  `highlightTextIsUnchanged` case strips the markup and asserts the visible text equals what the
  user typed — keep new display code on that rule.

## @counter indicator line (left-side mirrored jump line)

`set @counter N` / `@counter += k` / `@counter -= k` (set / op / Expr cards) are jumps at runtime:
`LExecutor.runOnce()` reads then post-increments (`instructions[(int)(counter.numval++)].run(this)`),
so execution continues at instruction N. `mindustry.logic.CounterJumpOverlay` draws a mirrored
jump line on the **left** of the writing card, pointing at the target card. Purely presentational —
it never changes the saved product, so it is **not** subject to the multiplayer gate, and it must
stay that way.

Three load-bearing properties and one recorded bug; each fails silently:

- **`Write.targets` holds instruction indices; `elementAt(i)` takes a statement index — never mix them.**
  That mixup is the 2026-09 misalignment report: `set @counter 3` drew its line to the 4th card
  because 3 was used as a statement index. Every target must go through
  `provenance.originOf(...)` before it can name a card, and a target whose origin is `-1` (function
  body, compiler-generated instruction) is unresolvable — badge only, never a guessed line.
  `originTest`'s `counterTargetsResolveThroughProvenance` pins the conversion, and step 17 of
  `docs/testing.md`'s manual checklist exists because no automated check can see *where* a line was
  drawn. Generalisation worth carrying to other features: whenever an `int` could be two of
  "instruction index / statement index / line number", say which in the name or the comment —
  the same trap appears as `originOfLine`'s line numbers vs `origins`' instruction indices.
- **The compile-time origin channel must not touch the product.**
  `SugarFunctions.OriginRecording` records `[from, to)` line ranges per statement from *outside*
  the emission code (`countLines(out)` before/after each statement in `lower`). It is deliberately
  not an `Appendable`/`StringBuilder` wrapper — `StringBuilder` cannot be subclassed under
  `--release 17`. `originTest` asserts `compileRecorded(...)` and `compile(...)` return
  **byte-identical** products across every fixture and both FuncModes; keep that assertion green
  for any change to `lower()`. Do not add per-emission-site bookkeeping: the range measurement is
  the whole point.
- **`markSynthetic` must not pre-allocate.** `synthetic.length` feeds the line-count ceiling in
  `flatten`, so padding the array makes phantom lines real; `lineCount()` then exceeds the actual
  text and `compileRecorded` returns null for *every* program. Same reason `flatten(totalLines)`
  takes the body's true line count from the caller: the range table only knows how far the
  *productive* statements reach, and the trailing label plus the entry skip are in no range.
- **Statement attribution comes from `CompileProvenance`, not from `CounterJumpIndex.Write.statement`.**
  The resolver treats negative provenance slots (`sugar-functions` `syntheticOrigin`) as unknown and
  re-fills them from `__ls_stmt_<N>:` label heuristics — correct when provenance is absent, wrong
  when it is present. The overlay uses `provenance.originOf(write.instruction)`.

Never guess a target: one target ⇒ solid line, several candidates ⇒ badge only (phantom lines on
hover, `logicsugar.counterJump.candidates`), unresolvable ⇒ grey badge. Writes that belong to no
card (function bodies, compiler-generated `@counter` shapes) get a grey chip under the statement
area instead of silently disappearing.

**It must look like the vanilla jump line, mirrored — including the rail allocation.** Anchor both
endpoints at the cards' own **left edge** (local `x = 0` through `localToAscendantCoordinates` — the
same coordinate discipline as `StructureGuideLayer`), use `Lines.stroke(Scl.scl(4f), color)` and the
`Tex.logicNode` arrow at the target end: copy `JumpCurve.draw` / `drawCurve` and mirror the lateral
direction (`x - rail` instead of `x + uiHeight`). Do not invent geometry — an early version put the
endpoints on an "outside rail" (`-Scl.scl(5f)`), used a 3.2f stroke and a `Fill.circle`, which
produced a line hanging off the cards that looked nothing like a jump line (2026-09 report). When a
request says "like X", read X's code first and mirror it item by item.

**The lateral distance is per-curve, from the lane allocator — never a fixed `bow`.** Vanilla's
`uiHeight` is `Scl.scl(40) + Scl.scl(10) * predHeight` (portrait `20`/`8`), and `predHeight` comes
from `StatementsTable.setJumpHeights` + `getJumpHeight`: an interval colouring that gives every
overlapping jump its own rail and keeps nested spans closer to the cards. Copying only `drawCurve`
and hard-coding `bow = 18f` therefore drew every `@counter` line on the *same* vertical rail, so
they ran over one another (2026-09-25 report: "加一个类似 jump 的轨道算法让线不交叉"). The
algorithm now lives in `logicsugar.assist.JumpLanes.assign(begin, end, flipped)` — a faithful mirror
including the `reprBefore`/`reprAfter` representative merge, extracted as a pure function so it *is*
testable headlessly (`counterJumpIndexTest`'s `jumpLanesSeparateOverlappingCurves`: disjoint spans
share the innermost rail, touching spans may share, overlapping ones must not, nesting keeps the
inner span closer, identical spans merge). `originTest.overlayRailsFollowLanes` pins that the overlay
actually consults it. Vanilla smooths a rail change with `Mathf.lerp(target, current, pow(0.9,
delta))`; keep that, or switching rails snaps. The arrow size must not be derived from `|tx - sx|`:
both anchors sit on the same edge, so that is 0 and the arrow would never be drawn.

**The target arrow is a double mirror — flip the x offset and the width together.** Vanilla draws
`Tex.logicNode.draw(t.x + 0.75f * s, t.y - s / 2f, -s, s)`: libGDX normalises a negative width into
`[t.x - 0.25s, t.x + 0.75s]` **and** mirrors the texture, so the arrow straddles the card's right
edge and points into the card. Mirroring to our left edge means the x offset becomes `-0.75f * s`
*and* the width becomes `+s` (the two mirrors cancel) — rect `[tx - 0.75s, tx + 0.25s]`, pointing
into the card. Flipping only the x offset left the arrow floating 15–35 px outside the card with its
texture still reversed, i.e. pointing *away* from the target (2026-09-25 report: "这个箭头位置对吗").
`originTest.overlayRailsFollowLanes` pins the exact call.

**Both axes of an anchor are element-local coordinates.** `localToAscendantCoordinates` adds the
element's `x`/`y` (its offset inside the parent table) itself, so an anchor must not include them —
`anchorY` returning `elem.y + getHeight()/2f` counted that offset twice and lifted each anchor by
its own `elem.y`. The statements table is top-aligned, so the write card (late in the program, small
`y`) stayed roughly put while the target card (earlier, large `y`) was thrown above the top of the
screen: a line that leaves the cards and points at nothing (2026-09-25 report). Vanilla takes
`hover.getHeight()/2f` for exactly this reason and `StructureGuideLayer` takes `0` / `getHeight()`.
Only the X axis hid the mistake, because it wants no offset — which a local `0` already is.
`originTest`'s `anchorsAreLocalCoordinates` nails the code (comments stripped, since the method's own
comment names the wrong expression) so it cannot come back unnoticed.

**The per-frame path must use `SugarCanvas.readonlyText()`, never `save()`.** The overlay polls the
canvas text every frame for its compile cache, and the undo history polls it every few frames.
`SugarCanvas.save()` is a *persisting* API: it runs `structure.refresh()` plus
`ExprHook.unfoldAll`/`foldAll`, which remove/add statement elements and hand back the **unfolded**
text — while `elementAt(i)` indexes the canvas as it is on screen (folded). `LCanvas.save()` on the
read-only path is no better: it calls `saveUI()` on every statement and `JumpStatement.saveUI()`
throws NPE when its target element is detached (that is what `SugarCanvas.normalizeJumpUI` exists
for). Called from an `update()` callback, either one turns "one bad frame" into "the callback dies
and the line never comes back" — the reported symptom was a long program drawing nothing, with a
single flash right after an edit (2026-09-25). So: per-frame text ⇒ `readonlyText()` (null-safe,
no folding), history snapshots ⇒ the same, and the callback wraps both the snapshot and the drawing
in `catch(Throwable)` with `noteOnce` logging, because a purely visual feature that dies silently is
undiagnosable by design. `originTest`'s `overlayUsesReadonlySnapshot` pins all three.

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
  the processor-status sliders, the unit-flag overlay and per-flag coloring, hide-vars, box-select and
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
