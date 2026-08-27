# LogicSugar — Agent Notes

Mindustry Java mod that adds structured `for` / `while` / `switch` / function blocks to the
logic editor while storing vanilla-compatible mlog. Workspace-wide rules (build modes, dev
identity `LogicSugar-dev / 0.0.0`, release safety) live in the parent `codex/AGENTS.md`;
this file only adds what is specific to this project.

## Build & Test

```powershell
cd LogicSugar; ./gradlew check        # runs selfTest, ifElseTest, decompileTest, crossLoaderTest
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
programs".

## Docs

Classified documentation lives in `docs/` (Chinese, feature names in English), styled after
the Neon main repo's docs:

- `docs/README.md` — navigation: reader-entry table, doc map, related files, conventions.
- `docs/architecture.md` — dual form (standalone / Neon bundled), compiler/decompiler
  pipelines, expression subsystem, cross-loader constraint, decompiler gate, layout map.
- `docs/development.md` — environment, Gradle commands, artifact chain, style rules.
- `docs/release.md` — version scheme, `deploy`/D8 pipeline, Release asset safety rules.
- `docs/testing.md` — the seven JavaExec self-test tasks, new-test conventions, manual
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
  which currently aggregates func mode, the function-library entry, hide-vars, box-select
  and jump-line-coloring rows. Do not re-add a self-registered category, and do not move
  `SwitchStrategySetting` into `bekBuildSettings` without updating Neon's sync assertions.
- No other code path branches on the aggregate form: behavior, compilation output and
  persistence are identical in both forms.
- The Neon side registers this repo via its submodule sync (`tools/submods.json` in the
  Neon repo) and asserts the injected structure (`bekBundled` + `bekBuildSettings`); if you
  rename either member, the Neon sync check will fail — coordinate the rename across both
  repos in one change.
