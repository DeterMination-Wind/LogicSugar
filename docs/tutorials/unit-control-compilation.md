# 单位控制卡是怎么编译的

> 返回 [教程目录](README.md) · 玩家向教程 [unit-control.md](unit-control.md) · English: [en/unit-control-compilation.md](en/unit-control-compilation.md)

## 先看结论

四张单位控制卡不是“一条 `ubind` 的语法糖”。它们把一套完整的 flag 认领协议展开：先算本处理器的 flag，再扫描单位、跳过不能用的、写 flag、回到顶部，最后在下一次进入时复核，把单位交给你的变量或循环体。

产物只使用原版指令：`op`、`set`、`ubind`、`sensor`、`ucontrol`、`jump`、`end`，加上你自己写在循环体里的语句。原版客户端能直接解析和运行，不需要装模组。

代价是代码很长。下面会看到 `unitbind`、`unitnext`、`unitfree`、`unitfor` 的完整 lowering；它们会明显吃处理器的 1000 条指令预算。

## 变量与标签改名

真实产物里的名字是 `__ls_ub_*`，读起来很吵。下面统一换成短名，但保留“原名 → 短名 → 含义”的对照；你自己在 Original 视图里看到的仍是左列。

| 原名 | 短名 | 含义 |
| --- | --- | --- |
| `__ls_ub_uid` | `myFlag` | 本处理器的单位 flag |
| `__ls_ub_h_0` | `held` | 记住的候选单位 |
| `__ls_ub_f_0` | `flag` | 临时读出的 `@unit.@flag` |
| `__ls_ub_d_0` | `dead` | 临时读出的 `@unit.@dead` |
| `__ls_ub_c_0` | `controlled` | 临时读出的 `@unit.@controlled` |
| `__ls_ub_a_0` | `anchor` | 本轮扫描看到的第一只单位 |
| `__ls_ub_s_0` | `steps` | 扫描步数 |
| `__ls_ub_n_0` | `delivered` | `unitfor`：本轮已经交给循环体的数量 |
| `__ls_ub_p_0` | `phase` | `unitfor`：`0` 统计，`1` 投递 |
| `__ls_ub_o_0` | `owned` | `unitfor`：已经拥有的数量 |
| `__ls_ub_r_0` | `room` | `unitfor`：本轮还能认领几个 |
| `__ls_ub_scan_0` | `scan` | 扫描入口 |
| `__ls_ub_loop_0` | `loop` | 扫描循环头 |
| `__ls_ub_keep_0` | `keep` | 单位通过存活检查 |
| `__ls_ub_take_0` | `take` | 认领/复用成功 |
| `__ls_ub_lost_0` | `lost` | 复用的单位丢了 |
| `__ls_ub_fail_0` | `fail` | 没找到单位 |
| `__ls_ub_ok_0` | `ok` | 卡片完成 |
| `__ls_ub_ours_0` | `ours` | `unitfor`：这只已经是我的 |
| `__ls_ub_count_0` | `count` | `unitfor`：统计已有单位 |
| `__ls_ub_give_0` | `give` | `unitfor`：把单位交给循环体 |
| `__ls_ub_wrap_0` | `wrap` | `unitfor`：扫描回到起点/列表末尾 |
| `__ls_ub_mark_0` | `mark` | `unitfor`：记下第一只单位 |
| `__ls_ub_free_0` | `free` | `unitfree`：跳过解控 |
| `__ls_stmt_3` | `afterLoop` | `unitfor` 块之后的语句位置 |
| `__ls_ub_step_<i>` | `step` | `unitfor` 的 `continue` 落点（有 `continue` 时才生成） |

两个读表时容易误会的地方：

- `_0` 是**语句下标**，不是“第 0 张卡”。同一个程序里的第二张卡会用 `_1`，第三张 `_2`，以此类推。
- `afterLoop` 不是固定叫 `__ls_stmt_3`。它是 `__ls_stmt_<destIndex + 1>`，也就是 `blockend` 之后的位置。下面 `unitfor` 例子里，`unitfor` 的下标是 0、正文是 1、`blockend` 的下标是 2，所以出口标签是 `__ls_stmt_3`；换个位置就会变。

`__ls_` 前缀是 LogicSugar 保留的，不要在你的程序里占用这些名字。

## 共同前缀：myFlag

