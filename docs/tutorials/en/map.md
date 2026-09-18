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

Call `mapclear(m)` before first use. Uninitialized memory reads as numeric 0 and would be treated as an occupied slot with key 0.

## Function table

| Function | Arguments | Returns | Notes |
| --- | --- | --- | --- |
| `mapset(m, k, v)` | map, key, value | 1 / -1 | insert or update; -1 when full or invalid key |
| `mapget(m, k)` | map, key | value or NaN | NaN when missing |
| `maphas(m, k)` | map, key | 1 / 0 | membership |
| `mapdel(m, k)` | map, key | 1 / 0 | delete; 0 when missing |
| `mapsize(m)` | map | non-empty key count | O(capacity) scan |
| `mapclear(m)` | map | 0 sentinel | writes NaN to every key slot; O(capacity) |

Sugar: `m[k]` / `m.get(k)` equal `mapget(m, k)`; `m.has(k)` / `m.containsKey(k)` equal `maphas(m, k)`; `m.size()` / `m.length()` / `m.count()` equal `mapsize(m)`.
## Lowered examples

Every operation becomes a funccall to an injected `__ls_builtin_map*` function.

```text
map m cell1 0 4
x = mapset(m, 1, 10)
```

```text
funccall __ls_builtin_mapset "cell1, 0, 4, 1, 10" x
```

More examples:

```text
x = mapget(m, 1) -> funccall __ls_builtin_mapget   "cell1, 0, 4, 1" x
x = maphas(m, 1) -> funccall __ls_builtin_maphas   "cell1, 0, 4, 1" x
x = mapdel(m, 1) -> funccall __ls_builtin_mapdel   "cell1, 0, 4, 1" x
x = mapsize(m)   -> funccall __ls_builtin_mapsize  "cell1, 0, 4" x
mapclear(m)      -> funccall __ls_builtin_mapclear "cell1, 0, 4" x
```

Expression arguments compile first:

```text
x = mapset(m, 3, a + 1)
```

```text
op add _0 a 1
funccall __ls_builtin_mapset "cell1, 0, 4, 3, _0" x
```

## Complexity

| Operation | Complexity | Notes |
| --- | --- | --- |
| mapget / maphas / mapdel (hit) | O(1) average, O(capacity) worst | linear probing from the hash slot to the key |
| mapget / maphas / mapdel (miss) | Theta(capacity) | the probe only ends after scanning the whole table (N >= capacity); an empty slot / tombstone cannot stop it early |
| mapset (update existing key) | O(1) average, O(capacity) worst | probes to the key and updates its value slot |
| mapset (insert new key) | Theta(capacity) | must scan the whole table to rule out a duplicate, then write the first free slot |
| mapsize | O(capacity) | counts non-empty keys |
| mapclear | O(capacity) | fills the key area |

## Caveats

- Call mapclear(m) before first use; otherwise numeric 0 slots look like a valid key 0.
- Numeric keys only. Strings are not supported. Key comparison uses vanilla equal with about 1e-6 tolerance.
- Invalid keys (NaN, +Inf, -Inf) are rejected: mapset returns -1, mapget returns NaN, maphas / mapdel return 0.
- Full table: mapset returns -1 and does not overwrite; tombstone slots from deletes are reusable.
- Values travel through the function return channel, so object values degrade to 1/0. Store ids or coordinates instead.
- Capacity: the map occupies 2 * capacity slots; base + 2*capacity beyond the block is a compile error.
- Names must not collide with other maps, sets, arrays/matrices or user functions. Multiple maps on one block must not overlap; cross-module overlaps are not rejected automatically.
- Initialization and persistence: keys and values live in memory and survive saves/reloads. LogicSugar never initializes the map automatically; call mapclear(m) when you need a fresh table.

