# 位集（bitset）

> 返回 [教程目录](README.md) · 上一章 [双端队列](deque.md) · 下一章 [哈希表](map.md)

## 什么时候用

大量布尔标记：已访问集合、开关数组、筛法、每单位/每格的状态位。一个内存槽 64 位，`words` 个槽就是 `words*64` 个布尔位。

## 声明卡

```text
bitset <name> <memory> <base> <words>
```

示例：`bitset bs cell1 0 2` 占用 `cell1` 地址 `0..1`，共 128 位。

位下标 `i` 的换算：

```text
word = i / 64        （整数除法）
bit  = i % 64
mask = 1 << bit
地址 = base + word
```

第 0 位是 0 号槽的最低位（LSB）。

## 函数速查表

| 函数 | 参数 | 返回 | 说明 |
| --- | --- | --- | --- |
| `bitset_set(b, i)` | 位集, 位下标 | 1 成功 / 0 越界 | 置 1；越界不写入 |
| `bitset_reset(b, i)` | 位集, 位下标 | 1 成功 / 0 越界 | 清 0；越界不写入 |
| `bitset_test(b, i)` | 位集, 位下标 | 1 / 0 | 读该位；越界返回 0 |
| `bitset_count(b)` | 位集 | 置 1 的位数 | 扫描全部 words；O(words) |

> 提示：编辑器菜单与积木显示的是新名字（如 `bitset_set`）；旧短名（如 `bset`）仍能解析已有存档，但新写的卡片一律用新名。

方法糖：`b[i]`、`b.test(i)`、`b.get(i)` 等价于 `bitset_test(b, i)`，`b.count()` 等价于 `bitset_count(b)`。

## 转译示例

### 置位

```text
bitset bs cell1 0 2
x = bitset_set(bs, i)
```

产物：

```text
op idiv _0 i 64
op mod _1 i 64
op shl _2 1 _1
op greaterThanEq _3 i 0
op lessThan _4 i 128
op land _5 _3 _4
op mul _2 _2 _5
op mul _0 _0 _5
op add _6 0 _0
read _7 cell1 _6
op or _7 _7 _2
funccall __ls_builtin_bwrite "cell1, _6, _7" x
```

说明：

- `_0` 是 word、`_1` 是 bit、`_2` 是 mask；
- `_3/_4/_5` 是无分支越界守卫；越界时 mask 和 word 被乘 0，读改写回等价于空操作，返回 0；
- 写回通过共享注入函数 `__ls_builtin_bwrite`（函数体是一条 `write`，返回 1）。

### 清位

`bitset_reset(bs, i)` 与 `bitset_set` 相同，只是把 `op or` 换成先取反再 `op and`：

```text
op not _8 _2 0
op and _7 _7 _8
funccall __ls_builtin_bwrite "cell1, _6, _7" x
```

### 测试位

`bitset_test(bs, i)` 的前半段与上面相同，最后是：

```text
op and _8 _7 _2
op notEqual x _8 0
```

### 计数

```text
x = bitset_count(bs)
```

产物：

```text
funccall __ls_builtin_bitcount "cell1, 0, 2" x
```

`bitset_count` 在注入函数里逐 word 统计置位数；normal 模式全程序共享一份。

## 复杂度

| 操作 | 复杂度 | 说明 |
| --- | --- | --- |
| `bitset_set` / `bitset_reset` / `bitset_test` | O(1) | 固定几条 `op`/`read`/`funccall` |
| `bitset_count` | O(words) | 每个内存槽一次循环 |

## 使用须知

- **容量**：`base + words` 不能超过内存块容量（`cellN` = 64，`bankN` / `worldN` = 512）。
- **越界语义**：`bitset_set` / `bitset_reset` 越界返回 0 且不写入；`bitset_test` 越界返回 0。位下标是负数或 `>= words*64` 都属于越界。
- **位序**：word 内 `bit = i % 64`，第 0 位对应 `1 << 0`；跨 word 的大端/小端由你自己的读写顺序决定，LogicSugar 只保证公式一致。
- **`bitset_count` 频率**：它是 O(words) 的循环，放在每 tick 都执行的条件里会明显增加处理器负载；尽量只在需要时统计，或自己维护计数。
- **内存区间**：与其它结构重叠不会自动拦截；位集区间是 `[base, base+words)`。

