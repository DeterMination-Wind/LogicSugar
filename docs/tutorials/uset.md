# 无序集合（uset）

> 返回 [教程目录](README.md) · 上一章 [哈希表](map.md) · 下一章 [列表](list.md)

## 什么时候用

只关心「在不在」的数字集合：去重、已访问标记、可见性统计。它和哈希表共用一套开放寻址，但没有 value 区，内存占用减半。

## 声明卡

```text
uset <name> <memory> <base> <capacity>
```

- token 必须是 `uset`，不能写 `set`（那是原版 opcode）。
- 只占用 key 区 `[base, base + capacity)`。
- 哈希：`abs(key) % capacity`；线性探测；空槽标记 NaN；删除是墓碑。
- 首次使用前必须 `set_clear(s)`。

示例：`uset s cell1 0 4` 占用 `cell1` 地址 `0..3`。

## 函数速查表

| 函数 | 参数 | 返回 | 说明 |
| --- | --- | --- | --- |
| `set_add(s, v)` | 集合, 值 | 1 成功 / -1 失败 | 已存在也算成功；表满或键非法返回 -1 |
| `set_contains(s, v)` | 集合, 值 | 1 / 0 | 是否存在 |
| `set_remove(s, v)` | 集合, 值 | 1 / -1 | 删除；成员不存在返回 -1 |
| `set_size(s)` | 集合 | 元素个数 | O(capacity) 扫描 |
| `set_clear(s)` | 集合 | 0（实现哨兵） | 全部槽写成 NaN；O(capacity) |

> 提示：编辑器菜单与积木显示的是新名字（如 `set_add`）；旧短名（如 `uadd`）仍能解析已有存档，但新写的卡片一律用新名。

方法糖：`s.has(v)` / `s.contains(v)` 等价于 `set_contains(s, v)`，`s.size()` / `s.length()` / `s.count()` 等价于 `set_size(s)`。
## 转译示例

```text
uset s cell1 0 4
x = set_add(s, 1)
```

产物：

```text
funccall __ls_builtin_usetadd "cell1, 0, 4, 1" x
```

更多例子：

```text
x = set_contains(s, 1)    -> funccall __ls_builtin_usethas   "cell1, 0, 4, 1" x
x = set_remove(s, 1)      -> funccall __ls_builtin_usetdel   "cell1, 0, 4, 1" x
x = set_size(s)           -> funccall __ls_builtin_usetsize  "cell1, 0, 4" x
set_clear(s)              -> funccall __ls_builtin_usetclear "cell1, 0, 4" x
```

## 复杂度

| 操作 | 复杂度 | 说明 |
| --- | --- | --- |
| `set_contains` / `set_remove`（命中）与 `set_add`（已存在） | 平均接近 O(1)，最坏 O(capacity) | 从哈希位置起线性探测到该值 |
| `set_contains` / `set_remove`（未命中） | Θ(capacity) | 探测只在扫完整表（N ≥ capacity）时结束，空槽/墓碑都不能提前停 |
| `set_add`（新值） | Θ(capacity) | 必须扫完整表确认没有同值，并记住首个空槽后写入 |
| `set_size` | O(capacity) | 统计非空槽 |
| `set_clear` | O(capacity) | 写满整段 key 区 |

## 使用须知

- **必须初始化**：首次使用前 `set_clear(s)`。
- **只支持数字**：字符串不支持；比较沿用原版 `equal` 的约 1e-6 容差。
- **非法值**：NaN、+Inf、-Inf 会被拒绝：`set_add` 返回 -1，`set_contains` 返回 0，`set_remove` 返回 -1。
- **重复添加**：`set_add` 对已存在的值返回 1（不重复插入、不增加 `set_size`）。
- **表满**：`set_add` 返回 -1；删除产生的墓碑槽可以复用。
- **容量**：占用 `capacity` 个槽；`base + capacity` 超内存块容量时编译期报错。
- **初始化与持久化**：键存在内存块里、会随存档保留；没有隐藏状态变量。需要清空时显式 `set_clear(s)`，LogicSugar 不会自动初始化。
- **跨模块**：与其它集合、map、数组/矩阵、用户函数重名会报错；跨模块区间重叠是已知限制。

