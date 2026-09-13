# Deque (deque)

> [Tutorial index](README.md) | Previous: [Queue](queue.md) | Next: [Bitset](bitset.md) | Chinese: [../deque.md](../deque.md)

## When to use

Push and pop at both ends: sliding windows, monotonic queues, 0-1 BFS, task queues that need front insertion.

## Declaration card

```text
deque <name> <memory> <base> <size>
```

Example: `deque d cell2 0 4` uses cell2 addresses 0..3. State:

```text
__ls_deq_d_head    physical head index
__ls_deq_d_tail    physical tail index (next write position)
__ls_deq_d_count   current element count
```

Invariant: tail == (head + count) % size.

## Function table

| Function | Arguments | Returns | Notes |
| --- | --- | --- | --- |
| `dpushf(d, v)` | deque, value | new size | front push; full returns current size and does not write |
| `dpushb(d, v)` | deque, value | new size | back push; full returns current size and does not write |
| `dpopf(d)` | deque | front value | empty: NaN |
| `dpopb(d)` | deque | back value | empty: NaN |
| `dpeekf(d)` | deque | front value | empty: NaN |
| `dpeekb(d)` | deque | back value | empty: NaN |
| `dsize(d)` | deque | element count | O(1) |
| `dclear(d)` | deque | 0 | clears head/tail/count |

Sugar: `d.front()` / `d.peekFront()` equal `dpeekf(d)`; `d.back()` / `d.peekBack()` equal `dpeekb(d)`; `d.size()` / `d.count()` equal `dsize(d)`.

## Lowered examples

Size:

```text
op add x __ls_deq_d_count 0
```

Back peek computes (head + count + size - 1) % size:

```text
op lessThanEq _0 __ls_deq_d_count 0
op add _3 __ls_deq_d_head __ls_deq_d_count
op add _3 _3 4
op sub _3 _3 1
op mod _3 _3 4
op add _1 0 _3
op add _2 _1 1
op mul _0 _0 _2
op sub _1 _1 _0
read x cell2 _1
```

Back push reuses the queue push builtin:

```text
funccall __ls_builtin_quepush "cell2, 0, 4, __ls_deq_d_head, __ls_deq_d_count, 7" __ls_deq_d_count
op add __ls_deq_d_tail __ls_deq_d_head __ls_deq_d_count
op mod __ls_deq_d_tail __ls_deq_d_tail 4
op add x __ls_deq_d_count 0
```

Front push moves the head first:

```text
funccall __ls_builtin_deqpushf "cell2, 0, 4, __ls_deq_d_head, __ls_deq_d_count, 7" __ls_deq_d_head
op add _0 __ls_deq_d_count 1
op min __ls_deq_d_count _0 4
op add __ls_deq_d_tail __ls_deq_d_head __ls_deq_d_count
op mod __ls_deq_d_tail __ls_deq_d_tail 4
op add x __ls_deq_d_count 0
```

## Complexity

| Operation | Complexity |
| --- | --- |
| dpushf / dpushb / dpopf / dpopb / dpeekf / dpeekb / dsize / dclear | O(1) |

## Caveats

- Empty deque: pop/peek return NaN and leave the state unchanged.
- Full deque: push returns the current size and does not write.
- f means front (head), b means back (tail). Back index is (head + count + size - 1) % size.
- State is not persisted: the three hidden variables reset on reload. Call dclear(d) if old data should be discarded.
- Capacity: range [base, base+size); base + size beyond capacity is a compile error.
- Queues and deques can share a memory block when ranges do not overlap, but cross-module overlaps are not rejected automatically.

