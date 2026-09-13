# 列表（list）

> 返回 [教程目录](README.md) · 上一章 [无序集合](uset.md) · 下一章 [小顶堆](heap.md)

## 什么时候用

紧凑的顺序表：需要按下标读写、按顺序插入/删除、查找元素。列表没有空洞，元素始终放在 `base .. base+count-1`。

## 声明卡

```text
list <name> <memory> <base> <size>
```

示例：`list l cell1 2 4` 使用 `cell1` 地址 `2..5`，最多 4 个元素。

状态是隐藏变量 `__ls_lst_l_count`（当前元素个数）。未赋值读作 0，因此列表默认从空开始是安全的。

## 函数速查表

| 函数 | 参数 | 返回 | 说明 |
| --- | --- | --- | --- |
| `lappend(l, v)` | 列表, 值 | 新元素个数 | 满时返回当前个数且不写入 |
| `lget(l, i)` | 列表, 下标 | 元素或 NaN | 越界返回 NaN |
| `lset(l, i, v)` | 列表, 下标, 值 | 1 / 0 | 越界不写入 |
| `linsert(l, i, v)` | 列表, 下标, 值 | 1 / 0 | 在 i 处插入，元素右移；满/越界返回 0 |
| `lremove(l, i)` | 列表, 下标 | 被删元素或 NaN | 删除后元素左移；越界返回 NaN |
| `lfind(l, v)` | 列表, 值 | 下标或 -1 | 首个匹配；未找到 -1 |
| `lsize(l)` | 列表 | 元素个数 | O(1) |

方法糖：`l[i]` / `l.get(i)` 等价于 `lget(l, i)`，`l.size()` / `l.length()` / `l.count()` 等价于 `lsize(l)`。

## 转译示例

### 大小

```text
list l cell1 2 4
x = lsize(l)
```

产物：

```text
op add x __ls_lst_l_count 0
```

### 按下标读取（带越界守卫）

```text
x = lget(l, i)
```

产物：

```text
op lessThan _0 i 0
op lessThanEq _1 __ls_lst_l_count i
op or _2 _0 _1
op add _3 2 i
op add _4 _3 1
op mul _2 _2 _4
op sub _3 _3 _2
read x cell1 _3
```

说明：`invalid = (i < 0) || (count <= i)`；地址 = `base + i`，无效时被拉到 `-1`，越界读返回 NaN。

### 追加

```text
x = lappend(l, 7)
```

产物：

```text
funccall __ls_builtin_lstappend "cell1, 2, 4, __ls_lst_l_count, 7" __ls_lst_l_count
op add x __ls_lst_l_count 0
```

`__ls_builtin_lstappend` 返回新的 `count`（满时返回旧 `count`），调用点直接把返回值写回计数变量。

### 写入

```text
x = lset(l, i, 5)
```

产物：

```text
funccall __ls_builtin_lstset "cell1, 2, __ls_lst_l_count, i, 5" x
```

### 插入与删除

```text
x = linsert(l, i, 5)
```

产物形状：

```text
op add _0 __ls_lst_l_count 0
funccall __ls_builtin_lstinsert "cell1, 2, 4, __ls_lst_l_count, i, 5" __ls_lst_l_count
op notEqual _1 __ls_lst_l_count _0
op add x _1 0
```

```text
x = lremove(l, i)
```

产物形状：

```text
op add _0 __ls_lst_l_count 0
op lessThan _1 i 0
op greaterThanEq _2 i _0
op or _3 _1 _2
op sub _4 1 _3
op sub __ls_lst_l_count _0 _4
funccall __ls_builtin_lstremove "cell1, 2, _0, i" x
```

`linsert` 返回 1/0（是否成功），`lremove` 返回被删除的值或 NaN。

## 复杂度

| 操作 | 复杂度 | 说明 |
| --- | --- | --- |
| `lappend` / `lget` / `lset` / `lsize` | O(1) | 直接读写或固定守卫 |
| `linsert` / `lremove` | O(n) | 需要右移/左移元素 |
| `lfind` | O(n) | 顺序扫描 |

## 使用须知

- **越界语义**：`lget` / `lremove` 越界返回 NaN；`lset` / `linsert` 越界返回 0 且不写入。
- **满列表**：`lappend` 返回当前 `size` 且不写入；`linsert` 在 `count >= size` 时返回 0。
- **紧凑存储**：元素永远在 `[base, base+count)`；`lremove` 会把后面的元素左移，`linsert` 会把元素右移。不要自己在 `base+count` 之后写数据。
- **状态不持久化**：`__ls_lst_l_count` 是普通变量，处理器重载后回到 0；内存内容仍在。需要清空时把 `count` 归零（可以先 `lremove` 全部，或直接用 `sclear` 风格的赋值），空列表默认安全。
- **容量**：区间 `[base, base+size)`，`base + size` 超容量编译期报错。
- **跨模块**：与其它结构重名会报错；区间重叠不会被自动拦截。

