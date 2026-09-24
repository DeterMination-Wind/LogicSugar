# LogicSugar 高级数据类型使用教程

面向已经会用 LogicSugar 的 if / for / while 和 Expr 表达式，但还不确定「这种数据该用哪个结构」的玩家。

每章只讲一种数据结构，包含：什么时候用、声明卡、函数速查表、转译后的 mlog 解释、复杂度、使用须知。所有示例都假设你已经知道怎么放积木、怎么切换 Original / Sugar 视图；示例里的 Sugar 源码行也可以直接当文本粘贴（`x = expr` 这类行会导入成 Expr 卡）。

> English version: [en/README.md](en/README.md)。先读 [README_zh.md](../../README_zh.md) 的「功能」了解基础；表达式语义和编译器细节见 [架构总览](../architecture.md) 的「表达式子系统」「数据子系统」。

## 章节导航

| 结构 | 一句话 | 典型操作 | 章节 |
| --- | --- | --- | --- |
| 一维数组 | 一段连续内存的随机访问容器 | `buf[i]`、`len(buf)` | [array.md](array.md) |
| 数组批量运算 | 对整段数组求值/变换 | `array_sum` `array_fill` `array_sort` `array_lower_bound` | [array-bulk.md](array-bulk.md) |
| 矩阵 | 行主序的二维数组 | `m[i][j]` | [matrix.md](matrix.md) |
| 记录 | 编译期的结构体（命名字段） | `p.hp`、`p.hp = x` | [record.md](record.md) |
| 栈 | 后进先出 | `stack_push` `stack_pop` `stack_top` | [stack.md](stack.md) |
| 队列 | 先进先出 | `queue_push` `queue_pop` `queue_front` | [queue.md](queue.md) |
| 双端队列 | 两端都能进出的环形缓冲 | `deque_push_front` `deque_pop_back` `deque_front` | [deque.md](deque.md) |
| 位集 | 大量布尔位的紧凑存储 | `bitset_set` `bitset_reset` `bitset_test` `bitset_count` | [bitset.md](bitset.md) |
| 哈希表 | 数字键到值的映射 | `map_set` `map_get` | [map.md](map.md) |
| 无序集合 | 只关心在不在的数字集合 | `set_add` `set_contains` `set_remove` | [uset.md](uset.md) |
| 列表 | 紧凑的顺序表，可按下标插入/删除 | `vector_push_back` `vector_at` `vector_erase` | [list.md](list.md) |
| 小顶堆 | 反复取最小值的优先队列 | `heap_push` `heap_pop` | [heap.md](heap.md) |
| 链表 | 空闲链加节点链接 | `chain_init` `chain_alloc` `chain_get` `chain_link` | [chain.md](chain.md) |

> 提示：编辑器菜单与积木显示的是新名字（如 `stack_push`）；旧短名（如 `spush`）仍能解析已有存档，但新写的卡片一律用新名。

不熟悉这些结构本身？先看下面的「选择指南」，再进入对应章节。

## 共同概念

### 声明卡只是编译期元数据

array / matrix / record / stack / queue / deque / bitset / map / uset / list / heap / chain 这些声明卡不产出任何 mlog 行。它们的作用是把「哪个名字对应哪块内存 / 哪些编译期信息」告诉 LogicSugar；真正写进处理器的只有 `op` / `read` / `write` / `jump` / `funccall` / `sensor` / `end` 等原版指令。

因此：

- 保存后的程序在任何原版客户端都能解析、运行；联机（含自建服）与单机一致。
- 重开处理器时声明卡通过 Sugar 载体恢复；纯原版 mlog（没有载体）不会凭空长出声明卡。
- 声明的名字、区间只在编译期存在，不占处理器运行时内存。

### 内存块、地址与容量

声明卡里的 `memory` 是承载数据的内存块变量名。容量优先看**这个变量实际链接到的方块**（在处理器里从方块用「链接」取变量名），拿不到处理器上下文时才按名字猜：

