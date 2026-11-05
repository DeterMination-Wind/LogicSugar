# Unit classification (unit_family / unit_tier / unit_planet)

> Back to the [tutorial index](README.md) · See also [architecture](../../architecture.md), "Expression subsystem"

## When to use it

Use these getters when a program has to know which factory / tech-tree family a unit belongs to, its tier,
or which planet its type comes from:

- Filter units reached by `unitfor` / `unitbind` (for example, only command the air family).
- Pick upgrade targets or abilities by tier.
- Keep the Serpulo and Erekir unit tables apart.

All three are **expression intrinsics**, not vanilla sensors. They expand at compile time into ordinary
`sensor` / `op` instructions, so the saved program is plain vanilla mlog and behaves the same in single
player and on multiplayer servers. They are not member sugar either: the argument must be a **unit**;
passing a unit type or a building is not meaningful (the game has no runtime "is this a unit type" sensor,
and a building instance can return a plausible non-zero value because block logic ids share the same
numbering space). Pass only units obtained from `unitbind` / `unitfor`.

## Quick examples

```text
f = unit_family(u)          # 0..8
t = unit_tier(u)            # 1..5, 0 = unknown
p = unit_planet(u)          # 1 Serpulo / 2 Erekir, 0 = unknown
```

With a condition card switched to Expr mode (source form `expr "..."`):

```text
unit_family(u) == @__AIR__
unit_tier(u) == @__T5__
unit_planet(u) == @__EREKIR__
```

A complete example: put that expression into the **Expr mode** of an `if` condition card; you do not
hand-write the structural source.

## Family (unit_family)

| Value | Constant | Family | Vanilla logic ids | Examples (v160) |
| --- | --- | --- | --- | --- |
| 0 | `@__UNKNOWN__` | unknown | — | modded / future units, `null` |
| 1 | `@__GROUND__` | Serpulo ground factory | 0..14 | `dagger..reign` (attack), `nova..corvus` (support), `crawler..toxopid` (legs) |
| 2 | `@__AIR__` | Serpulo air factory | 15..24 | `flare..eclipse` (attack), `mono..oct` (support) |
| 3 | `@__NAVAL__` | Serpulo naval factory | 25..34 | `risso..omura` (attack), `retusa..navanax` (support) |
| 8 | `@__CORE__` | core units | 35..37 | alpha, beta, gamma |
| 4 | `@__TANK__` | Erekir tank fabricator | 38..42 | stell, locus, precept, vanquish, conquer |
| 5 | `@__MECH__` | Erekir mech fabricator | 43..47 | merui, cleroi, anthicus, tecta, collaris |
| 6 | `@__SHIP__` | Erekir ship fabricator | 48..52 | elude, avert, obviate, quell, disrupt |
| 7 | `@__SUPPORT__` | Erekir core support | 53..55 | evoke, incite, emanate |

"Family" means the vanilla **factory / tech-tree family**, not movement. Each run of five consecutive ids
inside a family is an independent T1..T5 production line (ground has attack, support and legs; air and
naval have two lines each), so `unit_family` + `unit_tier` does not uniquely identify a unit — for example
`@__GROUND__` + `@__T5__` matches reign, corvus and toxopid. Use `u.type.Name` / `u.type.id` when the exact
unit matters.

## Tier (unit_tier)

- Serpulo: `id % 5 + 1` (0..37).
- Erekir: `(id - 38) % 5 + 1` (38..55).
- Unknown / modded / out of range: `0`.

Tier is the **tech level of a production line inside the family**, not a global power ranking. `@__T1__`..`@__T5__` are 1..5;
`0` only means "unknown", never "tier 0".

## Planet (unit_planet)

| Value | Constant | Range |
| --- | --- | --- |
| 1 | `@__SERPULO__` | logic id 0..37 |
| 2 | `@__EREKIR__` | logic id 38..55 |
| 0 | `@__UNKNOWN__` | anything else (modded / future / `null`) |

It reads the **unit type's logic id**, not the planet the unit is currently standing on. Moving the same
unit type to another map does not change the result.

## Constants

Constant names are case-insensitive and use LogicSugar's reserved `@__` prefix:

| Constant | Value | Purpose |
| --- | --- | --- |
| `@__UNKNOWN__` | 0 | unknown family / planet / tier |
| `@__GROUND__` | 1 | ground family |
| `@__AIR__` | 2 | air family |
| `@__NAVAL__` | 3 | naval family |
| `@__TANK__` | 4 | tank family |
| `@__MECH__` | 5 | mech family |
| `@__SHIP__` | 6 | ship family |
| `@__SUPPORT__` | 7 | support family |
| `@__CORE__` | 8 | core family |
| `@__T1__` .. `@__T5__` | 1..5 | unit tier |
| `@__SERPULO__` | 1 | Serpulo |
| `@__EREKIR__` | 2 | Erekir |
| `@__EPS__` | `1e-6` | vanilla comparison tolerance |
| `@__TILESIZE__` | 8 | world units per tile |
| `@__TICKS_PER_SECOND__` | 60 | ticks per second |
| `@__SECONDS_PER_TICK__` | `1/60` | seconds per tick |

Constants are replaced by numbers at compile time and do not occupy a runtime variable. An unregistered
`@__...` name is a compile error. Matching is case-insensitive: `@__air__` equals `@__AIR__`.

