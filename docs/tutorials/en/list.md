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
| `lappend(l, v)` | list, value | new count | full: returns current count and does not write |
| `lget(l, i)` | list, index | value or NaN | NaN when out of range |
| `lset(l, i, v)` | list, index, value | 1 / 0 | out of range does not write |
| `linsert(l, i, v)` | list, index, value | 1 / 0 | shift right; full/out of range returns 0 |
| `lremove(l, i)` | list, index | removed value or NaN | shift left; out of range returns NaN |
| `lfind(l, v)` | list, value | index or -1 | first match |
| `lsize(l)` | list | element count | O(1) |

Sugar: `l[i]` / `l.get(i)` equal `lget(l, i)`; `l.find(v)` / `l.indexOf(v)` equal `lfind(l, v)`; `l.size()` / `l.length()` / `l.count()` equal `lsize(l)`.

## Lowered examples

Size:

```text
list l cell1 2 4
x = lsize(l)
```

```text
op add x __ls_lst_l_count 0
```

Indexed read with a branchless range guard:

```text
x = lget(l, i)
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
x = lappend(l, 7)
```

```text
funccall __ls_builtin_lstappend "cell1, 2, 4, __ls_lst_l_count, 7" __ls_lst_l_count
op add x __ls_lst_l_count 0
```

Set:

```text
x = lset(l, i, 5)
```

```text
funccall __ls_builtin_lstset "cell1, 2, __ls_lst_l_count, i, 5" x
```

Insert and remove use injected functions that shift elements right/left.

## Complexity

| Operation | Complexity | Notes |
| --- | --- | --- |
| lappend / lget / lset / lsize | O(1) | direct access or fixed guard |
| linsert / lremove | O(n) | elements shift |
| lfind | O(n) | linear scan |

## Caveats

- Out of range: lget / lremove return NaN; lset / linsert return 0 and do not write.
- Full list: lappend returns the current size and does not write; linsert returns 0 when count >= size.
- Compact storage: elements stay in [base, base+count). lremove shifts elements left and linsert shifts them right. Do not write past base+count yourself.
- State is not persisted: `__ls_lst_l_count` resets to 0 on reload; memory contents remain. An empty list starts safely. To clear, reset the count.
- Capacity: range [base, base+size); base + size beyond capacity is a compile error.
- Names must not collide with other structures; cross-module overlaps are not rejected automatically.