每张卡开头都会重新算一次：

```mlog
op mul myFlag @thisx 100000
op add myFlag myFlag @thisy
op add myFlag myFlag 1
```

`@thisx` / `@thisy` 是处理器所在格的坐标。`@thisx * 100000` 把横坐标放到高位，`+ @thisy` 把纵坐标拼进去，最后 `+ 1`。

为什么要有 `+ 1`？因为地图原点 `(0, 0)` 的处理器如果只做乘加，flag 会是 `0`；而协议把 `0` 保留给“没有主人”。加 1 之后，本处理器在原点也有非零 flag。

这个值有三个性质：

- 同一处理器的所有单位控制卡共用它，所以它们操作的是同一队单位；
- 不同处理器坐标不同，flag 不同，不会互相认领；
- 它是普通变量，不是内置常量；每次进卡都重算，所以把处理器换到别的位置会换 flag。

## unitbind：完整 53 行（卡片 51 行）的 A/B/C/D

示例是 `unitbind @poly unit`，程序后面还有一条 `print unit`。下面 51 行只列卡片本身的 lowering；标题里的 53 行是完整示例产物：51 行卡 + 示例正文 `print unit` + 程序级的入口跳过 `set @counter 0`（载体另算）。

### 卡片 lowering（已改名）

```mlog
op mul myFlag @thisx 100000
op add myFlag myFlag @thisy
op add myFlag myFlag 1
jump scan equal held null
ubind held
jump lost equal @unit null
sensor dead @unit @dead
jump lost notEqual dead 0
sensor flag @unit @flag
jump take equal flag myFlag
lost:
set held null
scan:
set steps 0
set anchor null
loop:
op add steps steps 1
jump fail greaterThan steps 8192
ubind @poly
jump fail equal @unit null
jump fail equal @unit anchor
jump keep notEqual anchor null
set anchor @unit
keep:
sensor dead @unit @dead
jump loop notEqual dead 0
sensor flag @unit @flag
jump take equal flag myFlag
jump loop notEqual flag 0
sensor controlled @unit @controlled
jump loop notEqual controlled 0
sensor flag @unit @flag
jump loop notEqual flag 0
set held @unit
ucontrol flag myFlag 0 0 0 0
end
end
end
end
ubind held
jump loop equal @unit null
sensor flag @unit @flag
jump loop notEqual flag myFlag
take:
set unit @unit
set held @unit
jump ok always x false
fail:
set unit null
set held null
ok:
```

### A. 复用 held

```mlog
jump scan equal held null
ubind held
jump lost equal @unit null
sensor dead @unit @dead
jump lost notEqual dead 0
sensor flag @unit @flag
jump take equal flag myFlag
lost:
set held null
```

`held` 是上一次这张卡认领/复用的单位。`ubind held` 不是按类型绑定，而是绑定 `held` 这个**单位对象**本身，所以只要它还在，卡就能准确回到同一只。

这里检查三件事：

- `@unit` 是不是 `null`：单位可能已经死了、被移除，或者变量本身是空的；
- `@dead` 是不是非零：单位死了；
- `@flag` 是不是仍然是 `myFlag`：单位可能被别的处理器重新认领，或者被 `unitfree` 放回空闲。

任何一条不满足就跳到 `lost`，把 `held` 清空，然后进入扫描。三条都满足就直接 `take`，把 `unit` 和 `held` 都设成 `@unit`，不扫描、不写 flag。

注意：这段复用时只看 `@dead` 和 `@flag`，不看 `@controlled`。已经带 `myFlag` 的单位即使被玩家或指挥接管，也会被复用；`@controlled` 只在“要不要认领一只陌生单位”时起作用。

### B. 扫描

```mlog
scan:
set steps 0
set anchor null
loop:
op add steps steps 1
jump fail greaterThan steps 8192
ubind @poly
jump fail equal @unit null
jump fail equal @unit anchor
jump keep notEqual anchor null
set anchor @unit
keep:
sensor dead @unit @dead
jump loop notEqual dead 0
sensor flag @unit @flag
jump take equal flag myFlag
jump loop notEqual flag 0
sensor controlled @unit @controlled
jump loop notEqual controlled 0
sensor flag @unit @flag
jump loop notEqual flag 0
```

