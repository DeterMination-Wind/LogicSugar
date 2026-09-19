# 链表（chain）

> 返回 [教程目录](README.md) · 上一章 [小顶堆](heap.md)

## 什么时候用

需要自己管理节点和链接：邻接表、LRU、空闲节点池、复杂图结构。链表提供「值槽 + next 槽」的节点布局和一条空闲链，链接由你用 `chain_link` / `chain_set_head` 自己组织。

## 声明卡

```text
chain <name> <memory> <base> <size>
```

节点 i 占两个连续槽：

```text
value 槽：base + 2*i
next  槽：base + 2*i + 1      next = -1 表示链尾
占用区间：[base, base + 2*size)
```

隐藏状态：

```text
__ls_chn_c_head     链表头节点下标，-1 = 空链
__ls_chn_c_free     空闲链头，-1 = 无空闲节点
```

**首次使用前必须 `chain_init(c)` 或 `chain_clear(c)`**：未赋值变量读作 0，不初始化直接 `chain_alloc` 会把 0 号节点误认为空闲节点。

## 函数速查表

| 函数 | 参数 | 返回 | 说明 |
| --- | --- | --- | --- |
| `chain_init(c)` / `chain_clear(c)` | 链 | size（哨兵） | head = -1，空闲链重建为 0→1→…→size-1→-1 |
| `chain_alloc(c)` | 链 | 节点下标或 -1 | 从空闲链摘一个节点；空闲链空返回 -1 |
| `chain_free(c, i)` | 链, 下标 | 1 / 0 | 从链表摘掉节点 i 并挂回空闲链；非法下标返回 0 |
| `chain_get(c, i)` | 链, 下标 | 节点值或 NaN | 越界返回 NaN |
| `chain_set(c, i, v)` | 链, 下标, 值 | 1 / 0 | 越界不写入 |
| `chain_next(c, i)` | 链, 下标 | next 或 -1 | 越界返回 -1 |
| `chain_link(c, i, next)` | 链, 下标, 下一个 | 1 / 0 | 把 i 的 next 设为 next；next 可以是 -1（链尾） |
| `chain_set_head(c, i)` | 链, 下标 | 1 | 设置链表头；任何值都接受 |
| `chain_head(c)` | 链 | 头下标或 -1 | 读隐藏 head |
| `chain_len(c)` | 链 | 节点个数 | 从 head 遍历计数；空链 0 |

> 提示：编辑器菜单与积木显示的是新名字（如 `chain_init`）；旧短名（如 `cinit`）仍能解析已有存档，但新写的卡片一律用新名。

方法糖：`c[i]` / `c.get(i)` 等价于 `chain_get(c, i)`，`c.head()` 等价于 `chain_head(c)`，`c.next(i)` 等价于 `chain_next(c, i)`，`c.len()` / `c.length()` 等价于 `chain_len(c)`。

## 转译示例

### 初始化

```text
chain c cell1 2 4
x = chain_init(c)
```

产物：

```text
op sub __ls_chn_c_head 0 1
op add __ls_chn_c_free 0 0
funccall __ls_builtin_chninit "cell1, 2, 4" x
```

`__ls_builtin_chninit` 重建空闲链并返回 `size`；`head` / `free` 先写成 -1 / 0。

### 读取头下标

```text
x = chain_head(c)
```

产物：

```text
op add x __ls_chn_c_head 0
```

### 分配节点

```text
x = chain_alloc(c)
```

产物：

```text
op add _1 __ls_chn_c_free 0
funccall __ls_builtin_chnnew "cell1, 2, __ls_chn_c_free" __ls_chn_c_free
op add x _1 0
```

返回旧空闲链头（即新节点下标），并把 `free` 更新为下一个空闲节点。

### 读取节点值（带越界守卫）

```text
x = chain_get(c, i)
```

产物：

```text
op lessThan _1 i 0
op greaterThanEq _2 i 4
op or _3 _1 _2
op mul _4 i 2
op add _4 2 _4
op add _5 _4 1
op mul _3 _3 _5
op sub _4 _4 _3
read x cell1 _4
```

地址 = `base + 2*i`；越界时被拉到 `-1`，越界读返回 NaN。

### 其它操作

```text
x = chain_set(c, i, 5)     -> funccall __ls_builtin_chnset  "cell1, 2, 4, i, 5" x
x = chain_next(c, i)       -> funccall __ls_builtin_chnnext "cell1, 2, 4, i" x
x = chain_link(c, i, j)    -> funccall __ls_builtin_chnlink "cell1, 2, 4, i, j" x
x = chain_len(c)           -> funccall __ls_builtin_chnlen  "cell1, 2, __ls_chn_c_head" x
```

`chain_free` 会比较复杂：先算有效标志，函数摘链并返回新的头，调用点回写 `head` / `free` 并返回 1/0。

## 复杂度

| 操作 | 复杂度 | 说明 |
| --- | --- | --- |
| `chain_init` / `chain_clear` | O(size) | 重建整条空闲链 |
| `chain_alloc` | O(1) | 摘空闲链头 |
| `chain_free` | O(n) | 需要在 next 链上找 i 的前驱（i 是头时 O(1)） |
| `chain_get` / `chain_set` / `chain_next` / `chain_set_head` / `chain_head` / `chain_link` | O(1) | 直接读写 |
| `chain_len` | O(n) | 从头遍历计数 |

## 使用须知

- **必须先初始化**：`chain_init(c)` / `chain_clear(c)` 在首次使用前调用一次；处理器重载后 `head` / `free` 回到 0，需要重新初始化。
- **`chain_get` / `chain_set` 是按下标访问**：它们不跟随链表；要按链表顺序遍历请配合 `chain_head` + `chain_next`。
- **`chain_link` 不检查环**：把 next 指到已经在链上的节点会形成环；`chain_len` / `chain_free` 在环上不会终止。链表不变量由使用者维护。
- **`chain_free` 不检测重复释放**：把已经在空闲链上的节点再次 `chain_free` 会让空闲链成环；释放后不要再 `chain_free` 同一个节点。
- **节点与空闲链**：`chain_alloc` 分配、`chain_free` 回收；节点 i 的 `next` 槽在分配后会被置为 -1，接入链表要靠 `chain_link` / `chain_set_head`。
- **容量**：区间 `[base, base + 2*size)`；`base + 2*size` 超内存块容量编译期报错。
- **重名**：不能与其它链、数组/矩阵、用户函数、其它数据结构声明卡重名；跨模块区间重叠是已知限制。

