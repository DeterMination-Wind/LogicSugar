# Chain (chain)

> [Tutorial index](README.md) | Previous: [Min-heap](heap.md) | Chinese: [../chain.md](../chain.md)

## When to use

Manual nodes and links: adjacency lists, LRU, free node pools, graph structures. A chain gives node layout (value slot plus next slot) and a free list; you link nodes yourself with clink / cshead.

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

Call cinit(c) or cclear(c) before first use. Unset variables read as 0, so cnew without initialization would treat node 0 as free.

## Function table

| Function | Arguments | Returns | Notes |
| --- | --- | --- | --- |
| `cinit(c)` / `cclear(c)` | chain | size sentinel | head = -1; free list becomes 0->1->...->size-1->-1 |
| `cnew(c)` | chain | node index or -1 | pops a free node |
| `cfree(c, i)` | chain, index | 1 / 0 | unlinks node i and pushes it back to the free list |
| `cget(c, i)` | chain, index | node value or NaN | NaN when out of range |
| `cset(c, i, v)` | chain, index, value | 1 / 0 | out of range does not write |
| `cnext(c, i)` | chain, index | next or -1 | -1 when out of range |
| `clink(c, i, next)` | chain, index, next | 1 / 0 | sets i.next; next can be -1 |
| `cshead(c, i)` | chain, index | 1 | sets the head; any value is accepted |
| `chead(c)` | chain | head index or -1 | reads the hidden head |
| `clen(c)` | chain | node count | walks from the head; 0 for an empty chain |

Sugar: `c[i]` / `c.get(i)` equal `cget(c, i)`; `c.head()` equals `chead(c)`; `c.next(i)` equals `cnext(c, i)`; `c.len()` / `c.length()` equals `clen(c)`.

## Lowered examples

Initialize:

```text
chain c cell1 2 4
x = cinit(c)
```

```text
op sub __ls_chn_c_head 0 1
op add __ls_chn_c_free 0 0
funccall __ls_builtin_chninit "cell1, 2, 4" x
```

Head:

```text
x = chead(c)
```

```text
op add x __ls_chn_c_head 0
```

Allocate a node:

```text
x = cnew(c)
```

```text
op add _1 __ls_chn_c_free 0
funccall __ls_builtin_chnnew "cell1, 2, __ls_chn_c_free" __ls_chn_c_free
op add x _1 0
```

Read a node value (guarded):

```text
x = cget(c, i)
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
x = cset(c, i, 5)  -> funccall __ls_builtin_chnset  "cell1, 2, 4, i, 5" x
x = cnext(c, i)    -> funccall __ls_builtin_chnnext "cell1, 2, 4, i" x
x = clink(c, i, j) -> funccall __ls_builtin_chnlink "cell1, 2, 4, i, j" x
x = clen(c)        -> funccall __ls_builtin_chnlen  "cell1, 2, __ls_chn_c_head" x
```

## Complexity

| Operation | Complexity | Notes |
| --- | --- | --- |
| cinit / cclear | O(size) | rebuilds the free list |
| cnew | O(1) | pops the free list head |
| cfree | O(n) | finds the predecessor of i in the next chain |
| cget / cset / cnext / cshead / chead / clink | O(1) | direct read/write |
| clen | O(n) | walks from the head |

## Caveats

- Initialize first: call cinit(c) / cclear(c) once before use. After a processor reload the hidden variables reset to 0, so initialize again.
- cget / cset are index-based and do not follow the links. To walk the logical list, use chead plus cnext.
- clink does not detect cycles. Linking a node already in the list creates a cycle; clen / cfree will not terminate. The list invariants are your responsibility.
- cfree does not detect double frees. Freeing a node that is already on the free list creates a free-list cycle.
- Nodes and free list: cnew allocates, cfree recycles. A freshly allocated node has next = -1; link it with clink or cshead.
- Capacity: range [base, base+2*size); base + 2*size beyond capacity is a compile error.
- Names must not collide with other chains, arrays/matrices, user functions or other declaration cards; cross-module overlaps are a known limitation.