`ubind @poly` 每次取下一只该类型的单位，取到列表末尾会回到开头。`anchor` 记录本轮看到的第一只单位；如果又看到它，说明已经绕了一圈，没有更多候选了，跳 `fail`。

`steps` 是硬上限：`8192` 步还没绕回 `anchor`，就不再扫了。单位列表可能在扫描过程中变化（单位死亡、新单位出生），没有这个上限，某些情况下会一直循环。

扫描时的跳过规则，顺序很重要：

- `@dead != 0`：死的不要；
- `flag == myFlag`：自己的，直接 `take`；
- `flag != 0`：已经有别的主人，跳过；
- `@controlled != 0`：没有主人的单位如果已经被逻辑、玩家或指挥控制，跳过；
- 再读一次 `flag`：如果在这之间被别的处理器抢走，跳过。

注意 `@flag` 读了两次：先在 `sensor flag` 后判断是不是自己的，再在 `@controlled` 检查之后重新读一次。这样做的目的是缩小“读 flag 和写 flag 之间被别的处理器插队”的窗口；它不是原子操作，只是把竞争窗口压窄。

`jump take equal flag myFlag` 在跳过非零 flag 之前，所以扫描也可能捡到一只**已经带本处理器 flag** 的单位。这是 `unitbind` 和 `unitnext` 的一个区别：前者的扫描接受自己的单位，后者在扫描里跳过所有非零 flag，只在顶部 `held` 分支确认上一轮刚认领的那一只。

### C. 认领握手

```mlog
set held @unit
ucontrol flag myFlag 0 0 0 0
end
end
end
end
ubind held
jump loop equal @unit null
sensor flag @unit @flag
jump loop notEqual flag myFlag
```

认领分四步：

1. 把当前候选记到 `held`；
2. `ucontrol flag myFlag` 写 flag，同时把单位纳入逻辑控制；
3. 写四个 `end`；
4. 复核 `ubind held` / `sensor flag` / `jump loop`。

这里最容易读错的是第 3、4 步。按原版 v160.1 的 `EndI`，`end` 只做一件事：把 `@counter` 设成指令总数，当前这一轮执行到此结束；下一次 `runOnce` 把它重置为 0，从第 0 条重新开始。默认 1 条/tick 时“下一次”就是下一 tick；如果处理器每 tick 指令数调高、还有剩余额度，同一 tick 里也可能从顶部接着跑。所以第一个 `end` 之后，紧跟着的 `ubind held` 不会顺序执行，后三个 `end` 也没有跳转指向。四个 `end` 不是四拍，也不提供固定等待。

那复核在哪里完成？在**下一次进入这张卡**时的 A 段：`held` 已经记下了候选，`ubind held` 绑回它，读 flag；如果还是 `myFlag`，就 `take`。这就是“延迟确认”在实践中的落点。

为什么要延迟？因为另一台处理器可能在同一刻也看到了“flag 为 0”，并且也准备写自己的 flag。写完回到顶部，下一次进入时再读，才能判断最终是谁赢；输的那一方读到的 flag 不是自己的，就放弃这只。

### D. 成功与失败

```mlog
take:
set unit @unit
set held @unit
jump ok always x false
fail:
set unit null
set held null
ok:
```

成功时把单位写进用户变量 `unit`，同时更新 `held`，然后跳过失败分支。失败时把两者都清成 `null`。

`unit` 是你的变量，`@unit` 是原版当前绑定单位。成功时它们指向同一只；失败时 `unit` 保证是 `null`，但 `@unit` 不保证是 `null`，它可能还停在最后扫描到的那只单位上。

## unitnext：完整 51 行（卡片 50 行），一次性确认

示例是 `unitnext @flare scout`。下面 50 行是卡片本身；加上程序级入口跳过 `set @counter 0` 共 51 行。

### 卡片 lowering（已改名）

