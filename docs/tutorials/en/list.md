# List (list)

> [Tutorial index](README.md) | Previous: [Unordered set](uset.md) | Next: [Min-heap](heap.md) | Chinese: [../list.md](../list.md)

## When to use

A compact sequence: indexed read/write, ordered insert/remove, element search. There are no holes; elements always live at base .. base+count-1.

## Declaration card

```text
list <name> <memory> <base> <size>
```

Example: `list l cell1 2 4` uses cell1 addresses 2..5 and holds at most 4 elements.

State is the hidden variable `__ls_lst_l_count`. The unset value is 0, so a list starts empty safely.

## Function table

| Function | Arguments | Returns | Notes |
| --- | --- | --- | --- |
| `vector_push_back(l, v)` | list, value | new count or -1 | full: returns -1 and does not write |
| `vector_at(l, i)` | list, index | value or NaN | NaN when out of range |
| `vector_set(l, i, v)` | list, index, value | 1 / -1 | out of range does not write and returns -1 |
| `vector_insert(l, i, v)` | list, index, value | 1 / -1 | shift right; full/out of range returns -1 |
| `vector_erase(l, i)` | list, index | removed value or NaN | shift left; out of range returns NaN |
| `vector_find(l, v)` | list, value | index or -1 | first match |
| `vector_size(l)` | list | element count | O(1) |

> Note: the editor menus and cards use the new names (e.g. `vector_push_back`); the old short names (e.g. `lappend`) still parse in existing saves, and new cards are always written with the new names.

Sugar: `l[i]` / `l.get(i)` equal `vector_at(l, i)`; `l.find(v)` / `l.indexOf(v)` equal `vector_find(l, v)`; `l.size()` / `l.length()` / `l.count()` equal `vector_size(l)`.

## Lowered examples

Size:

```text
list l cell1 2 4
x = vector_size(l)
```

```text
op add x __ls_lst_l_count 0
```

Indexed read with a branchless range guard:

```text
x = vector_at(l, i)
```

```text
op lessThan _0 i 0
op lessThanEq _1 __ls_lst_l_count i
op or _2 _0 _1
op add _3 2 i
op add _4 _3 1
op mul _2 _2 _4
op sub _3 _3 _2
read x cell1 _3
```

Append: the injected function returns the new count and the call site writes it back.

```text
x = vector_push_back(l, 7)
```

```text
funccall __ls_builtin_lstappend "cell1, 2, 4, __ls_lst_l_count, 7" __ls_lst_l_count
op add x __ls_lst_l_count 0
```

Set:

```text
x = vector_set(l, i, 5)
```

```text
funccall __ls_builtin_lstset "cell1, 2, __ls_lst_l_count, i, 5" x
```

Insert and remove use injected functions that shift elements right/left.

## Complexity

| Operation | Complexity | Notes |
| --- | --- | --- |
| vector_push_back / vector_at / vector_set / vector_size | O(1) | direct access or fixed guard |
| vector_insert / vector_erase | O(n) | elements shift |
| vector_find | O(n) | linear scan |

## Caveats

- Out of range: vector_at / vector_erase return NaN; vector_set / vector_insert return -1 and do not write.
- Full list: vector_push_back returns -1 and does not write; vector_insert returns -1 when count >= size.
- Compact storage: elements stay in [base, base+count). vector_erase shifts elements left and vector_insert shifts them right. Do not write past base+count yourself.
- State is not persisted: `__ls_lst_l_count` resets to 0 on reload; memory contents remain. An empty list starts safely. To clear, reset the count.
- Capacity: range [base, base+size); base + size beyond capacity is a compile error.
- Names must not collide with other structures; cross-module overlaps are not rejected automatically.

