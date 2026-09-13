# 队列（queue）

> 返回 [教程目录](README.md) · 上一章 [栈](stack.md) · 下一章 [双端队列](deque.md)

## 什么时候用

先进先出：BFS、任务调度、按顺序处理事件、流水线缓冲区。

## 声明卡

```text
queue <name> <memory> <base> <size>
```

示例：`queue q cell2 0 4` 使用 `cell2` 地址 `0..3`。

声明卡只是编译期元数据，不产出 mlog。队列是环形缓冲，状态保存在三个隐藏变量里：

```text
__ls_que_q_head     队首物理下标
__ls_que_q_tail     队尾物理下标（下一个可写位置）
__ls_que_q_count    当前元素个数
```

不变式：`tail == (head + count) % size`。

## 函数速查表

| 函数 | 参数 | 返回 | 说明 |
| --- | --- | --- | --- |
| `qpush(q, v)` | 队列, 值 | 新元素个数 | 满时返回当前个数且不写入 |
| `qpop(q)` | 队列 | 队首元素 | 空队列返回 NaN，并出队 |
| `qpeek(q)` | 队列 | 队首元素 | 空队列返回 NaN，不出队 |
| `qsize(q)` | 队列 | 元素个数 | O(1) |
| `qclear(q)` | 队列 | 0 | 清空 head/tail/count |

方法糖：`q.front()` / `q.peek()` 等价于 `qpeek(q)`，`q.size()` / `q.count()` 等价于 `qsize(q)`。

## 转译示例

### 大小

```text
queue q cell2 0 4
x = qsize(q)
```

产物：

```text
op add x __ls_que_q_count 0
```

### 看队首（带空队列守卫）

```text
x = qpeek(q)
```

产物：

```text
op lessThanEq _0 __ls_que_q_count 0
op add _1 0 __ls_que_q_head
op add _2 _1 1
op mul _0 _0 _2
op sub _1 _1 _0
read x cell2 _1
```

说明：先算 `base + head`；如果 `count <= 0`，用 `bump` 把地址拉到 `-1`，越界读返回 NaN。

### 出队

```text
x = qpop(q)
```

产物：

```text
op lessThanEq _0 __ls_que_q_count 0
op add _1 0 __ls_que_q_head
op add _2 _1 1
op mul _0 _0 _2
op sub _1 _1 _0
op min _3 __ls_que_q_count 1
op add _4 __ls_que_q_head _3
op mod __ls_que_q_head _4 4
op sub __ls_que_q_count __ls_que_q_count 1
op max __ls_que_q_count __ls_que_q_count 0
read x cell2 _1
```

出队时 `head = (head + min(count,1)) % size`，空队列保持不动；`count` 减 1 后取 `max(..., 0)`。

### 入队

```text
x = qpush(q, 7)
```

产物：

```text
funccall __ls_builtin_quepush "cell2, 0, 4, __ls_que_q_head, __ls_que_q_count, 7" __ls_que_q_count
op add __ls_que_q_tail __ls_que_q_head __ls_que_q_count
op mod __ls_que_q_tail __ls_que_q_tail 4
op add x __ls_que_q_count 0
```

`qpush` 的写内存、满队列分支都在 `__ls_builtin_quepush` 里；`tail` 由调用点按不变式更新。

## 复杂度

| 操作 | 复杂度 |
| --- | --- |
| `qpush` / `qpop` / `qpeek` / `qsize` / `qclear` | O(1) |

## 使用须知

- **空队列**：`qpop` / `qpeek` 返回 NaN，且 `head` / `count` 保持不变。
- **满队列**：`qpush` 返回当前 `size` 且不写入；不会覆盖已有元素。
- **环形回绕**：`head` 和 `tail` 用 `% size` 回绕；`tail` 只是缓存，真值始终是 `(head + count) % size`。
- **状态不持久化**：`head` / `tail` / `count` 是普通变量，处理器重载后回到 0。默认从空队列开始安全；残留旧数据时先 `qclear(q)`。
- **容量**：`base + size` 超容量编译期报错；队列区间 `[base, base+size)`。
- **内存区间重叠**：与其它结构重叠不会被自动拦截，需自己避免。

