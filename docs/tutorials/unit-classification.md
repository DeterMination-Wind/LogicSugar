# 单位分类（unit_family / unit_tier / unit_planet）

> 返回 [教程目录](README.md) · 相关 [架构总览](../architecture.md)「表达式子系统」

## 什么时候用

需要按「这个单位属于哪个工厂 / 科技树家族、几级、来自哪个星球」做判断时：

- `unitfor` / `unitbind` 遍历到单位后，只处理某一族（例如只给空军队列下指令）。
- 按等级决定升级目标、技能或资源分配。
- 区分 Serpulo / Erekir 两套单位表，避免把 Erekir 单位塞进 Serpulo 的生产线。

三个 getter 都是**表达式内建**（compile-time intrinsic），不是原版传感器：编译期展开成普通
`sensor` / `op` 指令，保存产物是纯原版 mlog，联机与单机行为一致。它们也不是成员糖，实参必须是**单位**；
传单位类型或建筑没有意义（游戏没有运行时「这是单位类型」的传感器，建筑实例甚至会因为方块 logic id
与单位共用编号而返回一个看似合理的非零值）。请只传 `unitbind` / `unitfor` 得到的单位。

## 快速示例

```text
f = unit_family(u)          # 0..8
t = unit_tier(u)            # 1..5，0 = 未知
p = unit_planet(u)          # 1 Serpulo / 2 Erekir，0 = 未知
```

条件卡切到 Expr 模式（源码里是 `expr "..."`）后，可以直接写：

```text
unit_family(u) == @__AIR__
unit_tier(u) == @__T5__
unit_planet(u) == @__EREKIR__
```

一条完整例子：把上面的条件填进 `if` 条件卡的 **Expr 模式**即可，不需要手写结构源码。

## 家族（unit_family）

| 值 | 常量 | 家族 | 原版 logic id | 例子（v160） |
| --- | --- | --- | --- | --- |
| 0 | `@__UNKNOWN__` | 未知 | — | 模组单位、未来单位、`null` |
| 1 | `@__GROUND__` | Serpulo 地面工厂 | 0..14 | `dagger..reign`（攻击）、`nova..corvus`（支援）、`crawler..toxopid`（爬虫） |
| 2 | `@__AIR__` | Serpulo 空军工厂 | 15..24 | `flare..eclipse`（攻击）、`mono..oct`（支援） |
| 3 | `@__NAVAL__` | Serpulo 海军工厂 | 25..34 | `risso..omura`（攻击）、`retusa..navanax`（支援） |
| 8 | `@__CORE__` | 核心单位 | 35..37 | alpha、beta、gamma |
| 4 | `@__TANK__` | Erekir 坦克制造机 | 38..42 | stell、locus、precept、vanquish、conquer |
| 5 | `@__MECH__` | Erekir 机甲制造机 | 43..47 | merui、cleroi、anthicus、tecta、collaris |
| 6 | `@__SHIP__` | Erekir 舰船制造机 | 48..52 | elude、avert、obviate、quell、disrupt |
| 7 | `@__SUPPORT__` | Erekir 核心支援 | 53..55 | evoke、incite、emanate |

「家族」指原版**工厂 / 科技树家族**，不是移动方式。同一家族里的每 5 个连续 id 是一条独立的
T1..T5 产线（地面家族有攻击、支援、爬虫三条；空军与海军各有两条），所以 `unit_family` + `unit_tier`
不能唯一确定兵种——例如 `@__GROUND__` + `@__T5__` 同时命中 reign、corvus 和 toxopid。需要具体单位时
再用 `u.type.Name` / `u.type.id` 区分。

## 等级（unit_tier）

- Serpulo：`id % 5 + 1`（0..37）。
- Erekir：`(id - 38) % 5 + 1`（38..55）。
- 未知 / 模组 / 越界：`0`。

等级是**家族内每条产线的科技等级**，不是全局强度排名。`@__T1__`..`@__T5__` 就是 1..5；`0` 只表示未知，
不要把它当成「0 级单位」。