```mlog
op mul myFlag @thisx 100000
op add myFlag myFlag @thisy
op add myFlag myFlag 1
jump scan equal held null
ubind held
jump lost equal @unit null
sensor dead @unit @dead
jump lost notEqual dead 0
sensor flag @unit @flag
jump take equal flag myFlag
lost:
set held null
scan:
set steps 0
set anchor null
loop:
op add steps steps 1
jump fail greaterThan steps 8192
ubind @flare
jump fail equal @unit null
jump fail equal @unit anchor
jump keep notEqual anchor null
set anchor @unit
keep:
sensor dead @unit @dead
jump loop notEqual dead 0
sensor flag @unit @flag
jump loop notEqual flag 0
sensor controlled @unit @controlled
jump loop notEqual controlled 0
sensor flag @unit @flag
jump loop notEqual flag 0
set held @unit
ucontrol flag myFlag 0 0 0 0
end
end
end
end
ubind held
jump loop equal @unit null
sensor flag @unit @flag
jump loop notEqual flag myFlag
take:
set scout @unit
set held null
jump ok always x false
fail:
set scout null
set held null
ok:
```

和 `unitbind` 对比，差异有几处：

- 开头也有 `held` 复核分支，但 `take` 里多了一条 `set held null`：确认并交付后立刻清空，下一次重新扫描，不会像 `unitbind` 那样粘住；
- 扫描里没有 `jump take equal flag myFlag`，所以任何非零 flag 都被跳过，包括自己的；只有顶部的 `held` 分支会接受“上一轮刚认领的那一只”；
- 失败分支把 `scout` 设成 `null`，同时清空 `held`。

### 一次性确认

`take:` 在列表里位于认领握手之后。和 `unitbind` 一样，它不是靠 `end` 后面的顺序代码到达的，而是靠下一次进入卡片时的顶部 `held` 分支：

1. 认领时 `set held @unit`，写 flag，`end`；
2. 下一次进入卡片，`jump scan equal held null` 发现 `held` 非空；
3. `ubind held` 绑回候选，查 `@dead`、查 `flag == myFlag`；
4. 都通过就跳 `take`，执行 `set scout @unit`，然后 `set held null`；
5. 下一次调用 `unitnext` 时 `held` 已经是 `null`，重新扫描，找下一只空闲单位。

所以 `unitnext` 的 `held` 是“一次性收据”：只用来确认上一轮刚认领的那只，确认完就丢掉。`unitbind` 的 `held` 是“长期记忆”，确认后继续指向同一只。两者的延迟确认都落在下一次进入卡片，但交付语义不同。

## unitfree：完整 14 行（卡片 13 行）

示例是 `unitfree unit`。下面 13 行是卡片本身；加上程序级入口跳过 `set @counter 0` 共 14 行。

### 卡片 lowering（已改名）

```mlog
op mul myFlag @thisx 100000
op add myFlag myFlag @thisy
op add myFlag myFlag 1
ubind unit
jump free equal @unit null
sensor dead @unit @dead
jump free notEqual dead 0
sensor flag @unit @flag
jump free notEqual flag myFlag
ucontrol flag 0 0 0 0 0
ucontrol unbind 0 0 0 0 0
set unit null
free:
```

它只做一件事：把自己的单位放回空闲。条件全部满足才执行：

1. `ubind unit` 后 `@unit` 不是 `null`；
2. `@dead` 是 `0`；
3. `@flag` 正好等于 `myFlag`。

然后：

- `ucontrol flag 0` 把 flag 清成 0；
- `ucontrol unbind` 让单位脱离逻辑控制；
- `set unit null` 清空你的变量。

任何一条不满足就跳到 `free:`，什么都不改。注意这里“什么都不改”指的是 flag 和变量；它前面已经执行过 `ubind unit`，所以 `@unit` 会指向这个单位。别人的单位、空闲单位、自定义 flag 的单位，卡都不会帮你解控。

`unitfree` 也不检查 `@controlled`：只要 flag 正好是 `myFlag`，它就清。所以“解控”解的是 flag 协议里的归属，不是把玩家从控制位上赶下来。

## unitfor：完整 65 行（卡片 64 行），两段式

示例是 `unitfor 2 @poly unit`，正文是 `print unit`。这是卡片形式；从文本导入时行尾还带一个内部 `destIndex`，编译器会按 `blockend` 重算，下面的产物里不会出现它。下面 64 行含示例正文；加上程序级入口跳过 `set @counter 0` 共 65 行。

### 卡片 lowering（已改名）

