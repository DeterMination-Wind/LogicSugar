# 单位控制卡（unitbind / unitnext / unitfor / unitfree）

> 返回 [教程目录](README.md) · 想看清每一条指令：[编译展开详解](unit-control-compilation.md) · English: [en/unit-control.md](en/unit-control.md)

## 什么时候用

先看一个很常见的翻车现场：你写 `ubind @poly`，接着发 `ucontrol move`，单机测试一切正常；把同样的逻辑复制到第二台处理器，两台开始抢同一只 `@poly`——这一 tick 你的处理器绑到它，下一 tick 被另一台绑走，命令互相覆盖。

原版 `ubind` 只负责“把 `@unit` 指向某只单位”，不负责“这只单位归谁”。单位控制卡（unit-control cards）补的是后半句：给单位写一个属于本处理器的 flag（标志位），之后同一台处理器的卡都按这个 flag 找到同一批单位。也可以说，它做的是“认领”（claim）：号码由处理器坐标算出来，谁贴的工牌谁负责。

这四张卡适合：

- 需要稳定控制同一批单位，而不是每 tick 重新抓一只；
- 多台处理器各管一队，不能互相抢；
- 按数量循环，比如“最多 8 只 `@poly`，每只读一次生命”；
- 把单位放回空闲，让别的处理器可以接手。

如果你只是临时 `ubind` 读一下属性，原版 `ubind` 就够了，不必上这四张卡。

## 四张卡怎么选

| 卡片（UI 名） | 源码写法 | 做什么 | 什么时候用 |
| --- | --- | --- | --- |
| 控制一个单位（Control One Unit） | `unitbind <type> <variable>` | 优先复用上次认领的那只；否则扫描该类型，复用或认领一只 | 固定一只单位，反复给它发命令 |
| 控制下一个空闲单位（Control Next Idle Unit） | `unitnext <type> <variable>` | 扫描并跳过已有 flag 的单位，找下一只空闲的 | 设计用途是轮换/逐个领取空闲单位 |
| 解控（Release Unit） | `unitfree <variable>` | 只把 flag 是本处理器的单位放回空闲 | 交还单位、换岗、清理自己的队伍 |
| 控制多个单位（Control Units） | `unitfor <count> <type> <variable>` … `blockend` | 每轮对最多 `count` 只自己的单位执行循环体，不够就认领 | 批量读状态、批量发命令 |

> 卡片标题以游戏内语言为准。源码写法是 Sugar 文本和卡片 `write()` 的形式。`unitfor` 的编辑器卡片只显示 `count`、`type`、`variable` 三个字段；文本里可能还会看到一个内部目标下标，后面会说明。

## 先认识 flag：这队单位归谁

四张卡共用同一个身份，也就是处理器 flag。编译器在每张卡开头都会重算一次：

```mlog
op mul myFlag @thisx 100000
op add myFlag myFlag @thisy
op add myFlag myFlag 1
```

下文为了读起来顺，把编译器生成的 `__ls_ub_uid` 写成 `myFlag`，其它隐藏变量也换成短名；你切到 Original 视图时看到的仍是 `__ls_ub_*`。完整对照在[编译篇](unit-control-compilation.md)。

这三行的意思是：

- `@thisx` / `@thisy` 是处理器所在格的坐标；
- `* 100000` 把横坐标放到高位，`+ @thisy` 把纵坐标拼进去；
- 最后 `+ 1`，是因为地图原点 `(0, 0)` 的处理器如果只乘加会得到 `0`，而 `0` 被保留给“没有主人”。

于是有三条很实用的结论：

1. **flag 0 表示未认领。** 非 0 表示“已经有人贴了工牌”；只有正好等于本处理器 `myFlag` 的，才算“我的”。
2. **同一台处理器的四张卡共享一个 flag。** `unitbind` 认领的单位，`unitfor` 也能数到。
3. **不同处理器坐标不同，flag 也不同。** 正常地图上不会撞号，别人的卡不会来抢你的单位，你的卡也不会去抢别人的。

单位身上的 flag 只是一个数字。处理器没有全局花名册，每张卡都是现扫现认：`ubind` 一只只看，`sensor` 读 flag，再比较。这也是为什么后面反复强调：不要自己给同一批单位写别的 flag。

## unitbind：稳定控制一只

卡片名是“控制一个单位”（Control One Unit），写法：

```text
unitbind @poly unit
```

| 字段 | 说明 |
| --- | --- |
| `type` | 单位类型，例如 `@poly`、`@flare` |
| `variable` | 接收单位对象的普通变量，示例里是 `unit` |

它做三件事：

