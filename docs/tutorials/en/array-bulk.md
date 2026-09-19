# Array bulk operations

> [Tutorial index](README.md) | Previous: [Array](array.md) | Next: [Matrix](matrix.md) | Chinese: [../array-bulk.md](../array-bulk.md)

## When to use

Process a whole array at once: sum, average, min/max, count, find, fill, copy, sort, reverse, replace, swap, binary search.

These functions reuse the array / matrix declaration cards. The first argument must be a declared array name. Matrices are flattened in row-major order.

## Function table

| Function | Arguments | Returns | Notes |
| --- | --- | --- | --- |
| `array_sum(buf)` | array | total | adds every element |
| `array_avg(buf)` | array | average | array_sum / size |
| `array_min(buf)` | array | minimum | 1-arg array_min is the array op |
| `array_max(buf)` | array | maximum | 1-arg array_max is the array op |
| `array_count(buf, v)` | array, value | occurrences | 0 when none |
| `array_find(buf, v)` | array, value | first index | -1 when none |
| `array_fill(buf, v)` | array, value | implementation sentinel | writes v to the whole range |
| `array_copy(dst, src)` | target, source | implementation sentinel | sizes must match |
| `array_sort(buf)` | array | implementation sentinel | in-place ascending |
| `array_sort_desc(buf)` | array | implementation sentinel | in-place descending |
| `array_reverse(buf)` | array | implementation sentinel | in-place reverse |
| `array_replace(buf, old, neu)` | array, old, new | replacement count | replaces equals to old |
| `array_swap(buf, i, j)` | array, i, j | implementation sentinel | swaps two indices |
| `array_lower_bound(buf, v)` | array, value | index or -1 | requires ascending order |

> Note: the editor menus and cards use the new names (e.g. `array_sum`); the old short names (e.g. `sum`) still parse in existing saves, and new cards are always written with the new names.

Notes:

- `min(a, b)` / `max(a, b)` with two arguments are vanilla math built-ins, not array operations.
- `array_fill` / `array_copy` / `array_sort` / `array_sort_desc` / `array_reverse` / `array_swap` are statement-like. Their expression result is an implementation sentinel; do not read it.

## Lowered examples

Every bulk operation becomes a funccall to an injected `__ls_builtin_arr*` function. The shared body appears once per program in normal mode.

```text
array buf cell1 0 8
x = array_sum(buf)
```

```text
funccall __ls_builtin_arrsum "cell1, 0, 8" x
```

More examples:

```text
x = array_count(buf, 3)      -> funccall __ls_builtin_arrcount   "cell1, 0, 8, 3" x
x = array_find(buf, i)       -> funccall __ls_builtin_arrindexof "cell1, 0, 8, i" x
array_fill(buf, 5)           -> funccall __ls_builtin_arrfill    "cell1, 0, 8, 5" x
array_sort(buf)              -> funccall __ls_builtin_arrsort    "cell1, 0, 8, 1" x
array_sort_desc(buf)         -> funccall __ls_builtin_arrsort    "cell1, 0, 8, -1" x
array_reverse(buf)           -> funccall __ls_builtin_arrrev     "cell1, 0, 8" x
array_replace(buf, 1, 9)     -> funccall __ls_builtin_arrrepl    "cell1, 0, 8, 1, 9" x
array_swap(buf, i, j)        -> funccall __ls_builtin_arrswap    "cell1, 0, i, j" x
array_lower_bound(buf, 3)    -> funccall __ls_builtin_arrbsearch "cell1, 0, 8, 3" x
```

Matrix flattening:

```text
matrix m cell1 0 2 3
x = array_sum(m)
```

```text
funccall __ls_builtin_arrsum "cell1, 0, 6" x
```

`array_copy(a, b)` requires equal lengths. Matrices compare by flattened element count.

## Complexity

| Operation | Complexity | Note |
| --- | --- | --- |
| array_sum / array_avg / array_min / array_max | O(n) | one pass |
| array_count / array_replace | O(n) | one pass |
| array_find | O(n) worst; stops at the hit | stops reading after the first match |
| array_fill / array_copy / array_reverse | O(n) | one operation per element |
| array_sort / array_sort_desc | O(n^1.5) ~ O(n^2) | Shell sort (gaps `size/2, size/4, ..., 1`); watch the instruction budget for large arrays |
| array_swap | O(1) | four fixed reads/writes |
| array_lower_bound | O(log n) | requires ascending order; miss returns -1 |

## Caveats

- Arguments must be declared array names; expressions or other structures are compile errors.
- array_copy requires equal lengths (matrices compare by rows x cols).
- array_sort / array_sort_desc are an in-place Shell sort: each pass insertion-sorts the subsequences at gap `size/2, size/4, ..., 1`, so the last pass is a plain insertion sort. It is in-place, needs no scratch memory, and the shared subroutine is about 33 instructions. It is far faster than the previous insertion sort on random/reversed data, but hundreds of elements are still slow (a processor executes a fixed number of instructions per tick).
- Compatibility break (v5.0.0 to the next version): the sort builtin's instruction sequence changed. Processors saved by an older version that used `sortasc` / `sortdesc` will fail carrier verification on reopen and fall back to the vanilla view — the `array` declaration card and the sort card show up as raw mlog instructions. Drop the sort card again to recover. This is an unavoidable consequence of the decompiler gate comparing instruction streams one by one, not a bug.
- Compatibility break (v5.1.1 to the next version): the `indexof` builtin gained a `jump` so it stops at the first hit, and `copy` had its read/write bases corrected (same-block copy no longer reverses, cross-block copy no longer writes the wrong region). Processors saved by v5.1.1 or earlier that used either builtin will also fail carrier verification on reopen and fall back to the vanilla view; drop the corresponding array-operation card again to recover. This is the necessary cost of the correctness fix.
- array_lower_bound is only correct on ascending arrays. Sort descending arrays with array_sort first.
- array_fill / array_copy / array_sort / array_sort_desc / array_reverse / array_swap have no meaningful expression result.
- array_lower_bound / array_swap do not add runtime bounds checks; array_swap indices must be valid.
- Capacity and range rules are the same as for arrays; see [array.md](array.md).