```mlog
op mul myFlag @thisx 100000
op add myFlag myFlag @thisy
op add myFlag myFlag 1
set delivered 0
set phase 0
set owned 0
set anchor null
set steps 0
scan:
op add steps steps 1
jump afterLoop greaterThan steps 8192
ubind @poly
jump wrap equal @unit null
jump mark equal anchor null
jump wrap equal @unit anchor
jump keep always x false
mark:
set anchor @unit
keep:
sensor dead @unit @dead
jump scan notEqual dead 0
sensor flag @unit @flag
jump ours equal flag myFlag
jump scan equal phase 0
jump scan notEqual flag 0
sensor controlled @unit @controlled
jump scan notEqual controlled 0
sensor flag @unit @flag
jump scan notEqual flag 0
jump scan greaterThanEq delivered 2
jump scan lessThanEq room 0
set held @unit
ucontrol flag myFlag 0 0 0 0
end
end
end
end
ubind held
jump scan equal @unit null
sensor flag @unit @flag
jump scan notEqual flag myFlag
op sub room room 1
jump give always x false
ours:
jump count equal phase 0
jump scan greaterThanEq delivered 2
jump give always x false
count:
op add owned owned 1
jump scan always x false
wrap:
jump afterLoop notEqual phase 0
set phase 1
set delivered 0
set steps 0
op sub room 2 owned
jump scan equal @unit null
jump keep always x false
give:
set unit @unit
print unit
op add delivered delivered 1
jump scan always x false
afterLoop:
```

这里的 `print unit` 是示例正文。正文换成别的语句，`give:` 下面的 `print unit` 就换成你的指令；`set unit @unit` 和 `op add delivered delivered 1` 是卡片自己的投递/计数。列表里的 `2` 是示例的 `count`；换成 `8`，这些比较和 `op sub` 的立即数就跟着变。

### 初始化与统计（phase 0）

开头把状态清零：

```mlog
set delivered 0
set phase 0
set owned 0
set anchor null
set steps 0
```

`scan:` 之后先做和 `unitbind` 类似的边界检查：步数上限、`ubind @poly`、`anchor` 判环、`dead` 跳过、`flag` 读取。

然后：

```mlog
jump ours equal flag myFlag
jump scan equal phase 0
jump scan notEqual flag 0
```

如果 flag 是 `myFlag`，去 `ours:`。`phase` 还是 0 时，`ours:` 会跳到 `count:`，只做 `owned++`，然后继续扫描；这不是投递，只是统计。

如果 flag 不是 `myFlag`，而 `phase` 还是 0，就直接 `jump scan`，连空闲单位也不认领。第一阶段只数自己已经拥有多少只，不改变任何单位。

### 从统计切到投递（wrap）

`wrap:` 是扫描绕回起点或 `ubind` 返回 `null` 时到达的地方：

```mlog
wrap:
jump afterLoop notEqual phase 0
set phase 1
set delivered 0
set steps 0
op sub room 2 owned
jump scan equal @unit null
jump keep always x false
```

如果 `phase` 已经是 1，说明第二轮也扫完了，直接去 `afterLoop`。否则切到 `phase = 1`，把 `delivered` 和 `steps` 清零，计算：

```text
room = count - owned
```

`room` 是“本轮还能认领几个新单位”。如果 `owned` 已经超过 `count`，`room` 会是负数；后面的 `jump scan lessThanEq room 0` 会拦住认领，所以不会越补越多。

切完 phase 后，如果当前 `@unit` 是 `null`，回到 `scan`；否则用 `jump keep` 继续处理当前这只单位。

### 投递与认领（phase 1）

`ours:` 在 phase 1 时：

```mlog
ours:
jump count equal phase 0
jump scan greaterThanEq delivered 2
jump give always x false
```

如果已经投递了 `count` 只，就跳过；否则去 `give:`，把这只单位交给循环体。

注意：`ours` 分支在 `@controlled` 检查之前，所以已经带 `myFlag` 的单位即使被玩家或指挥接管，也会照常投递；`@controlled` 只拦“认领陌生单位”。

不是自己的单位，在 phase 1 会走认领路径：

```mlog
jump scan equal phase 0
jump scan notEqual flag 0
sensor controlled @unit @controlled
jump scan notEqual controlled 0
sensor flag @unit @flag
jump scan notEqual flag 0
jump scan greaterThanEq delivered 2
jump scan lessThanEq room 0
set held @unit
ucontrol flag myFlag 0 0 0 0
end
end
end
end
ubind held
jump scan equal @unit null
sensor flag @unit @flag
jump scan notEqual flag myFlag
op sub room room 1
jump give always x false
```

