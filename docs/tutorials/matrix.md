# 矩阵（matrix）

> 返回 [教程目录](README.md) · 上一章 [数组批量运算](array-bulk.md) · 下一章 [记录](record.md)

## 什么时候用

二维表：网格地图、棋盘、单位矩阵、按行/列组织的查找表。矩阵就是「按行主序摊平的一维数组」，地址计算全在编译期确定。

## 声明卡

```text
matrix <name> <memory> <base> <rows> <cols>
```

| 字段 | 说明 |
| --- | --- |
| `name` | 表达式里使用的名字，如 `m` |
| `memory` | 内存块变量名，如 `cell1` |
| `base` | 起始物理地址，非负整数 |
| `rows` | 行数，正整数 |
| `cols` | 列数，正整数 |

示例：`matrix m cell1 0 2 3` 表示 2 行 3 列，占用地址 `[0, 6)`。

声明卡只是编译期元数据，不产出 mlog。

## 表达式写法

| 写法 | 含义 |
| --- | --- |
| `m[i][j]` | 读取第 i 行第 j 列 |
| `m[i][j] = expr` | 写入第 i 行第 j 列 |

地址公式：**物理地址 = base + i * cols + j**（行主序）。

## 转译示例

```text
matrix m cell1 0 2 3
x = m[1][2]
```

地址 = 0 + 1*3 + 2 = 5，产物：

```text
read x cell1 5
```

其它字面量下标的折叠：

```text
x = m[0][0]   -> read x cell1 0
x = m[1][0]   -> read x cell1 3
```

变量下标会先算地址：

```text
matrix m cell1 0 2 3
x = m[i][j]
```

产物形状：

```text
op mul _0 i 3
op add _0 _0 j
read x cell1 _0
```

非零 base 会再加一次：

```text
matrix m bank1 10 2 3
x = m[i][j]
```

产物形状：

```text
op mul _0 i 3
op add _0 _0 j
op add _0 10 _0
read x cell1 _0
```

写入同理，最后一条是 `write`：

```text
matrix m cell1 0 2 3
m[1][2] = 5
```

产物：

```text
write 5 cell1 5
```

## 复杂度

| 操作 | 复杂度 |
| --- | --- |
| `m[i][j]` 读 | O(1)，地址编译期折叠或 2 条 `op` |
| `m[i][j] = v` 写 | O(1) |

## 使用须知

- **必须写两个下标**：`m[i]` 会报「矩阵需要两个下标」，`m[i][j][k]` 会报「数组只接受一个下标」。
- **行/列字面量越界是编译错误**；变量下标在运行时不额外检查，越界 `read` 返回 NaN、越界 `write` 是空操作。
- **`len(m)` 不支持**：矩阵没有 `len`，请用 `rows * cols` 或对着行列自己算；一维数组才支持 `len`。
- **行主序**：`m[i][j]` 的下一个内存地址是 `j+1`，跨行才跳到 `(i+1, 0)`。批量运算（`array_sum` / `array_sort` 等）会把矩阵按这个顺序摊平，见 [array-bulk.md](array-bulk.md)。
- **容量**：`base + rows*cols` 超过内存块容量时编译期报错（`cellN` = 64，`bankN` / `worldN` = 512）。
- **重名与区间**：不能与其它数组/矩阵/函数重名；与其它数据结构的区间重叠是跨模块已知限制。

