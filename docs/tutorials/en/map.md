# Map (hash map)

> [Tutorial index](README.md) | Previous: [Bitset](bitset.md) | Next: [Unordered set](uset.md) | Chinese: [../map.md](../map.md)

## When to use

Numeric key to value mapping: counters, lookup tables, dictionaries, per-id state. A hit usually needs only a few probes; a miss and a new-key insert always scan the whole table (probing cannot stop at an empty slot because a tombstone may hide the key), and it must be initialized first.

## Declaration card

```text
map <name> <memory> <base> <capacity>
```

Layout:

```text
key area:   [base, base + capacity)
value area: [base + capacity, base + 2*capacity)
```

- hash = abs(key) % capacity
- linear probing over the whole ring
- empty slot marker: NaN
- delete: write NaN to the key slot (tombstone; no shift, no rehash)

Example: `map m cell1 0 4` uses cell1 addresses 0..7 (4 keys + 4 values).

Call `map_clear(m)` before first use. Uninitialized memory reads as numeric 0 and would be treated as an occupied slot with key 0.

## Function table

| Function | Arguments | Returns | Notes |
| --- | --- | --- | --- |
| `map_set(m, k, v)` | map, key, value | 1 / -1 | insert or update; -1 when full or invalid key |
| `map_get(m, k)` | map, key | value or NaN | NaN when missing |
| `map_contains(m, k)` | map, key | 1 / 0 | membership |
| `map_erase(m, k)` | map, key | 1 / -1 | delete; -1 when the key is missing |
| `map_size(m)` | map | non-empty key count | O(capacity) scan |
| `map_clear(m)` | map | 0 sentinel | writes NaN to every key slot; O(capacity) |

> Note: the editor menus and cards use the new names (e.g. `map_set`); the old short names (e.g. `mapset`) still parse in existing saves, and new cards are always written with the new names.

Sugar: `m[k]` / `m.get(k)` equal `map_get(m, k)`; `m.has(k)` / `m.containsKey(k)` equal `map_contains(m, k)`; `m.size()` / `m.length()` / `m.count()` equal `map_size(m)`.
## Lowered examples

Every operation becomes a funccall to an injected `__ls_builtin_map*` function.

```text
map m cell1 0 4
x = map_set(m, 1, 10)
```

```text
funccall __ls_builtin_mapset "cell1, 0, 4, 1, 10" x
```

More examples:

```text
x = map_get(m, 1)         -> funccall __ls_builtin_mapget   "cell1, 0, 4, 1" x
x = map_contains(m, 1)    -> funccall __ls_builtin_maphas   "cell1, 0, 4, 1" x
x = map_erase(m, 1)       -> funccall __ls_builtin_mapdel   "cell1, 0, 4, 1" x
x = map_size(m)           -> funccall __ls_builtin_mapsize  "cell1, 0, 4" x
map_clear(m)              -> funccall __ls_builtin_mapclear "cell1, 0, 4" x
```

Expression arguments compile first:

```text
x = map_set(m, 3, a + 1)
```

```text
op add _0 a 1
funccall __ls_builtin_mapset "cell1, 0, 4, 3, _0" x
```

## Complexity

| Operation | Complexity | Notes |
| --- | --- | --- |
| map_get / map_contains / map_erase (hit) | O(1) average, O(capacity) worst | linear probing from the hash slot to the key |
| map_get / map_contains / map_erase (miss) | Theta(capacity) | the probe only ends after scanning the whole table (N >= capacity); an empty slot / tombstone cannot stop it early |
| map_set (update existing key) | O(1) average, O(capacity) worst | probes to the key and updates its value slot |
| map_set (insert new key) | Theta(capacity) | must scan the whole table to rule out a duplicate, then write the first free slot |
| map_size | O(capacity) | counts non-empty keys |
| map_clear | O(capacity) | fills the key area |

## Caveats

- Call map_clear(m) before first use; otherwise numeric 0 slots look like a valid key 0.
- Numeric keys only. Strings are not supported. Key comparison uses vanilla equal with about 1e-6 tolerance.
- Invalid keys (NaN, +Inf, -Inf) are rejected: map_set returns -1, map_get returns NaN, map_contains returns 0 and map_erase returns -1.
- Full table: map_set returns -1 and does not overwrite; tombstone slots from deletes are reusable.
- Values travel through the function return channel, so object values degrade to 1/0. Store ids or coordinates instead.
- Capacity: the map occupies 2 * capacity slots; base + 2*capacity beyond the block is a compile error.
- Names must not collide with other maps, sets, arrays/matrices or user functions. Multiple maps on one block must not overlap; cross-module overlaps are not rejected automatically.
- Initialization and persistence: keys and values live in memory and survive saves/reloads. LogicSugar never initializes the map automatically; call map_clear(m) when you need a fresh table.

