# LogicSugar Advanced Data-Structure Tutorial

For players who already use LogicSugar if / for / while and Expr expressions, but are not sure which structure fits a given job.

Each chapter covers one structure: when to use it, the declaration card, a function table, the lowered mlog with line-by-line notes, complexity, and practical caveats. Examples assume you know how to place cards and switch between the Original and Sugar views; the Sugar source lines shown in the examples can also be pasted as text (a `x = expr` line imports as an Expr card).

> Chinese version: [../README.md](../README.md). Feature overview: [README.md](../../../README.md). Compiler and expression details: [architecture.md](../../architecture.md).

## Chapter map

| Structure | One-liner | Typical calls | Chapter |
| --- | --- | --- | --- |
| Array | random access to a contiguous memory range | `buf[i]`, `len(buf)` | [array.md](array.md) |
| Array bulk ops | whole-array queries and transforms | `array_sum` `array_fill` `array_sort` `array_lower_bound` | [array-bulk.md](array-bulk.md) |
| Matrix | row-major 2-D array | `m[i][j]` | [matrix.md](matrix.md) |
| Record | compile-time struct with named fields | `p.hp`, `p.hp = x` | [record.md](record.md) |
| Stack | last in, first out | `stack_push` `stack_pop` `stack_top` | [stack.md](stack.md) |
| Queue | first in, first out | `queue_push` `queue_pop` `queue_front` | [queue.md](queue.md) |
| Deque | ring buffer open at both ends | `deque_push_front` `deque_pop_back` `deque_front` | [deque.md](deque.md) |
| Bitset | compact array of booleans | `bitset_set` `bitset_reset` `bitset_test` `bitset_count` | [bitset.md](bitset.md) |
| Map | numeric key to value | `map_set` `map_get` | [map.md](map.md) |
| Unordered set | membership of numeric values | `set_add` `set_contains` `set_remove` | [uset.md](uset.md) |
| List | compact sequence with index insert/remove | `vector_push_back` `vector_at` `vector_erase` | [list.md](list.md) |
| Min-heap | priority queue that peels the minimum | `heap_push` `heap_pop` | [heap.md](heap.md) |
| Chain | free list plus node links | `chain_init` `chain_alloc` `chain_get` `chain_link` | [chain.md](chain.md) |

> Note: the editor menus and cards use the new names (e.g. `stack_push`); the old short names (e.g. `spush`) still parse in existing saves, and new cards are always written with the new names.

See the selection guide below if you are not sure what these structures are.

## Common concepts

### Declaration cards are compile-time metadata

The declaration cards array / matrix / record / stack / queue / deque / bitset / map / uset / list / heap / chain do not emit any mlog lines. They tell LogicSugar which name maps to which memory range and which compile-time facts hold. The product contains only vanilla instructions such as op / read / write / jump / funccall / sensor / end.

Consequences:

- Saved programs parse and run on any vanilla client, in single player and on multiplayer servers.
- Reopening a processor restores declaration cards from the Sugar carrier. Plain hand-written mlog without a carrier never grows data-structure cards.
- Names and ranges exist only at compile time and do not consume processor runtime memory.

### Memory blocks, addresses and capacity

The memory field names the memory block variable that holds the data. Capacity comes from the block the variable is **actually linked to** (name the block with the link tool); the name table is only a fallback for when no processor context is available:

| Name | Example | Guessed capacity (slots) | Note |
| --- | --- | --- | --- |
| cellN | cell1 | 64 | most common; a **world-cell is also linked as cellN but holds 512**, and the linked block wins |
| bankN | bank1 | 512 | large |
| worldN | world1 | 512 | world processor rules may apply; vanilla rarely produces this name |
| other | mem | not checked | the name carries no capacity, so the check is skipped |

base is the starting physical address. size / capacity / words / rows x cols is the occupied length. base + length beyond the capacity is a compile error. A resolved link always wins (so world-cell and modded memory blocks are judged by their real slot count); only when no link can be resolved at all does the guessed table above apply, and the error then says the capacity is `inferred from the variable name`.

All array/matrix/record/container memory accesses stay inside that range. Overlaps between different modules are not rejected automatically; see the caveats in each chapter.

### Names and reserved prefix

- Names must be unique inside one module. Cross-module checks are best effort, and cross-module overlaps/duplicates are a known limitation.
- A name must not collide with array / matrix / user functions.
- The __ls_ prefix is reserved for LogicSugar hidden state variables and injected functions.

### Two spellings in Expr mode

Most read operations have two equivalent spellings:

```text
intrinsic form:  stack_top(s)  vector_at(list, i)     map_get(m, 1)
getter sugar:    s.top()       list[i] / list.get(i)
```

Method/index sugar covers read-only getters, including injected-function backed `map.get` / `set.has` / `vector_find` / `bitset_count` / `chain_next` / `chain_len` (see the table below). Writes and push/pop/clear operations still use the intrinsic form.

### Hidden state variables

Stack, queue, deque, list, heap and chain keep runtime state in ordinary mlog variables with the __ls_ prefix:

```text
stack s  -> __ls_stk_s_top
queue q  -> __ls_que_q_head / _tail / _count
deque d  -> __ls_deq_d_head / _tail / _count
list l   -> __ls_lst_l_count
heap h   -> __ls_hep_h_count
chain c  -> __ls_chn_c_head / _free
```

They are hidden from the in-game variable list by default (the hide __ls_ internals setting) but remain visible in the Original view.

### State is not persisted across reloads