顺序是：

1. 跳过非零 flag 和 `@controlled` 非零的单位；
2. 再读一次 flag，缩小竞争窗口；
3. 如果已经投递满 `count` 只，或者 `room <= 0`，不再认领；
4. 否则走和 `unitbind` 一样的认领握手；
5. 握手代码后面还写着 `room--` 和 `jump give`。按 `end` 的语义，执行会从程序顶部重来，下一轮 phase 0 会重新统计 `owned`，再用 `count - owned` 重算 `room`；真正保证名额的是这次重算，而不是把这两行当成会顺序执行。

`give:` 是投递点：

```mlog
give:
set unit @unit
print unit
op add delivered delivered 1
jump scan always x false
```

先把 `@unit` 写进循环变量，再执行你的正文，最后 `delivered++`，继续扫描。`delivered` 在本轮最多到 `count`；到顶后，后面的 `ours` 和认领路径都会跳过。

### break 与 continue

`break` 由通用块逻辑处理，跳到 `__ls_stmt_<destIndex + 1>`，也就是 `afterLoop`，直接离开 `unitfor`。

`continue` 跳到 `__ls_ub_step_<i>`。这个标签在 `blockend` 处、`delivered++` 之前生成：

```mlog
step:
op add delivered delivered 1
jump scan always x false
```

所以 `continue` 会跳过正文剩下的部分，但**这只单位仍然算已经处理**：`delivered` 还是会加一。没有 `continue` 的 `unitfor` 不会生成这个标签，省一个跳转位置。

### 为什么必须两段式

如果边扫描边认领，会有一个问题：你还没数完自己已经拥有多少只，就已经开始补名额了。新认领的单位会改变“已拥有”的数量，补着补着就可能超过 `count`；而且统计和投递混在一起，循环体什么时候执行、执行几次都会变得难以控制。

两段式把这件事拆开：

- phase 0 只统计 `owned`，不认领、不投递；
- 扫描绕回后，用 `room = count - owned` 算出还能补几个；
- phase 1 才投递已拥有的单位、认领空闲单位，并用 `delivered` 封顶。

这也让卡片能经受 `end` 带来的重入：认领新单位会结束这一轮、回到顶部；默认 1 条/tick 时下一次是下一 tick。phase 0 会重新统计，把刚认领的单位算进 `owned`，phase 1 再把它投递出来。它不依赖“上一次执行到哪一行”这种脆弱状态。

## 为什么代码长这样

把上面的细节压成几条设计理由：

- **避免两台处理器认领同一只单位。** 先读 flag，再写 flag；写完回到顶部，下一次进入时重读（`unitbind` / `unitnext` 的 `held` 分支）或重数（`unitfor` 的 phase 0）。没有这层重入检查，两台处理器可能同时看到 flag 0，然后都以为自己成功了。
- **避免抢走已被控制的单位。** 认领陌生单位前检查 `@controlled != 0` 并跳过，所以不会把玩家、指挥或其他逻辑正在用的单位拉进来。已经属于自己的单位不走这道检查，复用/投递不受影响。
- **单位列表会变。** 扫描过程中单位可能死亡或出生，`ubind` 的游标也会动；`anchor` 用来判断是否绕完一圈，`steps > 8192` 是防止永远绕不回来的硬上限。
- **跳过死单位。** `@dead` 检查在 flag 之前，死单位不参与认领。
- **产物必须是原版可解析的。** 只用 `op` / `set` / `ubind` / `sensor` / `ucontrol` / `jump` / `end`，不引入自定义指令；标签和变量名都是原版语法。
- **状态放在普通变量里。** `myFlag`、`held`、`phase` 等都是普通 mlog 变量，不依赖模组运行时；重载后它们回到初始值，卡会重新扫描。
- **名字带语句下标，方便推断。** `__ls_ub_*_<i>` 和 `__ls_stmt_<n>` 让没有载体的程序也能按固定 lowering 逐条比对，恢复成卡片；位置对不上就保持原版。

## 末尾：set @counter 0 与载体

卡片 lowering 之后，程序末尾还有入口跳过、标记块和载体（carrier）。以 `unitbind @poly unit` + `print unit` 为例，真实产物的尾部是：