1. 先看这张卡上次记下的 `held`：用 `ubind held` 绑回那个**单位对象**。如果它还在、没死、flag 仍是 `myFlag`，就直接复用，不再扫描。
2. 否则扫描该类型：`ubind @poly` 沿着原版单位游标一只只取；死掉的跳过；flag 正好是 `myFlag` 的直接拿下，别的非零 flag 跳过；认领空闲单位（flag 0）时，`@controlled` 非零的也跳过。都没有命中，就认领一只空闲的。
3. 成功后把单位写进 `unit`；一只都没找到时，把 `unit` 设成 `null`。

`held` 是这张卡自己的隐藏变量（名字里带语句下标），不是按 `unit` 这个变量名共享的。两张 `unitbind` 卡即使都写 `unit`，也各有各的 `held`。

最小例子：

```text
unitbind @poly unit
print unit
health = unit.health
print health
```

第一行是卡；后三行是普通打印和 Expr。`health = unit.health` 会编译成 `sensor health unit @health`，读的是变量 `unit` 指向的单位，不是 `@unit`。如果找不到可用单位（既没有空闲的，也没有已经带 `myFlag` 的），`unit` 会是 `null`，这时读生命没有意义；发命令前先判断。

认领握手长这样（节选）：

```mlog
set held @unit
ucontrol flag myFlag 0 0 0 0
end
end
end
end
```

这里只截了“记住候选、写 flag、结束这一轮执行”的部分；真实产物前后还有扫描、复核和跳转。`end` 不是 sleep：第一个 `end` 就让执行回到程序顶部；默认 1 条/tick 时下一 tick 从顶部重新开始，等执行再次走到这张卡时才复核；调高每 tick 指令数时，可能在同一 tick 的剩余额度里就从顶部接着跑。四行里后三行没有跳转指向。真正完成确认的是下一次进入这张卡时的 `held` 分支——`ubind held`、读 flag、仍等于 `myFlag` 就 `take`。完整读法见[编译篇](unit-control-compilation.md)。

## unitnext：换下一只空闲单位

卡片名是“控制下一个空闲单位”（Control Next Idle Unit），写法：

```text
unitnext @flare scout
```

它和 `unitbind` 共用同一套扫描、跳过和认领逻辑，但目标不同：`unitbind` 是“我就要这一只”，`unitnext` 是“给我下一只空闲的”。

具体做法：

- 开头也有一次性的 `held` 复核分支：如果上一轮刚认领了一只、还没交付，先用 `ubind held` 绑回它，确认 flag 还是 `myFlag` 就把它交给 `scout`；
- 交付成功后立刻 `set held null`，所以下一次执行不会粘在这只单位上，而是继续扫描；
- 扫描时跳过任何非零 flag 的单位，包括你自己已经拥有的单位，只找 flag 0、`@controlled` 为 0 的空闲单位。

所以重复执行会沿着原版 `ubind` 的游标一只只领新的空闲单位；已经领过的单位留在你的队里，不再重复交付。要还回去用 `unitfree`。如果所有 `@flare` 都已经被某个处理器认领，扫描会走到失败分支，`scout` 设成 `null`。

> 这里的“一次性”很关键：`unitbind` 的 `held` 是长期记忆，`unitnext` 的 `held` 只用一次。确认并交付后它就把 `held` 清掉，下一次重新扫描。两者都靠下一次进入卡片完成复核，但只有 `unitbind` 会一直复用同一只。

## unitfree：只解自己的

卡片名是“解控”（Release Unit），写法：

```text
unitfree unit
```

它先 `ubind unit`，然后只有同时满足“单位存在、没死、flag 正好是 `myFlag`”才解控：

- 把 flag 清成 `0`；
- `ucontrol unbind`，解除逻辑控制；
- 把变量设成 `null`。

不是你的单位它不解控，也不改 flag：

- 变量是 `null`：跳过；
- 单位已经死了：跳过；
- flag 是 `0`（空闲）或别人的 flag：跳过；
- 你手写过 `ucontrol flag` 的自定义 flag：也不等于 `myFlag`，照样跳过。

所以 `unitfree` 不是万能清 flag 工具。手写过 flag 的单位，要先自己 `ubind` 过去，再手动 `ucontrol flag 0 0 0 0 0` 清回空闲。

## unitfor：按数量批量处理

卡片名是“控制多个单位”（Control Units），写法：

```text
unitfor <count> <type> <variable> <destIndex>
    ...
blockend
```

| 字段 | 说明 |
| --- | --- |
| `count` | 每轮最多处理几只，示例用 `8` |
| `type` | 单位类型，例如 `@poly` |
| `variable` | 循环变量，每轮指向一只单位 |
| 正文 | 你的循环体，`break` / `continue` 可用 |

最后那个 `<destIndex>` 是内部的目标下标，编辑器卡片不显示它。从文本导入时行尾要带一个整数（比如 `0`），编译器会按 `blockend` 重新算；它不是你要理解的字段。