| 名字 | 例子 | 猜的容量（槽） | 备注 |
| --- | --- | --- | --- |
| `cellN` | `cell1` | 64 | 最常见；**world-cell 也叫 `cellN` 但实际 512 格**，此时以链接到的方块为准 |
| `bankN` | `bank1` | 512 | 大容量 |
| `worldN` | `world1` | 512 | 世界处理器可能受权限限制；原版几乎不会产生这种名字 |
| 其它名字 | `mem` | 不检查 | 名字猜不出容量，编译期跳过校验 |

`base` 是起始物理地址；`size` / `capacity` / `words` / `rows`×`cols` 是占用长度。`base + 占用长度` 超过容量时编译期报错。能解析到链接就以真实容量为准（所以 world-cell、模组内存块按它们的实际格子数判断）；只有完全解析不到链接时才按上表的猜测值检查，且错误信息会写明容量是 `inferred from the variable name`（推断值）。

数组/矩阵/记录/容器的所有内存读写都会落在这段区间内；不同模块之间的区间重叠不会被自动拦截（见每章的使用须知）。

### 名字与保留前缀

- 同一模块内名字必须唯一；不同结构之间由集成阶段尽量拦截，但跨模块区间重叠/重名属于已知限制。
- 名字不能与 array / matrix / 用户函数重名。
- `__ls_` 前缀保留给 LogicSugar 的隐藏状态变量和注入函数，用户声明名不能用。

### Expr 模式里的两种写法

同一个操作通常有两种等价写法：

```text
intrinsic 写法:  stack_top(s)      vector_at(list, i)     map_get(m, 1)
getter 糖写法:   s.top()           list[i] / list.get(i)
```

方法/下标糖覆盖只读 getter，包括走注入函数的 `map.get` / `set.has` / `vector_find` / `bitset_count` / `chain_next` / `chain_len`（见下文表格）。写入、push/pop/clear 这类操作仍用函数写法。

### 隐藏状态变量

栈、队列、双端队列、列表、堆、链表需要维护长度/头/尾等运行状态。LogicSugar 把它们放在普通 mlog 变量里，统一用 `__ls_` 前缀：

```text
stack s        -> __ls_stk_s_top
queue q        -> __ls_que_q_head / _tail / _count
deque d        -> __ls_deq_d_head / _tail / _count
list l         -> __ls_lst_l_count
heap h         -> __ls_hep_h_count
chain c        -> __ls_chn_c_head / _free
```

这些变量在游戏变量列表里默认被隐藏（隐藏 `__ls_*` 内部变量设置），但你仍然可以在 Original 视图里看到它们。

### 状态不随存档持久化

隐藏状态变量是普通处理器变量，重新载入处理器 / 存档往返 / 换一个处理器之后会回到未赋值等于 0，而内存块里的内容仍在。因此：

- 栈 / 队列 / 双端队列 / 列表 / 堆：默认从长度 0 开始是安全的；如果内存里还有旧数据，记得先 stack_clear / queue_clear / deque_clear 或自己重设计数。
- 哈希表 / 集合 / 链表：未赋值状态会被误读成有效数据（0 号槽 / 0 号节点），首次使用前必须显式 map_clear(m) / set_clear(s) / chain_init(c)。
- map / uset 没有隐藏计数，键值在内存里会随存档保留，但仍需要显式初始化。

### 复杂度和指令预算

处理器的硬限制是 1000 条指令。复杂度直接决定会不会撞上限：

| 复杂度 | 含义 | 例子 |
| --- | --- | --- |
| O(1) | 固定几条指令 | `vector_at`、`stack_top`、`queue_push`、`bitset_test` |
| O(log n) | 每次减半/树高 | `heap_push`、`heap_pop` |
| O(n) | 遍历全部元素 | `vector_find`、`vector_insert`、`vector_erase`、`chain_len` |
| O(n^1.5) ~ O(n²) | 希尔排序 | `array_sort` / `array_sort_desc` |
| O(capacity) | 扫描整张表 / 整段内存 | `map_size`、`map_clear`、`set_size`、`set_clear`、`chain_init` |

> `map_get` / `map_contains` / `set_add` / `set_contains` 的**命中**平均接近 O(1)；**未命中与新键插入恒为 Θ(capacity)**——探测不能在空槽处提前停止，必须扫整张表。

> 循环型算法（哈希探测、排序、堆调整、链表遍历等）在 normal 模式下编译成全程序共享的一份 `__ls_builtin_*` 子程序；inline 模式会在每个调用点复制函数体，长程序要留意 1000 条限制。

