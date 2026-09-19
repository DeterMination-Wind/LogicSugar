# Chain (chain)

> [Tutorial index](README.md) | Previous: [Min-heap](heap.md) | Chinese: [../chain.md](../chain.md)

## When to use

Manual nodes and links: adjacency lists, LRU, free node pools, graph structures. A chain gives node layout (value slot plus next slot) and a free list; you link nodes yourself with chain_link / chain_set_head.

## Declaration card

```text
chain <name> <memory> <base> <size>
```

Node i uses two consecutive slots:

```text
value slot: base + 2*i
next slot:  base + 2*i + 1      next = -1 means end of list
range:      [base, base + 2*size)
```

Hidden state:

```text
__ls_chn_c_head   head node index, -1 means empty
__ls_chn_c_free   free list head, -1 means no free node
```

Call chain_init(c) or chain_clear(c) before first use. Unset variables read as 0, so chain_alloc without initialization would treat node 0 as free.

## Function table

| Function | Arguments | Returns | Notes |
| --- | --- | --- | --- |
| `chain_init(c)` / `chain_clear(c)` | chain | size sentinel | head = -1; free list becomes 0->1->...->size-1->-1 |
| `chain_alloc(c)` | chain | node index or -1 | pops a free node |
| `chain_free(c, i)` | chain, index | 1 / 0 | unlinks node i and pushes it back to the free list |
| `chain_get(c, i)` | chain, index | node value or NaN | NaN when out of range |
| `chain_set(c, i, v)` | chain, index, value | 1 / 0 | out of range does not write |
| `chain_next(c, i)` | chain, index | next or -1 | -1 when out of range |
| `chain_link(c, i, next)` | chain, index, next | 1 / 0 | sets i.next; next can be -1 |
| `chain_set_head(c, i)` | chain, index | 1 | sets the head; any value is accepted |
| `chain_head(c)` | chain | head index or -1 | reads the hidden head |
| `chain_len(c)` | chain | node count | walks from the head; 0 for an empty chain |

> Note: the editor menus and cards use the new names (e.g. `chain_init`); the old short names (e.g. `cinit`) still parse in existing saves, and new cards are always written with the new names.

Sugar: `c[i]` / `c.get(i)` equal `chain_get(c, i)`; `c.head()` equals `chain_head(c)`; `c.next(i)` equals `chain_next(c, i)`; `c.len()` / `c.length()` equals `chain_len(c)`.

## Lowered examples

Initialize:

```text
chain c cell1 2 4
x = chain_init(c)
```

```text
op sub __ls_chn_c_head 0 1
op add __ls_chn_c_free 0 0
funccall __ls_builtin_chninit "cell1, 2, 4" x
```

Head:

```text
x = chain_head(c)
```

```text
op add x __ls_chn_c_head 0
```

Allocate a node:

```text
x = chain_alloc(c)
```

```text
op add _1 __ls_chn_c_free 0
funccall __ls_builtin_chnnew "cell1, 2, __ls_chn_c_free" __ls_chn_c_free
op add x _1 0
```

Read a node value (guarded):

```text
x = chain_get(c, i)
```

```text
op lessThan _1 i 0
op greaterThanEq _2 i 4
op or _3 _1 _2
op mul _4 i 2
op add _4 2 _4
op add _5 _4 1
op mul _3 _3 _5
op sub _4 _4 _3
read x cell1 _4
```

Other operations:

```text
x = chain_set(c, i, 5)     -> funccall __ls_builtin_chnset  "cell1, 2, 4, i, 5" x
x = chain_next(c, i)       -> funccall __ls_builtin_chnnext "cell1, 2, 4, i" x
x = chain_link(c, i, j)    -> funccall __ls_builtin_chnlink "cell1, 2, 4, i, j" x
x = chain_len(c)           -> funccall __ls_builtin_chnlen  "cell1, 2, __ls_chn_c_head" x
```

## Complexity

| Operation | Complexity | Notes |
| --- | --- | --- |
| chain_init / chain_clear | O(size) | rebuilds the free list |
| chain_alloc | O(1) | pops the free list head |
| chain_free | O(n) | finds the predecessor of i in the next chain |
| chain_get / chain_set / chain_next / chain_set_head / chain_head / chain_link | O(1) | direct read/write |
| chain_len | O(n) | walks from the head |

## Caveats

- Initialize first: call chain_init(c) / chain_clear(c) once before use. After a processor reload the hidden variables reset to 0, so initialize again.
- chain_get / chain_set are index-based and do not follow the links. To walk the logical list, use chain_head plus chain_next.
- chain_link does not detect cycles. Linking a node already in the list creates a cycle; chain_len / chain_free will not terminate. The list invariants are your responsibility.
- chain_free does not detect double frees. Freeing a node that is already on the free list creates a free-list cycle.
- Nodes and free list: chain_alloc allocates, chain_free recycles. A freshly allocated node has next = -1; link it with chain_link or chain_set_head.
- Capacity: range [base, base+2*size); base + 2*size beyond capacity is a compile error.
- Names must not collide with other chains, arrays/matrices, user functions or other declaration cards; cross-module overlaps are a known limitation.

