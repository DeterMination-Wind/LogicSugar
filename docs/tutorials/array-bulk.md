# 数组批量运算（array bulk）

> 返回 [教程目录](README.md) · 上一章 [一维数组](array.md) · 下一章 [矩阵](matrix.md)

## 什么时候用

需要一次性处理整段数组：求和、平均值、最值、计数、查找、填充、复制、排序、反转、替换、交换、二分查找。

这些函数复用 `array` / `matrix` 声明卡，不需要新声明；参数必须是已声明的数组名。矩阵会按行主序摊平成 `rows*cols` 个元素。

## 函数速查表

| 函数 | 参数 | 返回 | 说明 |
| --- | --- | --- | --- |
| `array_sum(buf)` | 数组 | 总和 | 全部元素相加 |
| `array_avg(buf)` | 数组 | 平均值 | `array_sum / size` |
| `array_min(buf)` | 数组 | 最小值 | 1 参 `array_min` 是数组运算 |
| `array_max(buf)` | 数组 | 最大值 | 1 参 `array_max` 是数组运算 |
| `array_count(buf, v)` | 数组, 值 | 出现次数 | 无匹配返回 0 |
| `array_find(buf, v)` | 数组, 值 | 首个下标 | 无匹配返回 -1 |
| `array_fill(buf, v)` | 数组, 值 | 无意义哨兵 | 把整段写成 v |
| `array_copy(dst, src)` | 目标, 源 | 无意义哨兵 | 要求两者长度相同 |
| `array_sort(buf)` | 数组 | 无意义哨兵 | 升序排序（原地） |
| `array_sort_desc(buf)` | 数组 | 无意义哨兵 | 降序排序（原地） |
| `array_reverse(buf)` | 数组 | 无意义哨兵 | 原地反转 |
| `array_replace(buf, old, neu)` | 数组, 旧值, 新值 | 替换次数 | 等于 old 的写成 neu |
| `array_swap(buf, i, j)` | 数组, 下标 i, 下标 j | 无意义哨兵 | 交换两个下标 |
| `array_lower_bound(buf, v)` | 数组, 值 | 下标或 -1 | 要求数组已升序 |

> 提示：编辑器菜单与积木显示的是新名字（如 `array_sum`）；旧短名（如 `sum`）仍能解析已有存档，但新写的卡片一律用新名。

注意：

- `min(a, b)` / `max(a, b)` 两个参数时是原版数学内置，不是数组运算；只有一参形式走数组批量运算。
- `len(buf)` 是数组长度（见 [array.md](array.md)），不属于本页的批量运算。
- `array_fill` / `array_copy` / `array_sort` / `array_sort_desc` / `array_reverse` / `array_swap` 是「积木型」操作，没有有意义的表达式返回值；在 Expr 里被赋值时拿到的是实现哨兵，请不要读取。

## 转译示例

所有批量运算都编译成对注入函数 `__ls_builtin_arr*` 的 `funccall`，参数是内存块名与字面量 base/size。normal 模式下每个子程序全程序共享一份。

```text
array buf cell1 0 8
x = array_sum(buf)
```

产物：

```text
funccall __ls_builtin_arrsum "cell1, 0, 8" x
```

更多例子：

```text
x = array_avg(buf)           -> funccall __ls_builtin_arravg     "cell1, 0, 8" x
x = array_min(buf)           -> funccall __ls_builtin_arrmin     "cell1, 0, 8" x
x = array_max(buf)           -> funccall __ls_builtin_arrmax     "cell1, 0, 8" x
x = array_count(buf, 3)      -> funccall __ls_builtin_arrcount   "cell1, 0, 8, 3" x
x = array_find(buf, i)       -> funccall __ls_builtin_arrindexof "cell1, 0, 8, i" x
array_fill(buf, 5)           -> funccall __ls_builtin_arrfill    "cell1, 0, 8, 5" x
array_sort(buf)              -> funccall __ls_builtin_arrsort    "cell1, 0, 8, 1" x
array_sort_desc(buf)         -> funccall __ls_builtin_arrsort    "cell1, 0, 8, -1" x
array_reverse(buf)           -> funccall __ls_builtin_arrrev     "cell1, 0, 8" x
array_replace(buf, 1, 9)     -> funccall __ls_builtin_arrrepl    "cell1, 0, 8, 1, 9" x
array_swap(buf, i, j)        -> funccall __ls_builtin_arrswap    "cell1, 0, i, j" x
array_lower_bound(buf, 3)    -> funccall __ls_builtin_arrbsearch "cell1, 0, 8, 3" x
```

