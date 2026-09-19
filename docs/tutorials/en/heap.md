# Min-heap (heap)

> [Tutorial index](README.md) | Previous: [List](list.md) | Next: [Chain](chain.md) | Chinese: [../heap.md](../heap.md)

## When to use

Repeatedly peel the minimum: Dijkstra, merging sorted streams, priority queues, top-K smallest.

## Declaration card

```text
heap <name> <memory> <base> <size>
```

Example: `heap h cell2 4 8` uses cell2 addresses 4..11 and holds at most 8 elements.

The min-heap is array-backed. State is the hidden variable `__ls_hep_h_count`. The unset value is 0, so it starts empty safely.

## Function table

| Function | Arguments | Returns | Notes |
| --- | --- | --- | --- |
| `heap_push(h, v)` | heap, value | 1 / -1 | full returns -1 and does not write |
| `heap_pop(h)` | heap | minimum or NaN | empty returns NaN; removes the minimum |
| `heap_size(h)` | heap | element count | O(1) |

> Note: the editor menus and cards use the new names (e.g. `heap_push`); the old short names (e.g. `hpush`) still parse in existing saves, and new cards are always written with the new names.

Sugar: `h.size()` / `h.length()` / `h.count()` equal `heap_size(h)`.

There is no non-destructive peek. To keep the minimum, push it back after popping (O(log n)).

## Lowered examples

Size:

```text
heap h cell2 4 8
x = heap_size(h)
```

```text
op add x __ls_hep_h_count 0
```

Push:

```text
x = heap_push(h, 5)
```

```text
op add _0 __ls_hep_h_count 0
funccall __ls_builtin_heppush "cell2, 4, 8, __ls_hep_h_count, 5" __ls_hep_h_count
op notEqual _1 __ls_hep_h_count _0
op add x _1 0
```

The injected function returns the new count; the call site converts did-count-change into 1/0.

Pop:

```text
x = heap_pop(h)
```

```text
op add _0 __ls_hep_h_count 0
op greaterThan _1 _0 0
op sub __ls_hep_h_count _0 _1
funccall __ls_builtin_heppop "cell2, 4, 8, _0" x
```

The count is decremented before the call (only when non-empty); the function returns the minimum, or NaN on an empty heap.

## Complexity

| Operation | Complexity | Notes |
| --- | --- | --- |
| heap_push / heap_pop | O(log n) | tree height is log n |
| heap_size | O(1) | reads the count variable |

## Caveats

- Min-heap: heap_pop returns the current minimum. To get the maximum, store negated values.
- No peek: heap_pop is destructive; heap_size only reports the count.
- Empty/full: heap_pop returns NaN when empty; heap_push returns -1 and does not write when full.
- State is not persisted: `__ls_hep_h_count` resets to 0 on reload, so old memory elements are not treated as live. Starting empty is safe; reset the count to clear.
- Capacity: range [base, base+size); base + size beyond capacity is a compile error.
- Duplicates are allowed; heap_pop returns one of the equal minima, order not guaranteed.