Hidden state variables are ordinary processor variables. Reloading the processor, a save round trip, or moving the program to another processor resets them to the unset value 0, while the memory block keeps its contents.

- stack / queue / deque / list / heap: starting from length 0 is safe. If old data remains in memory, call stack_clear / queue_clear / deque_clear first or reset the counter yourself.
- map / set / chain: the unset state is misread as valid data (slot 0 or node 0). Call map_clear(m) / set_clear(s) / chain_init(c) before first use.
- map / uset keep keys and values in memory, so they survive a reload, but they still require explicit initialization.

### Complexity and the instruction budget

The processor hard limit is 1000 instructions. Complexity decides whether a design fits:

| Complexity | Meaning | Examples |
| --- | --- | --- |
| O(1) | a few fixed instructions | `vector_at`, `stack_top`, `queue_push`, `bitset_test` |
| O(log n) | halving or tree height | `heap_push`, `heap_pop` |
| O(n) | scan all elements | `vector_find`, `vector_insert`, `vector_erase`, `chain_len` |
| O(n^1.5) ~ O(n^2) | Shell sort | `array_sort` / `array_sort_desc` |
| O(capacity) | scan the whole table or range | `map_size`, `map_clear`, `set_size`, `set_clear`, `chain_init` |

> A **hit** on `map_get` / `map_contains` / `set_add` / `set_contains` averages close to O(1); a **miss or a new-key insert is always Theta(capacity)** because probing cannot stop at an empty slot.

> Looping algorithms (hash probing, sorting, heap sift, chain traversal) become one shared __ls_builtin_* subroutine in normal mode. Inline mode copies the body at every call site, so watch the 1000-instruction limit in large programs.

### Method and index sugar

| Structure | Sugar | Equivalent |
| --- | --- | --- |
| list | `l[i]`, `l.get(i)` | `vector_at(l, i)` |
| list | `l.find(v)` / `l.indexOf(v)` | `vector_find(l, v)` |
| list / heap | `l.size()` / `l.length()` / `l.count()`, `h.size()` | `vector_size(l)` / `heap_size(h)` |
| stack | `s.top()`, `s.peek()`, `s.size()` / `s.count()` | `stack_top(s)` / `stack_size(s)` |
| queue | `q.front()`, `q.peek()`, `q.size()` / `q.count()` | `queue_front(q)` / `queue_size(q)` |
| deque | `d.front()`, `d.back()`, `d.size()` / `d.count()` | `deque_front(d)` / `deque_back(d)` / `deque_size(d)` |
| bitset | `b[i]`, `b.test(i)`, `b.get(i)`, `b.count()` | `bitset_test(b, i)` / `bitset_count(b)` |
| chain | `c[i]`, `c.get(i)`, `c.head()`, `c.next(i)`, `c.len()` | `chain_get(c, i)` / `chain_head(c)` / `chain_next(c, i)` / `chain_len(c)` |
| map | `m[k]`, `m.get(k)`, `m.has(k)`, `m.size()` | `map_get(m, k)` / `map_contains(m, k)` / `map_size(m)` |
| uset | `s.has(v)`, `s.size()` | `set_contains(s, v)` / `set_size(s)` |

Notes:

- If an array or matrix shares the name, `x[i]` is resolved as an array first.
- Index sugar is read-only: `l[i] = v` is a compile error. Use `vector_set(l, i, v)` / `bitset_set(b, i)` / `chain_set(c, i, v)`.
- The map / uset method sugar receiver must match the declaration card name; for `uset s ...` write `s.has(v)`.

## Selection guide

| You want | Use |
| --- | --- |
| random access to numeric slots | [array.md](array.md) |
| whole-array sum, sort, search | [array-bulk.md](array-bulk.md) |
| a 2-D table (grid, map, matrix math) | [matrix.md](matrix.md) |
| to pack related values into an object | [record.md](record.md) |
| LIFO (undo, DFS, bracket matching) | [stack.md](stack.md) |
| FIFO (task queue, BFS) | [queue.md](queue.md) |
| push/pop at both ends (sliding window) | [deque.md](deque.md) |
| many flags or visited marks | [bitset.md](bitset.md) |
| numeric key lookup (counters, maps) | [map.md](map.md) |
| de-duplication or membership | [uset.md](uset.md) |
| a sequence with middle insert/remove | [list.md](list.md) |
| repeatedly peel the minimum | [heap.md](heap.md) |
| manual nodes and links (adjacency, LRU) | [chain.md](chain.md) |

## Verifying the lowering yourself

1. Place the declaration card and the operation card (or write an Expr expression).
2. Save once, switch to the Original view, and compare the instructions with the chapter example.
3. To prove that no mod is needed, use the copy compiled code button and import the resulting vanilla mlog in a normal client.

## Roadmap

- Method sugar now covers builtin-backed `map_get` / `set_contains` / `vector_find` / `bitset_count` / `chain_next` / `chain_len` (declaration pre-scan during analyze plus reachability registration). Mutator method sugar (`s.push()`, `l.append()`, `m.set()`) is not provided; keep using the function forms.
- `buf[i] = x` is already supported. Indexed assignment for list / bitset / chain is not.

## Related docs

- [Architecture overview](../../architecture.md): compiler, expression subsystem, data subsystem internals.
- [Glossary](../../glossary.md): intrinsic, injected function, carrier, hidden state variable.
- [Testing guide](../../testing.md): self-test tasks and manual checklist per structure.
- [Chinese tutorial](../README.md).

