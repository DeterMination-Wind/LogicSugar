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
| `vector_push_back(l, v)` | 列表, 值 | 新元素个数 | 满时返回当前个数且不写入 |
| `vector_at(l, i)` | 列表, 下标 | 元素或 NaN | 越界返回 NaN |
| `vector_set(l, i, v)` | 列表, 下标, 值 | 1 / 0 | 越界不写入 |
| `vector_insert(l, i, v)` | 列表, 下标, 值 | 1 / 0 | 在 i 处插入，元素右移；满/越界返回 0 |
| `vector_erase(l, i)` | 列表, 下标 | 被删元素或 NaN | 删除后元素左移；越界返回 NaN |
| `vector_find(l, v)` | 列表, 值 | 下标或 -1 | 首个匹配；未找到 -1 |
| `vector_size(l)` | 列表 | 元素个数 | O(1) |

> 提示：编辑器菜单与积木显示的是新名字（如 `vector_push_back`）；旧短名（如 `lappend`）仍能解析已有存档，但新写的卡片一律用新名。

方法糖：`l[i]` / `l.get(i)` 等价于 `vector_at(l, i)`，`l.find(v)` / `l.indexOf(v)` 等价于 `vector_find(l, v)`，`l.size()` / `l.length()` / `l.count()` 等价于 `vector_size(l)`。

## 转译示例

### 大小

```text
list l cell1 2 4
x = vector_size(l)
```

产物：

```text
op add x __ls_lst_l_count 0
```

### 按下标读取（带越界守卫）

```text
x = vector_at(l, i)
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
x = vector_push_back(l, 7)
```

产物：

```text
funccall __ls_builtin_lstappend "cell1, 2, 4, __ls_lst_l_count, 7" __ls_lst_l_count
op add x __ls_lst_l_count 0
```

`__ls_builtin_lstappend` 返回新的 `count`（满时返回旧 `count`），调用点直接把返回值写回计数变量。

### 写入

```text
x = vector_set(l, i, 5)
```

产物：

```text
funccall __ls_builtin_lstset "cell1, 2, __ls_lst_l_count, i, 5" x
```

### 插入与删除

```text
x = vector_insert(l, i, 5)
```

产物形状：

```text
op add _0 __ls_lst_l_count 0
funccall __ls_builtin_lstinsert "cell1, 2, 4, __ls_lst_l_count, i, 5" __ls_lst_l_count
op notEqual _1 __ls_lst_l_count _0
op add x _1 0
```

```text
x = vector_erase(l, i)
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

`vector_insert` 返回 1/0（是否成功），`vector_erase` 返回被删除的值或 NaN。

## 复杂度

| 操作 | 复杂度 | 说明 |
| --- | --- | --- |
| `vector_push_back` / `vector_at` / `vector_set` / `vector_size` | O(1) | 直接读写或固定守卫 |
| `vector_insert` / `vector_erase` | O(n) | 需要右移/左移元素 |
| `vector_find` | O(n) | 顺序扫描 |

## 使用须知

- **越界语义**：`vector_at` / `vector_erase` 越界返回 NaN；`vector_set` / `vector_insert` 越界返回 0 且不写入。
- **满列表**：`vector_push_back` 返回当前 `size` 且不写入；`vector_insert` 在 `count >= size` 时返回 0。
- **紧凑存储**：元素永远在 `[base, base+count)`；`vector_erase` 会把后面的元素左移，`vector_insert` 会把元素右移。不要自己在 `base+count` 之后写数据。
- **状态不持久化**：`__ls_lst_l_count` 是普通变量，处理器重载后回到 0；内存内容仍在。需要清空时把 `count` 归零（可以先 `vector_erase` 全部，或直接用 `stack_clear` 风格的赋值），空列表默认安全。
- **容量**：区间 `[base, base+size)`，`base + size` 超容量编译期报错。
- **跨模块**：与其它结构重名会报错；区间重叠不会被自动拦截。

