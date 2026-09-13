# 小顶堆（heap）

> 返回 [教程目录](README.md) · 上一章 [列表](list.md) · 下一章 [链表](chain.md)

## 什么时候用

反复取最小值：Dijkstra、合并有序序列、优先队列、Top-K 的前 K 小。

## 声明卡

```text
heap <name> <memory> <base> <size>
```

示例：`heap h cell2 4 8` 使用 `cell2` 地址 `4..11`，最多 8 个元素。

小顶堆用数组实现，状态是隐藏变量 `__ls_hep_h_count`（元素个数）。未赋值读作 0，默认从空堆开始安全。

## 函数速查表

| 函数 | 参数 | 返回 | 说明 |
| --- | --- | --- | --- |
| `hpush(h, v)` | 堆, 值 | 1 成功 / 0 失败 | 满时返回 0 且不写入 |
| `hpop(h)` | 堆 | 最小值或 NaN | 空堆返回 NaN；会弹出 |
| `hsize(h)` | 堆 | 元素个数 | O(1) |

方法糖：`h.size()` / `h.length()` / `h.count()` 等价于 `hsize(h)`。

没有「只看不弹」的 peek；如果需要保留最小值，可以在 `hpop` 后立刻把值 `hpush` 回去（O(log n)）。

## 转译示例

### 大小

```text
heap h cell2 4 8
x = hsize(h)
```

产物：

```text
op add x __ls_hep_h_count 0
```

### 入堆

```text
x = hpush(h, 5)
```

产物：

```text
op add _0 __ls_hep_h_count 0
funccall __ls_builtin_heppush "cell2, 4, 8, __ls_hep_h_count, 5" __ls_hep_h_count
op notEqual _1 __ls_hep_h_count _0
op add x _1 0
```

`heppush` 返回新的 `count`（满时返回旧 `count`），调用点用「count 是否变化」换算成 1/0。

### 弹出最小值

```text
x = hpop(h)
```

产物：

```text
op add _0 __ls_hep_h_count 0
op greaterThan _1 _0 0
op sub __ls_hep_h_count _0 _1
funccall __ls_builtin_heppop "cell2, 4, 8, _0" x
```

调用前先把 `count` 减 1（空堆时不减），`heppop` 返回最小值；空堆时返回 NaN，NaN 不会被后续 `op` 归零。

## 复杂度

| 操作 | 复杂度 | 说明 |
| --- | --- | --- |
| `hpush` / `hpop` | O(log n) | 堆高为 log n，向上/向下调整 |
| `hsize` | O(1) | 读计数变量 |

## 使用须知

- **小顶堆**：`hpop` 返回当前最小值。想取最大值请存相反数。
- **没有 peek**：`hpop` 是破坏性的；`hsize` 只看数量。
- **空堆/满堆**：`hpop` 空堆返回 NaN；`hpush` 满堆返回 0 且不写入。
- **状态不持久化**：`__ls_hep_h_count` 在处理器重载后回到 0；堆内元素还在内存里但不会被当作有效元素。需要清空时把计数归零，空堆默认安全。
- **容量**：区间 `[base, base+size)`；`base + size` 超内存块容量编译期报错。
- **重复值**：允许重复；`hpop` 每次弹出其中一个最小值，顺序不保证。

