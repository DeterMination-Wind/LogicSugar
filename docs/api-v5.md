# v5 API 契约（logic-sugar-v2）

本文是 LogicSugar **v5 API** 的权威说明：持久化标记、值语义、失败信号、无结果卡与函数返回声明，以及老存档如何继续通过恢复验证门。实现结构见[架构总览](architecture.md)，玩家向改动见 [`release_notes_v5.0.0.md`](../release_notes_v5.0.0.md)。

## 1. 持久化标记：`logic-sugar-v2`

- 新保存的程序除了 `set __ls_sugar "…"` 载体，还会写一段源标记块：`# @logic-sugar-v2 begin` / `# @logic-sugar-line …` / `# @logic-sugar-v2 end`。标记块会被原版 parse/save 往返丢弃，因此**程序末尾的载体才是唯一可靠的源码来源**，标记只用于标识格式版本。
- `SugarCompiler.storedFormat(code)` 返回：`2` = 当前 v5 存档，`1` = v1 标记（2.0.0 时代的注释块），`0` = 标记已被原版往返冲掉（只剩载体）。
- 读取端（`stripMarkers`、`restore`、`SugarDecompiler.stripMetadata`）通过 `isMarkerBeginLine`/`isMarkerEndLine` 同时接受 v1/v2 标记对。

## 2. 值拷贝：`set`，不是 `op add d s 0`

旧 lowering 用 `op add <dst> <src> 0` 表示「拷贝」。原版 `op` 通过 `LVar.num()` 读操作数，会把**单位 / 建筑 / 内容 / 字符串 / 链接折成 1**，把**空值（未赋值或 NaN 标记 = 对象变量 + null 载荷）折成 0**，所以这类拷贝会静默丢值。

v5 起所有「值拷贝」都写成 `set`：

| 场景 | v5 写法 |
| --- | --- |
| 表达式卡 / `return` 的纯变量结果 | `set <dest> <src>` |
| 函数实参物化（`_0` 槽） | `set _0 <arg>` |
| 记录字段读 / 写 | `set <tmp> p_f1` / `set p_f1 <value>` |
| 堆顶取值（`heap_pop`） | `set __ls_lh_r __ls_lh_min` |

注意区分「拷贝」与「取值」：**空容器的取值仍然是 NaN 标记**（`stack_pop`/`stack_top`/`queue_pop`/`queue_front`/`deque_pop_front`/`deque_pop_back`/`deque_front`/`deque_back`/`vector_at`/`chain_get`、哈希/集合未命中、空的 `heap_pop`/`vector_erase`）。NaN 表示「没有值」，-1 表示「操作失败」，两者不混用。

## 3. 失败信号统一为 -1

一次数据操作有三种结果：成功、失败、没有值。v5 把**可失败操作**的失败信号统一成 `-1`（旧版有的是 `0`，有的返回原容量/原计数）。

| 操作 | 成功 | 失败（v5） | 旧版失败值 |
| --- | --- | --- | --- |
| `stack_push` / `queue_push` / `deque_push_back` / `deque_push_front` | 新元素个数 | `-1` | 原容量 / 旧头 |
| `vector_push_back` | 新元素个数 | `-1` | 原计数 |
| `heap_push` / `vector_insert` | `1` | `-1` | `0` |
| `vector_set` / `chain_set` / `chain_link` | `1` | `-1`（越界） | `0` |
| `chain_free` | `1` | `-1`（非法下标） | `0` |
| `map_erase` / `set_remove` | `1` | `-1`（键不存在） | `0` |
| `map_set` | `1` | `-1`（满表 / 非法键） | 已是 `-1` |
| `set_add` | `1` | `-1`（满表 / 非法键） | 已是 `-1` |
| `chain_alloc` | 新节点下标 | `-1`（空闲链耗尽） | 已是 `-1` |
| `chain_next` | 下个下标 | `-1`（非法下标 / 链尾） | 已是 `-1` |
| `vector_find` | 首个匹配下标 | `-1`（未找到） | 已是 `-1` |