它按两段工作：先数一遍你已经拥有多少只该类型单位，再按名额把单位交给循环体。完整机制在[编译篇](unit-control-compilation.md)；你现在只需要记住：每轮最多处理 `count` 只，缺的名额用空闲单位补。

例子：

```text
unitfor 8 @poly unit 42
    print unit
    health = unit.health
    print health
blockend
```

`42` 是内部的目标下标，编译器会按 `blockend` 重算，不影响这段程序的行为；编辑器卡片里看不到它。

这段会对最多 8 只你拥有的 `@poly` 执行循环体：打印单位、读生命、打印生命。如果你一只都没有、也没有可认领的空闲单位，循环体一次也不执行；有空闲单位时，卡会先认领，认领会让这一轮提前回到顶部；默认 1 条/tick 时，下一 tick 从顶部重新开始，等执行再次走到这张卡、重新统计之后，这些单位才会被交给循环体。

`break` 直接离开这个块；`continue` 跳过本轮剩下的正文，但这只单位仍然算已经处理（编译篇会看到它跳到计数那一步）。

这里有一个容易忽略的行为：**程序本身会一轮轮从头再跑，认领新单位还会让这一轮提前回到顶部。** 卡会重新统计自己的状态，但你的循环体可能再次看到已经处理过的单位。把循环体写成可重复执行的操作（发命令、读状态），不要在里面做“只执行一次”的累计；要做统计，自己在体内判断/标记，或者把累计放在能接受重入的地方。

另外，`unitfor` 不会在没单位时把循环变量清空；没有单位时变量保持上一次的值（或未赋值的数字 `0`）。别用“循环后变量是不是 `null`”判断有没有单位。

## 处理器眼里的几个值

| 名字 | 是什么 | 什么时候变 |
| --- | --- | --- |
| `@unit` | 原版当前绑定的单位 | `ubind` 或任何卡认领/切换单位时 |
| `unit` | 你写在卡片里的普通变量，保存一个单位对象 | 卡片成功时写入；`unitbind` 失败时设成 `null` |
| `unit.flag` / `@flag` | 单位身上的 flag | 卡写 `ucontrol flag` 时，或你自己写时 |
| `unit.dead` / `@dead` | 单位是否已死/无效 | 单位死亡时 |
| `unit.controlled` / `@controlled` | `0` 表示没有逻辑/玩家/指挥接管；非零表示已被接管 | 控制权变化时 |
| `@thisx` / `@thisy` | 处理器所在格坐标 | 处理器换位置时 |

最重要的区别是 `@unit` 和 `unit`：

- `@unit` 是原版内置的“当前绑定单位”，`ubind` 和四张卡都会改它；
- `unit` 是你的变量，只有卡片或你的代码会写它；
- 后面再来一次 `ubind`，`@unit` 可能换人，但 `unit` 还指着原来那只；
- `ucontrol` 这类原版指令作用于 `@unit`，所以要让 `unit` 指向的单位执行命令，先 `ubind unit` 把它重新绑定成 `@unit`，或者直接用 `unit.health` 这样以变量为 `from` 的读法。

`unitbind` 失败时只保证你的变量是 `null`，不保证 `@unit` 也是 `null`；`@unit` 可能还停在最后扫描到的单位上。要发命令前先判断变量，必要时重新 `ubind`。

## 易错点