```text
set @counter 0
# @logic-sugar-v2 begin
# @logic-sugar-line unitbind @poly unit
# @logic-sugar-line print unit
# @logic-sugar-line set @counter 0
# @logic-sugar-v2 end
set __ls_sugar "<Base64 of the Sugar source>"
```

`set @counter 0` 是程序级的入口跳过（entry skip），不是每张卡各生成一条。它把执行送回指令 0，让后面的 `set __ls_sugar` 不参与运行。

标记块（`# @logic-sugar-v2 begin` … `# @logic-sugar-v2 end`）是注释，不执行，也不占指令预算；每行 `# @logic-sugar-line` 对应 Sugar 源码的一行。它是最直白的载体，但原版客户端保存时会丢掉注释，所以编译器还写一条真正的 `set` 语句：Sugar 源码经过 Base64 编码后放在 `set __ls_sugar "..."` 里，这条能挺过原版的 parse/save 往返。上面最后一行里的 Base64 只是占位，真实内容是一整行很长的字符串。

几个实际情况：

- 源码较大时，一条 `set __ls_sugar` 会拆成 `set __ls_sugar_1`、`set __ls_sugar_2`……连续编号的分片；读取时按编号拼回去。
- 如果程序用了函数库，还会多出 `set __ls_lib "..."`（或 `set __ls_lib_N` 分片），保存实际用到的库函数子集。
- 老存档里可能看到 `# @logic-sugar-v1 begin` / `# @logic-sugar-v1 end` 标记块；当前版本仍能读取，只是新存档写的是 v2。
- 标记注释不占指令；`set __ls_sugar` / `set __ls_lib` 这些载体行是真正的指令，加上入口跳过一起占 1000 条预算；源码越长、分片越多，占得越多。
- 如果源码大到连分片都放不下，编译器会退回“不保存载体”并警告：程序仍能运行，但 Sugar 源码不再随这个存档恢复。

如果你把载体删掉：

- 程序仍然能跑，因为执行部分没有变；
- 只删 `set __ls_sugar`、留着标记块时，`SugarCompiler.restore` 仍能读出标记块；但编辑器开屏只把 `set __ls_sugar` 当作可信载体，所以会走推断，不保证整份源码原样回来。要把推断路径完整测到，就连标记块一起删。
- 标记块也被删掉（或被原版保存丢掉）时，LogicSugar 会尝试从 `__ls_ub_*` 名字和固定指令序列推断回卡片；
- 如果名字、顺序或语句位置对不上（比如你手改过跳转、换过变量名），推断会拒绝，显示成原版指令；
- 手写的普通 `ubind` / `@flag` 代码不会被误认成这些卡，因为推断只认编译器自己的 lowering。

`set @counter 0` 也是产物的一部分。删掉它会让指令流和编译器的预期不一致；载体校验会失败，没有载体时推断通常也对不上。除非你在调试，否则不要手改。

## 四个 end 的读法

这是读这份 lowering 最容易卡住的地方。

在原版 v160.1 里，`end` 的 `run` 只做一件事：把 `@counter` 设到指令总数。下一次 `runOnce` 看到计数器越界，就把它重置为 `0`，然后执行第 0 条。默认处理器是 1 条指令/tick，所以“下一次”通常是下一 tick；如果每 tick 指令数调高、还有剩余额度，同一 tick 里就会从顶部接着跑。`end` 不是“睡一觉，然后执行下一行”，也不提供固定等待。

因此：

- 第一个 `end` 执行后，紧挨着的下一条指令不会执行；执行已经回到顶部；
- 后面三个 `end` 没有跳转指向，正常路径永远到不了；
- 四个 `end` 不等于四个 tick，也不提供固定等待。

那 `unitbind` 的认领确认是怎么完成的？看 A 段：

```mlog
jump scan equal held null
ubind held
jump lost equal @unit null
sensor dead @unit @dead
jump lost notEqual dead 0
sensor flag @unit @flag
jump take equal flag myFlag
```

认领时已经把候选写进了 `held`。下一次从程序顶部重新执行，走到这张卡时，A 段会先 `ubind held`，再读 flag；如果 flag 还是 `myFlag`，就直接去 `take`。这就是“延迟确认”在实践中的落点。

