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
- Call set_clear(s) before first use.

Example: `uset s cell1 0 4` uses cell1 addresses 0..3.

## Function table

| Function | Arguments | Returns | Notes |
| --- | --- | --- | --- |
| `set_add(s, v)` | set, value | 1 / -1 | success even if already present; -1 when full or invalid |
| `set_contains(s, v)` | set, value | 1 / 0 | membership |
| `set_remove(s, v)` | set, value | 1 / -1 | delete; -1 when the member is missing |
| `set_size(s)` | set | element count | O(capacity) scan |
| `set_clear(s)` | set | 0 sentinel | writes NaN to every slot; O(capacity) |

> Note: the editor menus and cards use the new names (e.g. `set_add`); the old short names (e.g. `uadd`) still parse in existing saves, and new cards are always written with the new names.

Sugar: `s.has(v)` / `s.contains(v)` equal `set_contains(s, v)`; `s.size()` / `s.length()` / `s.count()` equal `set_size(s)`.
## Lowered examples

```text
uset s cell1 0 4
x = set_add(s, 1)
```

```text
funccall __ls_builtin_usetadd "cell1, 0, 4, 1" x
```

More examples:

```text
x = set_contains(s, 1)    -> funccall __ls_builtin_usethas   "cell1, 0, 4, 1" x
x = set_remove(s, 1)      -> funccall __ls_builtin_usetdel   "cell1, 0, 4, 1" x
x = set_size(s)           -> funccall __ls_builtin_usetsize  "cell1, 0, 4" x
set_clear(s)              -> funccall __ls_builtin_usetclear "cell1, 0, 4" x
```

## Complexity

| Operation | Complexity | Notes |
| --- | --- | --- |
| set_contains / set_remove (hit), set_add (already present) | O(1) average, O(capacity) worst | linear probing from the hash slot to the value |
| set_contains / set_remove (miss) | Theta(capacity) | the probe only ends after scanning the whole table (N >= capacity); an empty slot / tombstone cannot stop it early |
| set_add (new value) | Theta(capacity) | must scan the whole table to rule out a duplicate, then write the first free slot |
| set_size | O(capacity) | counts non-empty slots |
| set_clear | O(capacity) | fills the key area |

## Caveats

- Call set_clear(s) before first use.
- Numeric values only; comparison uses vanilla equal with about 1e-6 tolerance.
- Invalid values (NaN, +Inf, -Inf): set_add returns -1, set_contains returns 0 and set_remove returns -1.
- Adding a duplicate returns 1 and does not increase set_size.
- Full table: set_add returns -1; tombstone slots from deletes are reusable.
- Capacity: the set occupies capacity slots; base + capacity beyond the block is a compile error.
- Initialization and persistence: keys live in memory and survive saves; there is no hidden state variable. Call set_clear(s) when you need a fresh set.
- Names must not collide with other sets, maps, arrays/matrices or user functions; cross-module overlaps are a known limitation.