- **别自己给同一批单位写 flag。** 卡协议把任何非零 flag 都当成“有主人”。你写一个自定义 flag，`unitfor` 会把那些单位既不算“我的”，也不认作“空闲”，直接跳过；`unitfree` 也不会帮你清。要恢复，先手动把 flag 清成 0。
- **`unitbind` 会粘住，`unitnext` 不会。** 两者都用 `held` 跨 tick 复核；`unitbind` 确认后继续把 `held` 指向这只单位，下次优先复用它；`unitnext` 在 `take` 里把 `held` 清空，所以下次重新扫描，并且扫描会跳过你自己已经拥有的单位。
- **`unitfree` 只解自己的。** 别人的 flag、自定义 flag、已经死的单位，它都跳过；它不是清 flag 的万能工具。
- **`@controlled` 只挡“认领”，不挡复用。** 已经带 `myFlag` 的单位即使被玩家或指挥接管，`unitbind` 仍会复用、`unitfor` 仍会投递；只有“要不要把一只陌生单位拉进来”时才检查 `@controlled`。而且 flag 是你的，不等于控制权是你的：玩家接管的单位，`ucontrol` 命令不会生效。
- **处理器换位置会换 flag。** flag 按 `@thisx` / `@thisy` 算；把处理器拆掉在别处重建后，旧单位还带着旧 flag，新位置的卡会把它们当成别人的。换位置前先解控，或者手动清 flag。
- **变量可能是 `null`。** `unitbind` 找不到单位时变量为 `null`；`unitfor` 没单位时变量保持旧值。发 `ucontrol` 前先判断。
- **`end` 不是 sleep。** 原版 `end` 结束当前这一轮执行，把 `@counter` 送到指令末尾；下一次 `runOnce` 从程序顶部重新开始。默认 1 条/tick 时就是下一 tick；调高每 tick 指令数、还有剩余额度时，可能在同一 tick 里从顶部接着跑。四行里只有第一个会被执行到，后面三行没有跳转指向；四个 `end` 也不等于四个 tick。真正完成认领确认的是下一次进入卡片时的复用分支（`unitbind` / `unitnext` 的 `held`，或 `unitfor` 的重新统计）。完整解释见[编译篇](unit-control-compilation.md)。
- **联机/原版兼容。** 保存产物是纯原版 mlog，别的客户端不用装模组；但四张卡只在 LogicSugar 编辑器里有，原版打开会看到一长串原版指令，而不是四张卡。
- **重载不保留隐藏变量。** `held` 等 `__ls_ub_*` 是普通变量，处理器重新载入或换处理器后会回到初始值；单位身上的 flag 不会因为处理器重载而自动清零。别把“重载”当成解控手段。

## 预算

处理器的硬限制是 1000 条指令。四张卡不是一条 `ubind` 的语法糖，而是完整的认领协议，写起来会明显吃预算。

按本章示例的大小（一张卡 + 正文，再加程序级的 `set @counter 0`，不含载体），`unitbind` / `unitnext` / `unitfree` / `unitfor` 大约分别是 53 / 51 / 14 / 65 行。这只是直观尺度，不是固定常数：类型名、变量名和循环体会改变行数；标签行本身不占指令槽，真正占预算的是指令，而每条 `jump` 都算一条。`unitfor` 的循环体每多一条语句，产物就按这条语句自己的 lowering 增长，嵌套或多张卡叠加很快。

另外，原版 `LParser` 还有 500 个跳转位置的上限，卡里的标签也占名额；大量堆叠时，可能先撞标签上限，而不是 1000 条指令。

超过限制，LogicSugar 会直接报错，不提供突破这个上限的开关。写完切到 Original 数一下，或者看编译错误。

## 怎么自己验证

1. 在编辑器里只放一张卡，保存一次。
2. 切到 Original 视图，对照[编译篇](unit-control-compilation.md)的完整列表；先看前三行 `myFlag`，再看 `ubind` / `sensor` / `ucontrol` / `jump` 的顺序。
3. 用编辑器的“复制编译后代码”把产物粘到一个普通处理器里；能解析、能运行，就说明它不依赖模组。
4. 想确认载体：保存后重开，卡片应该还在。把末尾的 `set __ls_sugar "..."` 和 `# @logic-sugar-v2` 标记块都删掉再重开，可以专门观察推断：LogicSugar 会尝试按 `__ls_ub_*` 名字和固定序列恢复卡片；推断不出就显示原版指令。
5. 想确认“没有偷单位”：用两台处理器跑同样的 `unitbind @poly unit`，看它们最终拿到的单位是不是不同的两只。

## 小结与思考

四张卡的核心不是 `ubind`，而是 flag 认领协议：

- `unitbind` 稳定复用一只；
- `unitnext` 用一次性 `held` 复核，交付后清空，下一次继续找空闲单位；
- `unitfree` 只解自己的；
- `unitfor` 按数量批量处理，缺的名额用空闲单位补。

产物是纯原版 mlog，卡片身份在载体（carrier）里；没有载体时，LogicSugar 会按 `__ls_ub_*` 的固定 lowering 尝试推断，对不上就保持原版。

留两个问题给你：

1. 为什么 `unitfor` 要先数一遍再认领，而不是边扫边认领？
2. 如果你在 `unitfor` 前面写 `total = 0`，在循环体里累加 `total`，而认领新单位让执行提前回到顶部，会发生什么？

想看清每一条指令，继续读[编译展开详解](unit-control-compilation.md)。

## 相关文档

- [编译展开详解](unit-control-compilation.md)：四张卡的完整 renamed lowering、两段式循环和 `end` 读法。
- [架构总览](../architecture.md)：载体还原、反编译推断和单位控制卡的位置。
- [重建规则](../reconstruction.md)：`unit-control` 一节的推断与拒绝规则。
- [术语表](../glossary.md)：单位控制卡、载体、隐藏状态变量等名词。
- [测试指南](../testing.md)：`unitControlTest` 的手测与自测清单。
- [English](en/unit-control.md)。
