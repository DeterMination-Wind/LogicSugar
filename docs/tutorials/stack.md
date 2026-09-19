# 栈（stack）

> 返回 [教程目录](README.md) · 上一章 [记录](record.md) · 下一章 [队列](queue.md)

## 什么时候用

后进先出：撤销/回退、深度优先搜索、括号匹配、临时保存中间结果。

## 声明卡

```text
stack <name> <memory> <base> <size>
```

| 字段 | 说明 |
| --- | --- |
| `name` | 栈名，如 `s` |
| `memory` | 内存块，如 `cell1` |
| `base` | 起始地址 |
| `size` | 最大元素个数（容量） |

示例：`stack s cell1 0 8` 使用 `cell1` 地址 `0..7`。

声明卡只是编译期元数据，不产出 mlog。栈的状态保存在隐藏变量 `__ls_stk_s_top`（元素个数）。

## 函数速查表

| 函数 | 参数 | 返回 | 说明 |
| --- | --- | --- | --- |
| `stack_push(s, v)` | 栈, 值 | 新元素个数 | 满时返回当前个数且不写入 |
| `stack_pop(s)` | 栈 | 栈顶元素 | 空栈返回 NaN，并弹出 |
| `stack_top(s)` | 栈 | 栈顶元素 | 空栈返回 NaN，不弹出 |
| `stack_size(s)` | 栈 | 元素个数 | O(1) |
| `stack_clear(s)` | 栈 | 0 | 清空（top = 0） |

> 提示：编辑器菜单与积木显示的是新名字（如 `stack_push`）；旧短名（如 `spush`）仍能解析已有存档，但新写的卡片一律用新名。

方法糖：`s.top()` / `s.peek()` 等价于 `stack_top(s)`，`s.size()` / `s.count()` 等价于 `stack_size(s)`。

## 转译示例

### 大小

```text
stack s cell1 0 8
x = stack_size(s)
```

产物：

```text
op add x __ls_stk_s_top 0
```

### 看栈顶（带空栈守卫）

```text
x = stack_top(s)
```

产物：

```text
op sub _0 __ls_stk_s_top 1
op add _1 0 _0
op lessThanEq _2 __ls_stk_s_top 0
op mul _2 _2 0
op sub _1 _1 _2
read x cell1 _1
```

说明：先算 `top - 1`；如果 `top <= 0`（空栈），用无分支乘法把地址改成 `-1`；越界 `read` 返回 NaN。

非零 base 时守卫会乘 `base`，把空栈地址从 `base - 1` 拉回 `-1`：

```text
stack s bank1 10 4
x = stack_top(s)
```

产物：

```text
op sub _0 __ls_stk_s_top 1
op add _1 10 _0
op lessThanEq _2 __ls_stk_s_top 0
op mul _2 _2 10
op sub _1 _1 _2
read x bank1 _1
```

### 入栈

```text
x = stack_push(s, 5)
```

产物：

```text
funccall __ls_builtin_stkpush "cell1, 0, 8, __ls_stk_s_top, 5" __ls_stk_s_top
op add x __ls_stk_s_top 0
```

`stack_push` 的写内存、满栈分支都在注入函数 `__ls_builtin_stkpush` 里；normal 模式全程序共享一份，未使用不进产物。

### 压入表达式

```text
x = stack_push(s, i + 1)
```

产物：

```text
op add _0 i 1
funccall __ls_builtin_stkpush "cell1, 0, 8, __ls_stk_s_top, _0" __ls_stk_s_top
op add x __ls_stk_s_top 0
```

实参先编译，再发 `funccall`。

## 复杂度

| 操作 | 复杂度 |
| --- | --- |
| `stack_push` / `stack_pop` / `stack_top` / `stack_size` / `stack_clear` | O(1) |

## 使用须知

- **空栈**：`stack_pop` / `stack_top` 返回 NaN；`stack_pop` 不会把 `top` 减到负数（`max(top-1, 0)`）。
- **满栈**：`stack_push` 返回当前 `size` 且不写入；不会覆盖已有元素。
- **LIFO**：`stack_pop` 读的地址是 `base + top - 1`，然后 `top` 减 1。
- **状态不持久化**：`__ls_stk_s_top` 是普通变量，处理器重载后回到 0。因为未赋值读作 0，栈默认从空开始是安全的；如果内存里还残留旧数据，先 `stack_clear(s)`。
- **容量**：`base + size` 超过内存块容量时编译期报错（`cellN` = 64，`bankN` / `worldN` = 512）。
- **内存区间**：与其它结构在同一内存块重叠不会被自动拦截；栈的区间是 `[base, base+size)`。
- **名字保留**：`s` 不能与其它数据结构或用户函数重名；`__ls_` 前缀不可用。

