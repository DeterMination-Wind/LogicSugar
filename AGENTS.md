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
  联机（已连接或自建）一律回落原版行为。纯展示类功能（如处理器状态指示、变量复制按钮）
  不产生存档差异，不受此限。不提供改变指令预算的能力：指令上限覆盖曾试做后被移除
  （commit ea97e00），保存产物恒 ≤1000 条是硬不变式，勿再引入。
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
cd LogicSugar; ./gradlew check        # runs selfTest, ifElseTest, decompileTest, recoveryPredicateTest,
                                      # shortCircuitTest, crossLoaderTest, boxSelectTest, cfgTest, lintTest,
                                      # varClipboardTest, processorStatusTest, assertTest, assertTypeTest, arrayTest,
                                      # arrayBulkTest, dataFrameworkTest, recordTest, containerTest, bitsetTest,
                                      # mapTest, listHeapTest, chainTest, dataSubsystemTest
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
  production code outside that registration list.
- Injected functions use the `__ls_builtin_*` prefix. They are merged into the compile-time
  `LibraryIndex` via `SugarFunctions.withBuiltins` but must never enter the user function library or
  the `__ls_lib` carrier (`extractLibrarySource` only sees user library text). Unused builtins stay out
  of the product; normal mode shares one `funcdef` body per operation.
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
- `docs/testing.md` — the twenty-three JavaExec self-test tasks, new-test conventions, manual
  checklist.
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
  the instruction-limit slider, the processor-status sliders, hide-vars, box-select and
  jump-line-coloring rows. Do not re-add a self-registered category, and do not move
  `SwitchStrategySetting` into `bekBuildSettings` without updating Neon's sync assertions.
- No other code path branches on the aggregate form: behavior, compilation output and
  persistence are identical in both forms.
- The Neon side registers this repo via its submodule sync (`tools/submods.json` in the
  Neon repo) and asserts the injected structure (`bekBundled` + `bekBuildSettings`); if you
  rename either member, the Neon sync check will fail — coordinate the rename across both
  repos in one change.
