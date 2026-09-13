# Unordered set (uset)

> [Tutorial index](README.md) | Previous: [Map](map.md) | Next: [List](list.md) | Chinese: [../uset.md](../uset.md)

## When to use

Membership of numeric values: de-duplication, visited marks, visibility counts. It uses the same open addressing as the map but has no value area, so it uses half the memory.

## Declaration card

```text
uset <name> <memory> <base> <capacity>
```

- The token must be uset; do not write set (that is a vanilla opcode).
- Only the key area [base, base + capacity) is used.
- hash = abs(key) % capacity; linear probing; NaN empty marker; deletes leave tombstones.
- Call uclear(s) before first use.

Example: `uset s cell1 0 4` uses cell1 addresses 0..3.

## Function table

| Function | Arguments | Returns | Notes |
| --- | --- | --- | --- |
| `uadd(s, v)` | set, value | 1 / -1 | success even if already present; -1 when full or invalid |
| `uhas(s, v)` | set, value | 1 / 0 | membership |
| `udel(s, v)` | set, value | 1 / 0 | delete; 0 when missing |
| `usize(s)` | set | element count | O(capacity) scan |
| `uclear(s)` | set | 0 sentinel | writes NaN to every slot; O(capacity) |

Sugar: `s.has(v)` / `s.contains(v)` equal `uhas(s, v)`; `s.size()` / `s.length()` / `s.count()` equal `usize(s)`.
## Lowered examples

```text
uset s cell1 0 4
x = uadd(s, 1)
```

```text
funccall __ls_builtin_usetadd "cell1, 0, 4, 1" x
```

More examples:

```text
x = uhas(s, 1) -> funccall __ls_builtin_usethas   "cell1, 0, 4, 1" x
x = udel(s, 1) -> funccall __ls_builtin_usetdel   "cell1, 0, 4, 1" x
x = usize(s)   -> funccall __ls_builtin_usetsize  "cell1, 0, 4" x
uclear(s)      -> funccall __ls_builtin_usetclear "cell1, 0, 4" x
```

## Complexity

| Operation | Complexity | Notes |
| --- | --- | --- |
| uadd / uhas / udel | O(1) average, O(capacity) worst | more collisions or a full table degrade to a linear scan |
| usize | O(capacity) | counts non-empty slots |
| uclear | O(capacity) | fills the key area |

## Caveats

- Call uclear(s) before first use.
- Numeric values only; comparison uses vanilla equal with about 1e-6 tolerance.
- Invalid values (NaN, +Inf, -Inf): uadd returns -1, uhas / udel return 0.
- Adding a duplicate returns 1 and does not increase usize.
- Full table: uadd returns -1; tombstone slots from deletes are reusable.
- Capacity: the set occupies capacity slots; base + capacity beyond the block is a compile error.
- Initialization and persistence: keys live in memory and survive saves; there is no hidden state variable. Call uclear(s) when you need a fresh set.
- Names must not collide with other sets, maps, arrays/matrices or user functions; cross-module overlaps are a known limitation.