矩阵按行主序摊平：

```text
matrix m cell1 0 2 3
x = array_sum(m)
```

产物：

```text
funccall __ls_builtin_arrsum "cell1, 0, 6" x
```

`array_copy(a, b)` 要求长度相同；矩阵与数组混用时按摊平后的元素数比较：

```text
array a cell1 0 8
array b cell1 8 8
array_copy(a, b)
```

产物：

```text
funccall __ls_builtin_arrcopy "cell1, 0, cell1, 8, 8" x
```

## 复杂度

| 操作 | 复杂度 | 说明 |
| --- | --- | --- |
| `array_sum` / `array_avg` / `array_min` / `array_max` | O(n) | 一遍扫描 |
| `array_count` / `array_replace` | O(n) | 一遍扫描 |
| `array_find` | O(n) 最坏；命中即停 | 找到第一个匹配后不再读取后续元素 |
| `array_fill` / `array_copy` / `array_reverse` | O(n) | 每个元素一次 |
| `array_sort` / `array_sort_desc` | O(n^1.5) ~ O(n²) | 希尔排序（间隔 `size/2, size/4, …, 1`）；大数组要注意 1000 条指令与运行时间 |
| `array_swap` | O(1) | 固定 4 条读写 |
| `array_lower_bound` | O(log n) | 要求升序；未命中返回 -1 |

## 使用须知

- **参数必须是已声明的数组名**，不能写表达式或其他结构的名字；否则编译期报错。
- **`array_copy` 要求长度相同**，矩阵按摊平后的 `rows*cols` 与数组 `size` 比较。
- **`array_sort` / `array_sort_desc` 是原地希尔排序**：每轮对间隔 `size/2, size/4, …, 1` 的子序列做插入排序，最后一轮即普通插入排序。原地、无辅助内存、子程序固定占约 33 条指令；随机/逆序数据比过去的插入排序快得多，几百个元素仍会明显变慢（处理器每 tick 只执行固定条数指令）。
- **断代说明（v5.0.0 → 下一版）**：排序内置函数体的指令序列变了。旧版本保存过、且用过 `sortasc` / `sortdesc` 的处理器，重开时载体校验会失败，编辑器回落到原版视图——`array` 声明卡和排序卡片会显示成原始 mlog 指令。重新放一次排序卡片即可恢复正常。这是「反编译安全门逐指令比对产物」的必然结果，不是 bug。
- **断代说明（v5.1.1 → 下一版）**：`indexof` 的内置函数体为「命中即停」新增一条跳出循环的 `jump`，`copy` 修正了读/写基址写反的 bug（同块 `copy` 不再反向、跨块不再写错区域）。v5.1.1 及更早保存过、且用过这两个内置的处理器，重开时载体校验同样会失败并回落到原版视图；重新放一次对应的数组运算卡片即可恢复。这是修复正确性的必要代价。
- **`array_lower_bound` 只保证在升序数组上正确**；数组未排序时结果未定义。降序数组请先用 `array_sort`。
- **`array_fill` / `array_copy` / `array_sort` / `array_sort_desc` / `array_reverse` / `array_swap` 没有有意义的返回值**：在积木里用即可，不要在表达式里依赖它们的返回。
- **越界**：`array_lower_bound` / `array_swap` 等不做额外的运行时越界检查；`array_swap` 的下标必须合法。
- **容量与内存区间**：与数组一致，见 [array.md](array.md) 的容量与重名说明。

