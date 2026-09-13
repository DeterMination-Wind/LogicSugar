# 数组批量运算（array bulk）

> 返回 [教程目录](README.md) · 上一章 [一维数组](array.md) · 下一章 [矩阵](matrix.md)

## 什么时候用

需要一次性处理整段数组：求和、平均值、最值、计数、查找、填充、复制、排序、反转、替换、交换、二分查找。

这些函数复用 `array` / `matrix` 声明卡，不需要新声明；参数必须是已声明的数组名。矩阵会按行主序摊平成 `rows*cols` 个元素。

## 函数速查表

| 函数 | 参数 | 返回 | 说明 |
| --- | --- | --- | --- |
| `sum(buf)` | 数组 | 总和 | 全部元素相加 |
| `avg(buf)` | 数组 | 平均值 | `sum / size` |
| `min(buf)` | 数组 | 最小值 | 1 参 `min` 是数组运算 |
| `max(buf)` | 数组 | 最大值 | 1 参 `max` 是数组运算 |
| `count(buf, v)` | 数组, 值 | 出现次数 | 无匹配返回 0 |
| `indexof(buf, v)` | 数组, 值 | 首个下标 | 无匹配返回 -1 |
| `fill(buf, v)` | 数组, 值 | 无意义哨兵 | 把整段写成 v |
| `copy(dst, src)` | 目标, 源 | 无意义哨兵 | 要求两者长度相同 |
| `sortasc(buf)` | 数组 | 无意义哨兵 | 升序排序（原地） |
| `sortdesc(buf)` | 数组 | 无意义哨兵 | 降序排序（原地） |
| `reverse(buf)` | 数组 | 无意义哨兵 | 原地反转 |
| `replace(buf, old, neu)` | 数组, 旧值, 新值 | 替换次数 | 等于 old 的写成 neu |
| `swap(buf, i, j)` | 数组, 下标 i, 下标 j | 无意义哨兵 | 交换两个下标 |
| `bsearch(buf, v)` | 数组, 值 | 下标或 -1 | 要求数组已升序 |

注意：

- `min(a, b)` / `max(a, b)` 两个参数时是原版数学内置，不是数组运算；只有一参形式走数组批量运算。
- `len(buf)` 是数组长度（见 [array.md](array.md)），不属于本页的批量运算。
- `fill` / `copy` / `sortasc` / `sortdesc` / `reverse` / `swap` 是「积木型」操作，没有有意义的表达式返回值；在 Expr 里被赋值时拿到的是实现哨兵，请不要读取。

## 转译示例

所有批量运算都编译成对注入函数 `__ls_builtin_arr*` 的 `funccall`，参数是内存块名与字面量 base/size。normal 模式下每个子程序全程序共享一份。

```text
array buf cell1 0 8
x = sum(buf)
```

产物：

```text
funccall __ls_builtin_arrsum "cell1, 0, 8" x
```

更多例子：

```text
x = avg(buf)             -> funccall __ls_builtin_arravg "cell1, 0, 8" x
x = min(buf)             -> funccall __ls_builtin_arrmin "cell1, 0, 8" x
x = max(buf)             -> funccall __ls_builtin_arrmax "cell1, 0, 8" x
x = count(buf, 3)        -> funccall __ls_builtin_arrcount "cell1, 0, 8, 3" x
x = indexof(buf, i)      -> funccall __ls_builtin_arrindexof "cell1, 0, 8, i" x
fill(buf, 5)             -> funccall __ls_builtin_arrfill "cell1, 0, 8, 5" x
sortasc(buf)             -> funccall __ls_builtin_arrsort "cell1, 0, 8, 1" x
sortdesc(buf)            -> funccall __ls_builtin_arrsort "cell1, 0, 8, -1" x
reverse(buf)             -> funccall __ls_builtin_arrrev "cell1, 0, 8" x
replace(buf, 1, 9)       -> funccall __ls_builtin_arrrepl "cell1, 0, 8, 1, 9" x
swap(buf, i, j)          -> funccall __ls_builtin_arrswap "cell1, 0, i, j" x
bsearch(buf, 3)          -> funccall __ls_builtin_arrbsearch "cell1, 0, 8, 3" x
```

矩阵按行主序摊平：

```text
matrix m cell1 0 2 3
x = sum(m)
```

产物：

```text
funccall __ls_builtin_arrsum "cell1, 0, 6" x
```

`copy(a, b)` 要求长度相同；矩阵与数组混用时按摊平后的元素数比较：

```text
array a cell1 0 8
array b cell1 8 8
copy(a, b)
```

产物：

```text
funccall __ls_builtin_arrcopy "cell1, 0, cell1, 8, 8" x
```

## 复杂度

| 操作 | 复杂度 | 说明 |
| --- | --- | --- |
| `sum` / `avg` / `min` / `max` | O(n) | 一遍扫描 |
| `count` / `indexof` / `replace` | O(n) | 一遍扫描 |
| `fill` / `copy` / `reverse` | O(n) | 每个元素一次 |
| `sortasc` / `sortdesc` | O(n²) | 插入排序；大数组要注意 1000 条指令与运行时间 |
| `swap` | O(1) | 固定 4 条读写 |
| `bsearch` | O(log n) | 要求升序；未命中返回 -1 |

## 使用须知

- **参数必须是已声明的数组名**，不能写表达式或其他结构的名字；否则编译期报错。
- **`copy` 要求长度相同**，矩阵按摊平后的 `rows*cols` 与数组 `size` 比较。
- **`sortasc` / `sortdesc` 是原地插入排序**：小数组没问题，几百个元素会明显变慢，且子程序本身会占用固定指令数。
- **`bsearch` 只保证在升序数组上正确**；数组未排序时结果未定义。降序数组请先用 `sortasc`。
- **`fill` / `copy` / `sortasc` / `sortdesc` / `reverse` / `swap` 没有有意义的返回值**：在积木里用即可，不要在表达式里依赖它们的返回。
- **越界**：`bsearch` / `swap` 等不做额外的运行时越界检查；`swap` 的下标必须合法。
- **容量与内存区间**：与数组一致，见 [array.md](array.md) 的容量与重名说明。

