# Array bulk operations

> [Tutorial index](README.md) | Previous: [Array](array.md) | Next: [Matrix](matrix.md) | Chinese: [../array-bulk.md](../array-bulk.md)

## When to use

Process a whole array at once: sum, average, min/max, count, find, fill, copy, sort, reverse, replace, swap, binary search.

These functions reuse the array / matrix declaration cards. The first argument must be a declared array name. Matrices are flattened in row-major order.

## Function table

| Function | Arguments | Returns | Notes |
| --- | --- | --- | --- |
| `sum(buf)` | array | total | adds every element |
| `avg(buf)` | array | average | sum / size |
| `min(buf)` | array | minimum | 1-arg min is the array op |
| `max(buf)` | array | maximum | 1-arg max is the array op |
| `count(buf, v)` | array, value | occurrences | 0 when none |
| `indexof(buf, v)` | array, value | first index | -1 when none |
| `fill(buf, v)` | array, value | implementation sentinel | writes v to the whole range |
| `copy(dst, src)` | target, source | implementation sentinel | sizes must match |
| `sortasc(buf)` | array | implementation sentinel | in-place ascending |
| `sortdesc(buf)` | array | implementation sentinel | in-place descending |
| `reverse(buf)` | array | implementation sentinel | in-place reverse |
| `replace(buf, old, neu)` | array, old, new | replacement count | replaces equals to old |
| `swap(buf, i, j)` | array, i, j | implementation sentinel | swaps two indices |
| `bsearch(buf, v)` | array, value | index or -1 | requires ascending order |

Notes:

- `min(a, b)` / `max(a, b)` with two arguments are vanilla math built-ins, not array operations.
- `fill` / `copy` / `sortasc` / `sortdesc` / `reverse` / `swap` are statement-like. Their expression result is an implementation sentinel; do not read it.

## Lowered examples

Every bulk operation becomes a funccall to an injected `__ls_builtin_arr*` function. The shared body appears once per program in normal mode.

```text
array buf cell1 0 8
x = sum(buf)
```

```text
funccall __ls_builtin_arrsum "cell1, 0, 8" x
```

More examples:

```text
x = count(buf, 3)   -> funccall __ls_builtin_arrcount   "cell1, 0, 8, 3" x
x = indexof(buf, i) -> funccall __ls_builtin_arrindexof "cell1, 0, 8, i" x
fill(buf, 5)        -> funccall __ls_builtin_arrfill    "cell1, 0, 8, 5" x
sortasc(buf)        -> funccall __ls_builtin_arrsort    "cell1, 0, 8, 1" x
sortdesc(buf)       -> funccall __ls_builtin_arrsort    "cell1, 0, 8, -1" x
reverse(buf)        -> funccall __ls_builtin_arrrev     "cell1, 0, 8" x
replace(buf, 1, 9)  -> funccall __ls_builtin_arrrepl    "cell1, 0, 8, 1, 9" x
swap(buf, i, j)     -> funccall __ls_builtin_arrswap    "cell1, 0, i, j" x
bsearch(buf, 3)     -> funccall __ls_builtin_arrbsearch "cell1, 0, 8, 3" x
```

Matrix flattening:

```text
matrix m cell1 0 2 3
x = sum(m)
```

```text
funccall __ls_builtin_arrsum "cell1, 0, 6" x
```

`copy(a, b)` requires equal lengths. Matrices compare by flattened element count.

## Complexity

| Operation | Complexity | Note |
| --- | --- | --- |
| sum / avg / min / max | O(n) | one pass |
| count / indexof / replace | O(n) | one pass |
| fill / copy / reverse | O(n) | one operation per element |
| sortasc / sortdesc | O(n^2) | insertion sort; watch the instruction budget for large arrays |
| swap | O(1) | four fixed reads/writes |
| bsearch | O(log n) | requires ascending order; miss returns -1 |

## Caveats

- Arguments must be declared array names; expressions or other structures are compile errors.
- copy requires equal lengths (matrices compare by rows x cols).
- sortasc / sortdesc are in-place insertion sort. They are fine for small arrays and slow for hundreds of elements.
- bsearch is only correct on ascending arrays. Sort descending arrays with sortasc first.
- fill / copy / sortasc / sortdesc / reverse / swap have no meaningful expression result.
- bsearch / swap do not add runtime bounds checks; swap indices must be valid.
- Capacity and range rules are the same as for arrays; see [array.md](array.md).

