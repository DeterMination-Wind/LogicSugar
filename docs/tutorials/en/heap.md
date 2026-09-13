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
| `hpush(h, v)` | heap, value | 1 / 0 | full returns 0 and does not write |
| `hpop(h)` | heap | minimum or NaN | empty returns NaN; removes the minimum |
| `hsize(h)` | heap | element count | O(1) |

Sugar: `h.size()` / `h.length()` / `h.count()` equal `hsize(h)`.

There is no non-destructive peek. To keep the minimum, push it back after popping (O(log n)).

## Lowered examples

Size:

```text
heap h cell2 4 8
x = hsize(h)
```

```text
op add x __ls_hep_h_count 0
```

Push:

```text
x = hpush(h, 5)
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
x = hpop(h)
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
| hpush / hpop | O(log n) | tree height is log n |
| hsize | O(1) | reads the count variable |

## Caveats

- Min-heap: hpop returns the current minimum. To get the maximum, store negated values.
- No peek: hpop is destructive; hsize only reports the count.
- Empty/full: hpop returns NaN when empty; hpush returns 0 and does not write when full.
- State is not persisted: `__ls_hep_h_count` resets to 0 on reload, so old memory elements are not treated as live. Starting empty is safe; reset the count to clear.
- Capacity: range [base, base+size); base + size beyond capacity is a compile error.
- Duplicates are allowed; hpop returns one of the equal minima, order not guaranteed.