`end` 后面那几行 `ubind held` / `sensor flag` / `jump loop` 在正常路径上不会执行到；这不是 bug 报告，而是原版 `end` 语义和这份 lowering 放在一起时的读法。`unitbind` / `unitnext` 用下一次进入卡片时的 `held` 分支完成同样的复核；`unitfor` 用下一次的 phase 0 重数来完成。区别只在交付后怎么处理 `held`：`unitbind` 继续留着，`unitnext` 在 `take` 里清空。

`unitfor` 也是一样：认领后的 `end` 让这一轮结束、回到顶部；初始化会重新执行，phase 0 重新统计，把刚认领的单位算进 `owned`，phase 1 再投递。两段式让它不依赖“上次执行到哪一行”。

如果你需要真正的等待，用等待/计时类积木，不要数 `end` 的个数。

## 自己审计

想确认这份文档没有替你“美化”产物，可以按下面几步做：

1. 在编辑器里只放一张卡，保存，切到 Original 视图。
2. 对照本文的完整列表，逐行看 `myFlag`、`ubind`、`sensor`、`ucontrol`、`jump` 的顺序；变量名应该是 `__ls_ub_*`，不是本文的短名。
3. 用原版处理器或 `LAssembler.read` 验证每一行都能解析。仓库里的 `unitControlTest.productIsVanilla` 做的就是这件事：只允许 `op` / `set` / `ubind` / `sensor` / `ucontrol` / `jump` / `end` 和用户正文，并拒绝 `InvalidStatement`。
4. 重点核对几个安全点：`@controlled` 只拦认领、`@flag` 双读、`steps > 8192`、`anchor` 判环，以及“复核代码写在 `end` 后面、不会顺序执行”这一事实；确认 `unitbind` / `unitnext` 都有 `take` 重入、`unitfor` 有 phase 0 重数，并且 `unitnext` 的 `take` 会清空 `held`。
5. 核对 `unitfor` 的两段式：phase 0 只 `owned++`，`wrap` 时算 `room = count - owned`，phase 1 才 `give` 和认领，`delivered` 封顶。
6. 把 `set __ls_sugar "..."` 和 `# @logic-sugar-v2` 标记块都删掉再重开，观察推断是否恢复成卡；改一个扫描守卫或变量名，确认它保持原版而不是硬恢复。
7. 想直接看 `end` 的效果，可以拿一段“`end` 后面还有赋值”的小程序在 `LExecutor` 里单步执行，观察计数器越界后被重置为 0、后面的行没有执行；这是读四个 `end` 最直接的验证。
8. 开发时可以跑 `.\gradlew.bat unitControlTest`。它检查 lowering 形状（flag 拼接、四个 `end`、`unitbind` / `unitnext` 的 `take` 重入、`unitnext` 的一次性 `held` 清空、`unitfor` 的 `break`/`continue`、原版可解析、载体往返），不模拟运行时；运行时行为仍按第 7 步自己看。

## 小结与问题

四张卡共享一套 flag 认领协议，产物是原版 mlog：

- `myFlag = @thisx * 100000 + @thisy + 1`，`0` 留给未认领；
- `unitbind` 先复用 `held`，再扫描/认领；
- `unitnext` 用一次性 `held` 复核，交付后清空；扫描跳过所有非零 flag，所以下一次继续找空闲单位；
- `unitfree` 只清自己的 flag；
- `unitfor` 先统计 `owned`，再算 `room`，最后投递/认领，`delivered` 封顶；
- 载体保存卡片身份，推断只认固定 lowering，对不上就保持原版。

留三个问题：

1. 为什么 `unitfor` 要先统计再投递？如果边扫边认领，`room` 会出什么问题？
2. 为什么扫描要在 `@controlled` 检查前后各读一次 `@flag`？这只缩小窗口，还是彻底消除竞争？
3. 在 `end` 的语义下，`unitbind` / `unitnext` 的确认实际发生在哪一段？为什么不能按行号读 `end` 后面的几行？`unitnext` 为什么在交付后清空 `held`？

相关文档：

- [玩家向教程](unit-control.md)：四张卡怎么选、字段和易错点。
- [重建规则](../reconstruction.md)：`unit-control` 的推断与拒绝规则。
- [术语表](../glossary.md)：单位控制卡、载体、隐藏状态变量。
- [架构总览](../architecture.md)：载体重建与反编译推断的位置。
