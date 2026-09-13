# Record (record)

> [Tutorial index](README.md) | Previous: [Matrix](matrix.md) | Next: [Stack](stack.md) | Chinese: [../record.md](../record.md)

## When to use

Pack related values into an object: unit state hp/mp/x/y, recipe parameters, multiple return values. A record does not occupy memory; its fields are ordinary mlog variables with O(1) access.

## Declaration card

```text
record <name> <f1> <f2> ... <f8>
```

| Rule | Detail |
| --- | --- |
| Field count | at least 1, at most 8 |
| Empty slots | use `~`; the card always has 10 tokens |
| Field names | match [A-Za-z_][A-Za-z0-9_]*, case-sensitive |
| Reserved prefix | the record name and field names must not start with __ls_ |

Example: `record p hp mp x y ~ ~ ~ ~` creates fields p_hp, p_mp, p_x, p_y.

## Member access

| Form | Meaning | Lowered |
| --- | --- | --- |
| `p.hp` | read field | `op add <result> p_hp 0` |
| `p.hp = expr` | write field | compile expr, then `op add p_hp <value> 0` |

## Lowered examples

Read:

```text
record p f1 f2 ~ ~ ~ ~ ~ ~
x = p.f1
```

```text
op add x p_f1 0
```

Two fields in one expression:

```text
x = p.f1 + p.f2
```

```text
op add _0 p_f1 0
op add _1 p_f2 0
op add x _0 _1
```

Write:

```text
record p f1 f2 ~ ~ ~ ~ ~ ~
p.f1 = 5
p.f2 = a + 1
```

```text
op add p_f1 5 0
op add _0 a 1
op add p_f2 _0 0
```

Field variables are normal variables and appear in the in-game variable list, which helps debugging.

## Complexity

| Operation | Complexity |
| --- | --- |
| read p.f1 | O(1), one op add |
| write p.f1 = v | O(1), one op add |

## Caveats

- The declaration card emits no instructions. It only tells the compiler that p is a record and which fields it has.
- Fields are ordinary variables. Reloading the processor resets them to 0/NaN; records do not persist across saves. For persistence, write into a memory block or use map/list.
- Field names are case-sensitive. `p.F1` and `p.f1` are different; a wrong field name is a compile error, not a new variable.
- At most 8 fields. Split the record or use array/list for more.
- Field variables use the form name_field and must not collide with arrays, matrices, functions or other record fields.
- sensor compatibility: record expansion only applies when the base is a declared record name. Members on other bases such as `unit.health` still use the vanilla sensor path. On a declared record, an unknown member is a typo error instead of a sensor fallback.
- Records cannot nest. Fields are scalars; use separate records/arrays or a chain for deeper structures.

