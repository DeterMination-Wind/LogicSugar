# Combined memory (span)

> [Tutorial index](README.md) | Previous: [Chain](chain.md) | Chinese: [../span.md](../span.md)

## When to use

One memory block is not big enough, but you own several equally sized `cell` / `bank` / `world` blocks and want them to look like **one contiguous address space**: an array that does not fit in a single cell, a matrix laid out row-block by row-block, or simply not writing `i / 64` and `i % 64` yourself.

`span` is the name for that logical address space. It moves no data: it only converts a logical address into "which block + slot inside it" at compile time, and hands the logical capacity `N * C` to the range checks of arrays, matrices and containers.

## Declaration card

```text
span <name> "<cell1 + cell3 + cell2>"
```

In the editor the card shows as one expression, `mem = cell1 + cell3 + cell2`:

- `+` means **concatenate in written order** — it is not addition and never becomes an `op`;
- the first name is logical address 0, the second starts at `C`, the third at `2C`, … (`C` = per-block capacity);
- any number of blocks, but at least two, and every block must have the same capacity, otherwise the compile fails;
- `mem` can be used directly as a memory name (`read x mem i` / `write v mem i`), or typed into the memory field of an array/matrix/stack/queue/deque/bitset/map/uset/list/heap/chain card: `array buf big 0 128`.

Per-block capacity `C` is resolved in this order: first the block the variable is **linked to in the processor** (`memoryCapacity`, so a world-cell counts its real 512), and only when nothing resolves, by name (`cellN` = 64, `bankN` / `worldN` = 512 — the same table the other cards use). A name that resolves nowhere and looks like no link name is a compile error: addressing needs a definite divisor.

## Lowered example

```text
span big "cell1 + cell2"
read x big i
```

Product (`C = 64`):

```text
op idiv __ls_span_q i 64
op mod __ls_span_r i 64
select __ls_span_b equal __ls_span_q 0 cell1 0
select __ls_span_b equal __ls_span_q 1 cell2 __ls_span_b
read x __ls_span_b __ls_span_r
```

Reading it: `__ls_span_q = i / 64` (floor) picks the block, `__ls_span_r = i % 64` is the slot inside it, the `select` chain turns "which block" into the actual block variable, and the final `read` / `write` lands on it.

- A constant address is folded at compile time: `read x big 70` → `read x cell2 6`.
- Out of range (negative, or `>= N*C`) no `select` matches, so the target stays numeric `0` (the null building): the read yields nothing and the write is dropped, exactly like an out-of-range vanilla `read` / `write`.
- A variable address is a fixed `N+3` instructions; what you save is index arithmetic, not instructions.

## Complexity

| Operation | Complexity |
| --- | --- |
| constant `read` / `write` | O(1), one instruction (folded at compile time) |
| variable `read` / `write` | O(1), fixed `N+3` instructions (N = block count) |

Array/matrix/container complexity is unchanged; each access just carries a few more addressing instructions.

## Usage notes

- **Equal capacities**: mixed capacities are a compile error. `cell1 + cell2` is 64 + 64; `cell1 + bank1` is 64 + 512 and cannot be combined.
- **The name must not shadow a link**: `mem` cannot be `cell1` / `bank1` / `world1`, and cannot collide with an array/matrix/function name.
- **Nothing is moved**: a span is an alias; data stays block by block, and save/map round trips keep the physical layout.
- **Just want to treat several blocks as one range**: declaring the span and using `read` / `write` is enough. To get the `buf[i]` subscript sugar, also declare `array buf big 0 <size>` (`size <= N*C`).
- **No cross-block bulk ops**: injected functions such as `array_sum` / `array_sort` address a single block, so a span memory is a compile error there — call them per block, or write your own index loop.
- **Reopening a save**: the `span` card lives only in the sugar carrier; plain mlog (no carrier) never grows one. An Expr card using `buf[i]` comes back with the carrier too.
- **Multiplayer**: the product is plain vanilla `op` / `select` / `read` / `write`, so other clients need no mod.