**查询类仍是 0/1**：`map_contains`、`set_contains`、`bitset_test`。特别地 `bitset_test` 越界仍返回 `0`（它是「这一位是否置位」的查询，而 mlog 里 `-1` 是真值，返回 -1 会让 `if bitset_test(...)` 把越界误判成置位）。

## 4. 无结果卡：`~`

结果恒定、没有信息量的操作不暴露目标变量，卡片可以写 `~`：

- `bitset_set` / `bitset_reset`（共用写回子程序，恒返回 1，越界也返回 1）
- `chain_set_head`（恒返回 1）

另外 `array_fill`/`array_copy`/`array_sort`/`array_sort_desc`/`array_reverse`/`array_swap`/`stack_clear`/`queue_clear`/`deque_clear`/`map_clear`/`set_clear` 本来就是无结果操作。

**兼容细节**：无结果卡在新存档里写 `~`；旧存档如果已经带了目标变量，`emitDataCall` 仍然写进那个变量，因此**旧存档的指令流逐字节不变**，仍然过验证门。表达式形式（如 `x = bset(bs, i)`）保留旧的结果操作数以兼容老源码。

## 5. 函数返回声明

任意函数（含玩家函数）可以显式声明返回值，写在其参数之后、`destIndex` 之前：

```
funcdef f a ~ 3        ~ = void：函数体不得出现 return <表达式>
funcdef f a value 3    value = 必须至少值返回一次
funcdef f a 3          旧三 token 形态：按函数体推断（不变）
```

- 声明别名：`~` / `void` / `none` → `~`；`value` / `val` → `value`。
- 判别方式：第三个槽是整数（`destIndex`）即为旧形态，否则是声明 —— 因此**旧存档字节不变**，也避免 LParser 复用 token 数组带来的陈旧 token 问题。
- 分析期校验：`~` 函数体内 `return <表达式>` 报错；对 `~` 函数写 `funccall f "1" r` 报错「declared void (~)」；`value` 函数从未值返回报错。
- 调用点：`expandCall` **不会**为 void 函数生成结果拷贝（否则调用方会读到陈旧的 `_result`）。
- `funcdef` 只存在于 Sugar 源码（载体 / 函数库）中，不会进入编译产物，因此不影响原版客户端与联机。

## 6. 老存档如何继续工作

`verifyRestore` 先在**当前 v5 lowering**下重编译恢复出的源码并逐字比对存储流；不一致时进入 `Api.v1`（v5 之前的 lowering）再比对一次，**只有精确匹配才接受载体**：

- 纯写法差异（`op add d s 0` ↔ `set d s`）由 `executableStream` 的 `canonicalizeCopies` 归一化，不需要 legacy 通道；
- 失败值/无结果卡这类**语义差异**由 legacy 通道复现，老存档行为可复现；
- 两者都不匹配（例如程序被外部改过）仍然回退纯原版，验证门没有被放宽。

## 7. 迁移清单（v4 → v5）

1. 需要判断结果的代码请按第 3 节改判失败：老代码写 `if 结果 == 0` 的失败分支要改成 `== -1`（成功分支 `== 1` 或非零判断通常可保留）。
2. `bitset_set`/`bitset_reset`/`chain_set_head` 卡片的目标变量可以清空写 `~`；老卡片保留目标变量仍然能用。
3. 需要明确「这个函数有/没有返回值」时给 `funcdef` 加 `~` 或 `value`；不写就沿用旧行为（按函数体推断）。
4. 依赖「空容器取值为 0」的代码一直是错的：空容器返回 NaN 标记，请用 `size`/`has` 先判断。
5. 老程序不需要手动迁移：重新保存时标记会升级为 `logic-sugar-v2`，载体里的源码保持原样。
