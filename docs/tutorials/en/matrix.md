# Matrix (matrix)

> [Tutorial index](README.md) | Previous: [Array bulk ops](array-bulk.md) | Next: [Record](record.md) | Chinese: [../matrix.md](../matrix.md)

## When to use

A 2-D table: grids, boards, unit matrices, row/column lookup tables. A matrix is a row-major flattened array with a compile-time address formula.

## Declaration card

```text
matrix <name> <memory> <base> <rows> <cols>
```

Example: `matrix m cell1 0 2 3` is 2 rows by 3 columns and occupies addresses [0, 6). The card emits no mlog.

## Expression forms

| Form | Meaning |
| --- | --- |
| `m[i][j]` | read row i, column j |
| `m[i][j] = expr` | write row i, column j |

Address formula: physical address = base + i * cols + j (row-major).

## Lowered examples

```text
matrix m cell1 0 2 3
x = m[1][2]
```

Address = 0 + 1*3 + 2 = 5:

```text
read x cell1 5
```

Other literal folds: `m[0][0]` -> address 0, `m[1][0]` -> address 3.

Variable indices:

```text
matrix m cell1 0 2 3
x = m[i][j]
```

```text
op mul _0 i 3
op add _0 _0 j
read x cell1 _0
```

Non-zero base adds one more op:

```text
matrix m bank1 10 2 3
x = m[i][j]
```

```text
op mul _0 i 3
op add _0 _0 j
op add _0 10 _0
read x cell1 _0
```

Literal write:

```text
matrix m cell1 0 2 3
m[1][2] = 5
```

```text
write 5 cell1 5
```

## Complexity

| Operation | Complexity |
| --- | --- |
| read m[i][j] | O(1), folded address or 2 ops |
| write m[i][j] | O(1) |

## Caveats

- Two indices are required. `m[i]` reports a matrix needs two indices; `m[i][j][k]` reports an array accepts one index.
- Literal row/column out-of-range values are compile errors. Variable indices are not checked at runtime: out-of-range reads return NaN and writes are no-ops.
- len(m) is not supported. Use rows * cols; only 1-D arrays have len.
- Row-major order: the next address after m[i][j] is j+1, crossing to (i+1, 0) only at the end of a row. Bulk operations flatten matrices in this order.
- Capacity: base + rows*cols beyond the block capacity is a compile error (cellN 64, bankN / worldN 512).
- Names must not collide with other arrays/matrices/functions. Cross-module overlaps are a known limitation.

