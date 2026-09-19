# 双端队列（deque）

> 返回 [教程目录](README.md) · 上一章 [队列](queue.md) · 下一章 [位集](bitset.md)

## 什么时候用

两端都能进出：滑动窗口、单调队列、双端 BFS、需要在队首插入的任务队列。

## 声明卡

```text
deque <name> <memory> <base> <size>
```

示例：`deque d cell2 0 4` 使用 `cell2` 地址 `0..3`。

和队列一样是环形缓冲，状态：

```text
__ls_deq_d_head     队首物理下标
__ls_deq_d_tail     队尾物理下标（下一个可写位置）
__ls_deq_d_count    当前元素个数
```

不变式：`tail == (head + count) % size`。

## 函数速查表

| 函数 | 参数 | 返回 | 说明 |
| --- | --- | --- | --- |
| `deque_push_front(d, v)` | 双端队列, 值 | 新元素个数 | 队首插入；满时返回当前个数且不写入 |
| `deque_push_back(d, v)` | 双端队列, 值 | 新元素个数 | 队尾插入；满时返回当前个数且不写入 |
| `deque_pop_front(d)` | 双端队列 | 队首元素 | 空时返回 NaN |
| `deque_pop_back(d)` | 双端队列 | 队尾元素 | 空时返回 NaN |
| `deque_front(d)` | 双端队列 | 队首元素 | 空时返回 NaN，不出队 |
| `deque_back(d)` | 双端队列 | 队尾元素 | 空时返回 NaN，不出队 |
| `deque_size(d)` | 双端队列 | 元素个数 | O(1) |
| `deque_clear(d)` | 双端队列 | 0 | 清空 head/tail/count |

> 提示：编辑器菜单与积木显示的是新名字（如 `deque_push_front`）；旧短名（如 `dpushf`）仍能解析已有存档，但新写的卡片一律用新名。

方法糖：`d.front()` / `d.peekFront()` 等价于 `deque_front(d)`，`d.back()` / `d.peekBack()` 等价于 `deque_back(d)`，`d.size()` / `d.count()` 等价于 `deque_size(d)`。

## 转译示例

### 大小

```text
deque d cell2 0 4
x = deque_size(d)
```

产物：

```text
op add x __ls_deq_d_count 0
```

### 看队尾

队尾下标是 `(head + count + size - 1) % size`：

```text
x = deque_back(d)
```

产物：

```text
op lessThanEq _0 __ls_deq_d_count 0
op add _3 __ls_deq_d_head __ls_deq_d_count
op add _3 _3 4
op sub _3 _3 1
op mod _3 _3 4
op add _1 0 _3
op add _2 _1 1
op mul _0 _0 _2
op sub _1 _1 _0
read x cell2 _1
```

### 队尾插入

```text
x = deque_push_back(d, 7)
```

产物：

```text
funccall __ls_builtin_quepush "cell2, 0, 4, __ls_deq_d_head, __ls_deq_d_count, 7" __ls_deq_d_count
op add __ls_deq_d_tail __ls_deq_d_head __ls_deq_d_count
op mod __ls_deq_d_tail __ls_deq_d_tail 4
op add x __ls_deq_d_count 0
```

队尾插入复用队列的 push 注入函数。

### 队首插入

```text
x = deque_push_front(d, 7)
```

产物：

```text
funccall __ls_builtin_deqpushf "cell2, 0, 4, __ls_deq_d_head, __ls_deq_d_count, 7" __ls_deq_d_head
op add _0 __ls_deq_d_count 1
op min __ls_deq_d_count _0 4
op add __ls_deq_d_tail __ls_deq_d_head __ls_deq_d_count
op mod __ls_deq_d_tail __ls_deq_d_tail 4
op add x __ls_deq_d_count 0
```

队首插入会先移动 `head`（`__ls_builtin_deqpushf` 的返回值），再修正 `count` 和 `tail`。

## 复杂度

| 操作 | 复杂度 |
| --- | --- |
| `deque_push_front` / `deque_push_back` / `deque_pop_front` / `deque_pop_back` / `deque_front` / `deque_back` / `deque_size` / `deque_clear` | O(1) |

## 使用须知

- **空 deque**：`deque_pop_front` / `deque_pop_back` / `deque_front` / `deque_back` 返回 NaN，状态不变。
- **满 deque**：`deque_push_front` / `deque_push_back` 返回当前 `size` 且不写入。
- **两端语义**：`f` = 前端（队首），`b` = 后端（队尾）；`deque_front` 读 `head`，`deque_back` 读 `(head + count + size - 1) % size`。
- **状态不持久化**：三个隐藏变量在处理器重载后回到 0；残留旧数据时先 `deque_clear(d)`。
- **容量**：区间 `[base, base+size)`，`base + size` 超容量编译期报错。
- **队列与双端队列可以共用同一内存块的不同区间**（区间不重叠时），但跨模块重叠不会被自动拦截。

