# Bitset (bitset)

> [Tutorial index](README.md) | Previous: [Deque](deque.md) | Next: [Map](map.md) | Chinese: [../bitset.md](../bitset.md)

## When to use

Many boolean flags: visited marks, switch arrays, sieves, per-unit or per-tile state bits. One memory slot holds 64 bits, so words slots hold words*64 booleans.

## Declaration card

```text
bitset <name> <memory> <base> <words>
```

Example: `bitset bs cell1 0 2` uses cell1 addresses 0..1 and has 128 bits.

Index conversion:

```text
word = i / 64
bit  = i % 64
mask = 1 << bit
address = base + word
```

Bit 0 is the least significant bit of word 0.

## Function table

| Function | Arguments | Returns | Notes |
| --- | --- | --- | --- |
| `bset(b, i)` | bitset, index | 1 / 0 | set bit; out of range does not write |
| `bclr(b, i)` | bitset, index | 1 / 0 | clear bit; out of range does not write |
| `btest(b, i)` | bitset, index | 1 / 0 | read bit; out of range returns 0 |
| `bcount(b)` | bitset | number of set bits | scans all words; O(words) |

Sugar: `b[i]`, `b.test(i)`, `b.get(i)` equal `btest(b, i)`.

## Lowered examples

Set:

```text
bitset bs cell1 0 2
x = bset(bs, i)
```

```text
op idiv _0 i 64
op mod _1 i 64
op shl _2 1 _1
op greaterThanEq _3 i 0
op lessThan _4 i 128
op land _5 _3 _4
op mul _2 _2 _5
op mul _0 _0 _5
op add _6 0 _0
read _7 cell1 _6
op or _7 _7 _2
funccall __ls_builtin_bwrite "cell1, _6, _7" x
```

The guard multiplies the mask and word by 0 when the index is out of range, so the read-modify-write becomes a no-op and returns 0. The write-back goes through the shared `__ls_builtin_bwrite` function.

Clear replaces `op or` with `op not` plus `op and`. Test ends with `op and _8 _7 _2` and `op notEqual x _8 0`.

Count:

```text
x = bcount(bs)
```

```text
funccall __ls_builtin_bitcount "cell1, 0, 2" x
```

## Complexity

| Operation | Complexity | Notes |
| --- | --- | --- |
| bset / bclr / btest | O(1) | a few op/read/funccall lines |
| bcount | O(words) | one loop per memory slot |

## Caveats

- Capacity: base + words must fit the block (cellN 64, bankN / worldN 512).
- Out-of-range bset/bclr return 0 and do not write; btest returns 0. Negative indices and indices >= words*64 are out of range.
- Bit order: bit = i % 64, bit 0 is 1 << 0. Cross-word ordering follows your own access order; LogicSugar only keeps the formula consistent.
- bcount is an O(words) loop. Avoid calling it every tick in a hot condition.
- Overlaps with other structures are not rejected automatically; the bitset range is [base, base+words).

