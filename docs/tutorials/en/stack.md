# Stack (stack)

> [Tutorial index](README.md) | Previous: [Record](record.md) | Next: [Queue](queue.md) | Chinese: [../stack.md](../stack.md)

## When to use

LIFO: undo, depth-first search, bracket matching, temporary storage.

## Declaration card

```text
stack <name> <memory> <base> <size>
```

Example: `stack s cell1 0 8` uses cell1 addresses 0..7. The card emits no mlog. State is the hidden variable `__ls_stk_s_top` (element count).

## Function table

| Function | Arguments | Returns | Notes |
| --- | --- | --- | --- |
| `spush(s, v)` | stack, value | new size | full: returns current size and does not write |
| `spop(s)` | stack | top value | empty: NaN; removes the value |
| `speek(s)` | stack | top value | empty: NaN; does not remove |
| `ssize(s)` | stack | element count | O(1) |
| `sclear(s)` | stack | 0 | sets top to 0 |

Sugar: `s.top()` / `s.peek()` equal `speek(s)`; `s.size()` / `s.count()` equal `ssize(s)`.

## Lowered examples

Size:

```text
stack s cell1 0 8
x = ssize(s)
```

```text
op add x __ls_stk_s_top 0
```

Peek with branchless empty guard:

```text
x = speek(s)
```

```text
op sub _0 __ls_stk_s_top 1
op add _1 0 _0
op lessThanEq _2 __ls_stk_s_top 0
op mul _2 _2 0
op sub _1 _1 _2
read x cell1 _1
```

When top <= 0 the guard folds the address to -1, and the out-of-range read returns NaN. A non-zero base multiplies the guard by base to pull base-1 back to -1.

Push:

```text
x = spush(s, 5)
```

```text
funccall __ls_builtin_stkpush "cell1, 0, 8, __ls_stk_s_top, 5" __ls_stk_s_top
op add x __ls_stk_s_top 0
```

The memory write and full check live in the shared injected function `__ls_builtin_stkpush`.

## Complexity

| Operation | Complexity |
| --- | --- |
| spush / spop / speek / ssize / sclear | O(1) |

## Caveats

- Empty stack: spop / speek return NaN. spop keeps top at max(top-1, 0).
- Full stack: spush returns the current size and does not write; it never overwrites existing elements.
- State is not persisted: `__ls_stk_s_top` resets to 0 on reload. Starting empty is safe; call sclear(s) if old memory data should be discarded.
- Capacity: base + size beyond the block capacity is a compile error; the range is [base, base+size).
- Overlaps with other structures in the same memory block are not rejected automatically.
- The name must not collide with other structures or user functions; the __ls_ prefix is reserved.