## 星球（unit_planet）

| 值 | 常量 | 范围 |
| --- | --- | --- |
| 1 | `@__SERPULO__` | logic id 0..37 |
| 2 | `@__EREKIR__` | logic id 38..55 |
| 0 | `@__UNKNOWN__` | 其它（模组 / 未来 / `null`） |

它看的是**单位类型的 logic id**，不是单位当前站在哪颗星球上。同一个单位类型换地图不会改变结果。

## 常量表

常量大小写不敏感，统一使用逻辑糖保留的 `@__` 前缀：

| 常量 | 值 | 用途 |
| --- | --- | --- |
| `@__UNKNOWN__` | 0 | 未知家族 / 星球 / 等级 |
| `@__GROUND__` | 1 | 地面家族 |
| `@__AIR__` | 2 | 空军家族 |
| `@__NAVAL__` | 3 | 海军家族 |
| `@__TANK__` | 4 | 坦克家族 |
| `@__MECH__` | 5 | 机甲家族 |
| `@__SHIP__` | 6 | 舰船家族 |
| `@__SUPPORT__` | 7 | 支援家族 |
| `@__CORE__` | 8 | 核心家族 |
| `@__T1__` .. `@__T5__` | 1..5 | 单位等级 |
| `@__SERPULO__` | 1 | Serpulo |
| `@__EREKIR__` | 2 | Erekir |
| `@__EPS__` | `1e-6` | 原版比较容差 |
| `@__TILESIZE__` | 8 | 一格的世界单位长度 |
| `@__TICKS_PER_SECOND__` | 60 | 每秒 tick 数 |
| `@__SECONDS_PER_TICK__` | `1/60` | 每 tick 秒数 |

常量在编译期替换成数字，不占运行时变量。未登记的 `@__...` 名字是编译错误；大小写不敏感，
`@__air__` 与 `@__AIR__` 等价。

> 跨版本分享：`@__` 常量和三个 getter 只存在于糖源码 / 表达式条件里。把它们复制给不支持本功能的旧版
> LogicSugar 时，旧版会把 `@__` 当成普通变量（函数调用则可能报未知函数）；编译后的处理器产物是纯原版
> mlog，不受影响。分享产物没问题，分享糖源码时请确认对方版本。

## 家族 ≠ 移动方式

不要用 `@flying` / `@naval` / `u.type.Flying` 反推家族，Erekir 上两者对不上：

- **elude** 是 `@__SHIP__` 家族，但它是**地面**单位，不会飞。
- **avert / obviate / quell / disrupt** 也是 `@__SHIP__` 家族，却会飞。
- **evoke / incite / emanate** 是 `@__SUPPORT__` 家族，会飞。

所以：

- 要判断「能不能飞」→ 用 `u.type.Flying`。
- 要判断「哪个工厂造出来的 / 哪条科技树」→ 用 `unit_family(u)`。

## 成本与缓存

| 调用 | 约指令数 | 说明 |
| --- | --- | --- |
| `unit_family(u)` | 约 24 | 两次 `sensor`（`@type`、`@id`）+ 范围阶梯 + `null` / 越界守卫 |
| `unit_tier(u)` | 约 14 | 两次 `sensor` + 取模 + 非负/越界/`null` 守卫 |
| `unit_planet(u)` | 约 11 | 两次 `sensor` + 范围判断 + 守卫 |

默认处理器预算（`@ipt`）很小，而这几个 getter **每次调用都会重新发 `sensor` 并重算整条链**。
同一个单位要在同一个判断里比多次时，先缓存：

```text
f = unit_family(u)
t = unit_tier(u)

# 条件卡 Expr 模式：
f == @__AIR__
```

不要写 `unit_family(u) == @__AIR__ || unit_family(u) == @__NAVAL__`——那会把完整展开复制两遍。
循环里尤其要缓存；遍历大量单位时优先把 `unitfor` 的过滤条件放在最外层。