> Cross-version sharing: `@__` constants and the three getters exist only in Sugar source / expression
> conditions. An older LogicSugar without this feature sees `@__` as an ordinary variable (and may report
> an unknown function for a getter call); the compiled processor product is plain vanilla mlog and is
> unaffected. Sharing the product is fine; check the receiver's version before sharing Sugar source.

## Family is not movement

Do not infer family from `@flying` / `@naval` / `u.type.Flying`; on Erekir the two disagree:

- **elude** is `@__SHIP__` family but **grounded**; it cannot fly.
- **avert / obviate / quell / disrupt** are also `@__SHIP__` family and do fly.
- **evoke / incite / emanate** are `@__SUPPORT__` family and fly.

So:

- "Can it fly?" -> `u.type.Flying`.
- "Which factory / tech tree made it?" -> `unit_family(u)`.

## Cost and caching

| Call | Approx. instructions | Notes |
| --- | --- | --- |
| `unit_family(u)` | about 24 | two `sensor`s (`@type`, `@id`) + range ladder + `null` / range guards |
| `unit_tier(u)` | about 14 | two `sensor`s + modulo + non-negative / range / `null` guards |
| `unit_planet(u)` | about 11 | two `sensor`s + range checks + guards |

The default processor budget (`@ipt`) is small, and every call re-emits the `sensor`s and recomputes the
whole chain. Cache the result when the same unit is compared more than once:

```text
f = unit_family(u)
t = unit_tier(u)

# condition card, Expr mode:
f == @__AIR__
```

Avoid `unit_family(u) == @__AIR__ || unit_family(u) == @__NAVAL__`: that duplicates the full expansion.
Cache inside loops too, and put the family filter in the outermost `unitfor` condition when scanning many
units.

## Limitations

### Expression fields only

Supported:

- Expr cards (`x = unit_family(u)`).
- The **expression mode** of `if` / `elif` / `while` / `for` condition cards (source form `expr "..."`).
- Function `return <expr>` expressions.

Not supported, and rejected by the compiler (they never silently become `null` variables):

- Simple condition fields (value / operation / comparison triplets).
- Short-circuit conditions (`exprsc "..."`).
- The `switch` expression field (assign first: `f = unit_family(u)`, then `switch f`).
- `case` values: use a numeric literal such as `case 2`; constants are not expanded there.
- Raw `jump` operands and the value fields of ordinary `set` / `op` cards; getter calls such as
  `unit_family(...)` in those positions are rejected too.

Any `@__` token or getter call that survives lowering is a hard compile error, so a misplaced constant is explicit. Unused function bodies / library functions are not lowered; their constants or getter calls are checked only when the body is used, so they do not affect a save that never uses them.

### No member sugar

`u.family` / `u.tier` / `u.planet` are not supported. Member expansion differs between a unit and a unit
type, and the compiler cannot reliably tell the receiver apart, so only the function forms exist. A user
`funcdef` with one of these names shadows the intrinsic; do not define functions named `unit_family`,
`unit_tier`, or `unit_planet`.

### Multi-line Expr cards reopen as an equivalent formula

`unit_family(u)` expands to multiple instructions. A multi-line Expr card is saved as its expanded text;
on reopen the generic op-chain rebuild produces an **equivalent long formula**, and the function name
`unit_family` is not guaranteed to survive. Condition / structural cards keep their source text and still
show the original expression. If a stable name matters, write the call in a condition, or accept the
formula form after reopening an Expr card.

## Modded and future units

The classification table is **frozen to the vanilla v160 logic id list**:

- Modded units usually get logic id `-1`, or an id vanilla does not use.
- No vanilla unit uses an id above 55 today; future vanilla units will also fall outside the table.
- In all these cases `unit_family` / `unit_tier` / `unit_planet` return `0` (unknown); they never guess.

To identify a modded unit, compare with a vanilla sensor:

```text
u.type.Name == "my-mod-unit"
```

`u.type.id` exposes the same logic id if you want your own mapping. Do not treat `0` as a specific
family; it only means "not in the v160 table".

Do not widen or re-mean the table in place: saved conditions and carrier source are recompiled, and a
different instruction stream fails `verifyRestore`, sending the save back to the vanilla view. Add a new
intrinsic name or version instead.

## What already works without these getters

These are ordinary vanilla `sensor` reads and do not need this feature:

| Expression | Meaning |
| --- | --- |
| `u.type.Flying` | whether it flies (movement) |
| `u.type.Size` | unit size |
| `u.type.Health` | max health |
| `u.type.Armor` | armor |
| `u.type.Range` | attack range |
| `u.type.Speed` | movement speed |
| `u.type.PayloadCapacity` | payload capacity |
| `u.type.ItemCapacity` | item capacity |
| `u.type.Name` | unit type name (string) |
| `u.type.id` | vanilla logic id |

`unit_family` / `unit_tier` / `unit_planet` only classify those raw fields; they add no new runtime data
and do not change the meaning of the saved program.

## Verifying the lowering

1. Write `f = unit_family(u)`, save, and switch to the Original view.
2. You should see two `sensor`s (`@type`, `@id`) followed by `op`s, with no `@__` token and no
   `unit_family` call.
3. Copy the product into a client without the mod; it still parses and runs.
