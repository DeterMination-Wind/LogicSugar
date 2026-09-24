# Array (array)

> [Tutorial index](README.md) | Next: [Array bulk ops](array-bulk.md) | Chinese: [../array.md](../array.md)

## When to use

Random read/write over a contiguous memory range: counter arrays, buffers, lookup tables, anything you index as buf[i].

## Declaration card

```text
array <name> <memory> <base> <size>
```

| Field | Meaning |
| --- | --- |
| name | array name used in expressions, for example buf |
| memory | memory block variable, for example cell1 or bank1 |
| base | starting physical address, non-negative integer literal |
| size | element count, positive integer literal |

Example: `array buf cell1 0 8` maps buf[0]..buf[7] to addresses 0..7. The declaration card emits no mlog.

## Expression forms

| Form | Meaning | Result |
| --- | --- | --- |
| `buf[i]` | read index i | NaN when out of range |
| `buf[i] = expr` | write index i | no write when out of range |
| `len(buf)` | compile-time length | the literal size |

The index can be any expression. Literal indices are folded at compile time; variable indices compute the address at runtime.

## Lowered examples

Every example below is written in the Sugar source form (what the declaration card and the Expr card contain). That same text can be pasted straight into the processor's code field: the declaration line imports as a card, and a line such as `x = buf[3]` imports as an Expr card instead of becoming an empty `noop` card.

Literal index:

```text
array buf cell1 0 8
x = buf[3]
```

```text
read x cell1 3
```

Variable index with non-zero base:

```text
array buf cell1 10 8
x = buf[i]
```

```text
op add _0 10 i
read x cell1 _0
```

With base 0 the index is the address directly:

```text
read x cell1 i
```

Indexed write:

```text
array buf cell1 10 8
buf[i] = 5
```

```text
op add _0 10 i
write 5 cell1 _0
```

Length:

```text
array buf cell1 0 8
x = len(buf)
```

`len(buf)` folds to the literal 8; as an expression statement the product is:

```text
op add x 8 0
```

## Complexity

| Operation | Complexity |
| --- | --- |
| read buf[i] | O(1), one read |
| write buf[i] | O(1), one write |
| len(buf) | O(1), compile-time constant |

## Caveats

- Literal out-of-range indices are compile errors. Variable out-of-range reads return NaN and writes are no-ops in strip mode; debug assert builds insert assertBounds before the read/write.
- base and size must be integer literals. The length cannot change at runtime.
- Capacity: resolved from the memory block the `memory` variable is **actually linked to**; only when no link can be resolved does the name table apply (cellN 64, bankN / worldN 512 — a world-cell is also linked as cellN but holds 512, and the linked block wins). base + size beyond capacity is a compile error, and a guessed capacity is labelled as inferred in the message.
- Names must not collide with other arrays, matrices or user functions. Cross-module overlaps are a known limitation and are not rejected automatically.
- Memory slots currently hold numbers. Convert units/buildings to numbers with sensor / op before storing objects; see the architecture overview for the memory object storage note.
- If x is not a declared array, `x[i]` degrades to a plain `read x i` when no array declarations exist, and becomes an unknown-array error once any array is declared.
- If an array and a list/bitset/chain share a name, `x[i]` is resolved as an array first. Do not rely on cross-module duplicates.