## 使用限制

### 只能用在表达式字段

支持：

- Expr 卡（`x = unit_family(u)`）。
- `if` / `elif` / `while` / `for` 条件卡的 **Expr 表达式模式**（源码里是 `expr "..."`）。
- 函数 `return <expr>` 的返回表达式。

不支持，且编译期会明确报错（不会静默变成 `null` 变量）：

- 简单条件字段（值 / 运算 / 比较三段式）。
- 短路条件（`exprsc "..."`）。
- `switch` 的表达式字段（先 `f = unit_family(u)`，再 `switch f`）。
- `case` 的值：请写数字字面量，例如 `case 2`；不要在 case 里写 `@__AIR__`。
- 裸 `jump` 操作数、普通 `set` / `op` 卡的值字段；这些位置的 `unit_family(...)` 等 getter 调用同样会报错。

编译器会拒绝任何会被编译的代码里残留的 `@__` token 或 getter 调用，因此「写错位置」是显式错误，而不是运行时读到一个空变量。未使用的函数体 / 库函数不会被 lowering；其中的常量或 getter 只会在真正被调用/使用时检查，不会影响未使用代码的存档。

### 没有成员糖

不支持 `u.family` / `u.tier` / `u.planet`。单位和单位类型的成员展开不同，编译器无法可靠区分接收者，
所以只提供函数写法 `unit_family(u)` 等。同名用户 `funcdef` 会遮蔽这些内建，不要用这三个名字定义函数。

### 多行 Expr 卡重开会变成等价公式

`unit_family(u)` 的展开是多行指令。多行 Expr 卡保存时按展开文本写入，重开时由通用 op 链重建为一个
**等价的长公式**，不保证函数名 `unit_family` 还在。条件卡 / 结构卡保留源文本，重开仍显示原表达式。
需要函数名稳定可读时，把它写在条件里，或接受 Expr 卡重开后的公式形态。

## 模组单位与未来单位

分类表**冻结在原版 v160 的 logic id 列表**：

- 模组单位的 logic id 通常是 `-1`，或使用原版未占用的新 id。
- 目前没有原版单位使用 56 以上；未来版本新增的单位也会落在表外。
- 这些情况 `unit_family` / `unit_tier` / `unit_planet` 一律返回 `0`（未知），不会猜成某个家族。

需要识别模组单位时，用原版传感器直接比较：

```text
u.type.Name == "my-mod-unit"
```

也可以读 `u.type.id`（同一个 logic id）自己做映射。不要依赖 `0` 代表某个具体家族；`0` 只表示「不在
这张 v160 表里」。

表不能原地扩范围或改语义：已保存的条件 / 载体源会重新编译，指令流一变就过不了
`verifyRestore`，存档会退到原版视图。需要改变语义时新增 intrinsic 名字或版本。

## 没有这三个 getter 时已经能做什么

以下写法本身就是原版 `sensor`，不需要本功能：

| 写法 | 含义 |
| --- | --- |
| `u.type.Flying` | 是否会飞（移动方式） |
| `u.type.Size` | 单位尺寸 |
| `u.type.Health` | 最大生命值 |
| `u.type.Armor` | 护甲 |
| `u.type.Range` | 攻击范围 |
| `u.type.Speed` | 移动速度 |
| `u.type.PayloadCapacity` | 载荷容量 |
| `u.type.ItemCapacity` | 物品容量 |
| `u.type.Name` | 单位类型名（字符串） |
| `u.type.id` | 原版 logic id |

`unit_family` / `unit_tier` / `unit_planet` 只是把这些原始字段进一步归类；它们不改变产物语义，
也不提供运行时新数据。

## 怎么验证

1. 写 `f = unit_family(u)`，保存后切到 Original 视图。
2. 应看到两条 `sensor`（`@type`、`@id`）加一串 `op`，不应出现 `@__` 或 `unit_family`。
3. 把产物复制到无模组客户端，仍然可以解析和运行。
