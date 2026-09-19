# 哈希表（map）

> 返回 [教程目录](README.md) · 上一章 [位集](bitset.md) · 下一章 [无序集合](uset.md)

## 什么时候用

数字键到值的映射：计数器、查找表、字典、按 id 存状态。命中通常只需少量探测；未命中与新键插入必须扫完整表（探测不能在空槽处停下：墓碑之后可能还有同 key 的项），需要先初始化。

## 声明卡

```text
map <name> <memory> <base> <capacity>
```

布局：

```text
key 区：  [base, base + capacity)
value 区：[base + capacity, base + 2*capacity)
```

- 哈希：`hash = abs(key) % capacity`
- 探测：线性探测，整表环形扫描
- 空槽标记：NaN
- 删除：把 key 槽写成 NaN（墓碑），不搬移、不回填

示例：`map m cell1 0 4` 占用 `cell1` 地址 `0..7`（4 个 key + 4 个 value）。

**首次使用前必须 `map_clear(m)`**：未初始化的内存槽读回数字 0，会被当成「已占用且 key = 0」。

## 函数速查表

| 函数 | 参数 | 返回 | 说明 |
| --- | --- | --- | --- |
| `map_set(m, k, v)` | 表, 键, 值 | 1 成功 / -1 失败 | 插入或更新；表满或键非法返回 -1 |
| `map_get(m, k)` | 表, 键 | 值或 NaN | 未命中返回 NaN |
| `map_contains(m, k)` | 表, 键 | 1 / 0 | 键是否存在 |
| `map_erase(m, k)` | 表, 键 | 1 / -1 | 删除；键不存在返回 -1 |
| `map_size(m)` | 表 | 非空 key 数 | O(capacity) 扫描 |
| `map_clear(m)` | 表 | 0（实现哨兵） | 把所有 key 槽写成 NaN；O(capacity) |

> 提示：编辑器菜单与积木显示的是新名字（如 `map_set`）；旧短名（如 `mapset`）仍能解析已有存档，但新写的卡片一律用新名。

方法糖：`m[k]` / `m.get(k)` 等价于 `map_get(m, k)`，`m.has(k)` / `m.containsKey(k)` 等价于 `map_contains(m, k)`，`m.size()` / `m.length()` / `m.count()` 等价于 `map_size(m)`。
## 转译示例

所有操作都编译成对注入函数 `__ls_builtin_map*` 的 `funccall`，参数是内存块名与字面量 base/capacity。

```text
map m cell1 0 4
x = map_set(m, 1, 10)
```

产物：

```text
funccall __ls_builtin_mapset "cell1, 0, 4, 1, 10" x
```

更多例子：

```text
x = map_get(m, 1)         -> funccall __ls_builtin_mapget   "cell1, 0, 4, 1" x
x = map_contains(m, 1)    -> funccall __ls_builtin_maphas   "cell1, 0, 4, 1" x
x = map_erase(m, 1)       -> funccall __ls_builtin_mapdel   "cell1, 0, 4, 1" x
x = map_size(m)           -> funccall __ls_builtin_mapsize  "cell1, 0, 4" x
map_clear(m)              -> funccall __ls_builtin_mapclear "cell1, 0, 4" x
```

实参是表达式时先编译实参：

```text
x = map_set(m, 3, a + 1)
```

产物：

```text
op add _0 a 1
funccall __ls_builtin_mapset "cell1, 0, 4, 3, _0" x
```

normal 模式下每个注入函数全程序共享一份；未使用的不会进入产物。

## 复杂度

| 操作 | 复杂度 | 说明 |
| --- | --- | --- |
| `map_get` / `map_contains` / `map_erase`（命中） | 平均接近 O(1)，最坏 O(capacity) | 从哈希位置起线性探测到该 key |
| `map_get` / `map_contains` / `map_erase`（未命中） | Θ(capacity) | 探测只在扫完整表（N ≥ capacity）时结束，空槽/墓碑都不能提前停 |
| `map_set`（更新已有 key） | 平均接近 O(1)，最坏 O(capacity) | 探测到同 key 即更新 |
| `map_set`（插入新 key） | Θ(capacity) | 必须扫完整表确认没有同 key，并记住首个空槽后写入 |
| `map_size` | O(capacity) | 统计非空 key 个数 |
| `map_clear` | O(capacity) | 写满整段 key 区 |

## 使用须知

- **必须初始化**：首次使用前 `map_clear(m)`；否则数字 0 的槽会被当成 key = 0 的有效项。
- **只支持数字键**：字符串键不支持；键比较沿用原版 `equal`（约 1e-6 容差），所以 1 和 1.0000001 可能被当作同一个键。
- **非法键**：NaN、+Inf、-Inf 会被拒绝：`map_set` 返回 -1，`map_get` 返回 NaN，`map_contains` 返回 0，`map_erase` 返回 -1。
- **表满**：`map_set` 返回 -1，不覆盖已有键；删除产生的墓碑槽可以复用。
- **值经函数返回值回传**：对象值（单位、建筑等）在注入函数返回值通道会退化为 1/0；需要存对象请存 id/坐标等数字。
- **容量**：占用 `2 * capacity` 个槽；`base + 2*capacity` 超内存块容量时编译期报错。
- **名字与区间**：不能与其它 map、集合、数组/矩阵、用户函数重名；同一内存块上多个 map/区间互不重叠；跨模块重叠不会被自动拦截。
- **初始化与持久化**：map 没有隐藏计数变量，键和值都在内存块里，会随存档/处理器重载保留。LogicSugar 不会自动初始化；首次使用前以及需要清空时显式 `map_clear(m)`。

