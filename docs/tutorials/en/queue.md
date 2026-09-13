# Queue (queue)

> [Tutorial index](README.md) | Previous: [Stack](stack.md) | Next: [Deque](deque.md) | Chinese: [../queue.md](../queue.md)

## When to use

FIFO: BFS, task scheduling, ordered events, pipeline buffers.

## Declaration card

```text
queue <name> <memory> <base> <size>
```

Example: `queue q cell2 0 4` uses cell2 addresses 0..3. The queue is a ring buffer with hidden state:

```text
__ls_que_q_head    physical head index
__ls_que_q_tail    physical tail index (next write position)
__ls_que_q_count   current element count
```

Invariant: tail == (head + count) % size.

## Function table

| Function | Arguments | Returns | Notes |
| --- | --- | --- | --- |
| `qpush(q, v)` | queue, value | new size | full: returns current size and does not write |
| `qpop(q)` | queue | head value | empty: NaN; removes it |
| `qpeek(q)` | queue | head value | empty: NaN; does not remove |
| `qsize(q)` | queue | element count | O(1) |
| `qclear(q)` | queue | 0 | clears head/tail/count |

Sugar: `q.front()` / `q.peek()` equal `qpeek(q)`; `q.size()` / `q.count()` equal `qsize(q)`.

## Lowered examples

Size:

```text
op add x __ls_que_q_count 0
```

Peek:

```text
op lessThanEq _0 __ls_que_q_count 0
op add _1 0 __ls_que_q_head
op add _2 _1 1
op mul _0 _0 _2
op sub _1 _1 _0
read x cell2 _1
```

Pop updates head and count after the same guarded read:

```text
op min _3 __ls_que_q_count 1
op add _4 __ls_que_q_head _3
op mod __ls_que_q_head _4 4
op sub __ls_que_q_count __ls_que_q_count 1
op max __ls_que_q_count __ls_que_q_count 0
```

Push:

```text
funccall __ls_builtin_quepush "cell2, 0, 4, __ls_que_q_head, __ls_que_q_count, 7" __ls_que_q_count
op add __ls_que_q_tail __ls_que_q_head __ls_que_q_count
op mod __ls_que_q_tail __ls_que_q_tail 4
op add x __ls_que_q_count 0
```

## Complexity

| Operation | Complexity |
| --- | --- |
| qpush / qpop / qpeek / qsize / qclear | O(1) |

## Caveats

- Empty queue: qpop / qpeek return NaN and leave head/count unchanged.
- Full queue: qpush returns the current size and does not write.
- head and tail wrap with % size; the tail variable is a cache of (head + count) % size.
- State is not persisted: head/tail/count reset to 0 on reload. Starting empty is safe; call qclear(q) if old data should be discarded.
- Capacity: base + size beyond the block capacity is a compile error; the range is [base, base+size).
- Overlaps with other structures are not rejected automatically.