### 方法糖与下标糖

| 结构 | 可用写法 | 等价于 |
| --- | --- | --- |
| list | `l[i]`、`l.get(i)` | `vector_at(l, i)` |
| list | `l.find(v)` / `l.indexOf(v)` | `vector_find(l, v)` |
| list / heap | `l.size()` / `l.length()` / `l.count()`、`h.size()` | `vector_size(l)` / `heap_size(h)` |
| stack | `s.top()`、`s.peek()`、`s.size()` / `s.count()` | `stack_top(s)` / `stack_size(s)` |
| queue | `q.front()`、`q.peek()`、`q.size()` / `q.count()` | `queue_front(q)` / `queue_size(q)` |
| deque | `d.front()`、`d.back()`、`d.size()` / `d.count()` | `deque_front(d)` / `deque_back(d)` / `deque_size(d)` |
| bitset | `b[i]`、`b.test(i)`、`b.get(i)`、`b.count()` | `bitset_test(b, i)` / `bitset_count(b)` |
| chain | `c[i]`、`c.get(i)`、`c.head()`、`c.next(i)`、`c.len()` | `chain_get(c, i)` / `chain_head(c)` / `chain_next(c, i)` / `chain_len(c)` |
| map | `m[k]`、`m.get(k)`、`m.has(k)`、`m.size()` | `map_get(m, k)` / `map_contains(m, k)` / `map_size(m)` |
| uset | `s.has(v)`、`s.size()` | `set_contains(s, v)` / `set_size(s)` |

注意：

- 已有同名的 array / matrix 时，`x[i]` 优先按数组解释。
- 下标糖目前只读：`l[i] = v` 会直接报错，请写 `vector_set(l, i, v)` / `bitset_set(b, i)` / `chain_set(c, i, v)`。
- `map` / `uset` 的方法糖接收者名必须与声明卡一致；例如 `uset s ...` 时写 `s.has(v)`。

## 选择指南

| 你想要 | 用 |
| --- | --- |
| 按下标随机读写一段数字 | [array.md](array.md) |
| 对整段数组求和、排序、查找 | [array-bulk.md](array-bulk.md) |
| 二维表（网格、地图、矩阵乘法） | [matrix.md](matrix.md) |
| 把几个相关的值打包成一个对象 | [record.md](record.md) |
| 后进先出（撤销、DFS、括号匹配） | [stack.md](stack.md) |
| 先进先出（任务队列、BFS） | [queue.md](queue.md) |
| 两端进出（滑动窗口、双端 BFS） | [deque.md](deque.md) |
| 很多开关 / 访问标记 | [bitset.md](bitset.md) |
| 数字键查表（计数器、映射） | [map.md](map.md) |
| 去重 / 存在性判断 | [uset.md](uset.md) |
| 顺序表，中间要插入/删除 | [list.md](list.md) |
| 反复取最小值（Dijkstra、合并） | [heap.md](heap.md) |
| 自己管理节点与链接（邻接表、LRU） | [chain.md](chain.md) |

## 怎么自己验证转译结果

1. 在编辑器里放声明卡加操作卡（或写 Expr 表达式）。
2. 保存一次，切换到 Original 视图，对照本章的转译示例看指令。
3. 需要确认没有模组也能跑时，用复制编译后代码导出纯原版 mlog，在普通客户端里导入。

## 后续计划

- 方法糖已覆盖走注入函数的 `map_get` / `set_contains` / `vector_find` / `bitset_count` / `chain_next` / `chain_len`（analyze 阶段先做声明预扫描，再补注入函数可达性）。暂不提供 mutator 方法糖（`s.push()` / `l.append()` / `m.set()` 等）。
- 数组的 `buf[i] = x` 已经支持；list / bitset / chain 的下标赋值暂不支持。

## 相关文档

- [架构总览](../architecture.md)：编译器、表达式子系统、数据子系统的实现细节。
- [术语表](../glossary.md)：intrinsic、注入函数、载体、隐藏状态变量等名词。
- [测试指南](../testing.md)：每个数据结构对应的自测任务和手测清单。
- [README_zh.md](../../README_zh.md)：功能总览与安装。

