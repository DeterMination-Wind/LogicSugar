# Logic Sugar

<h1 align="center">
  <a href="https://github.com/DeterMination-Wind/LogicSugar/releases/latest"><img src="https://img.shields.io/github/v/release/DeterMination-Wind/LogicSugar?display_name=release&label=Latest%20Release&color=green"></a>
  <a href="https://github.com/DeterMination-Wind/LogicSugar/releases"><img src="https://img.shields.io/github/downloads/DeterMination-Wind/LogicSugar/total?label=Downloads&color=blue"></a>
  <a href="LICENSE"><img src="https://img.shields.io/github/license/DeterMination-Wind/LogicSugar?label=License"></a>
  <a href="https://github.com/DeterMination-Wind/LogicSugar"><img src="https://img.shields.io/github/stars/DeterMination-Wind/LogicSugar?style=flat&label=Star%20this%20mod!&color=yellow"></a>
</h1>

[中文](README_zh.md) | [English](README.md)

> 写逻辑时关注结构和意图，而不是在一堆跳转指令里迷路。

Logic Sugar 面向希望让逻辑更易读、更易修改、更易分享的 Mindustry 玩家。它把常见的控制流和计算变成编辑器里清晰的结构化块，同时保存为原版兼容的 mlog——写出来的程序在任何普通客户端里都能运行，之后还能重新打开继续编辑。

## 功能

### 结构化控制流

用积木编写常见控制流，保存时编译为普通原版 mlog。

| 结构 | 写法 | 说明 |
| --- | --- | --- |
| 条件分支 | `if`、`elif`、`else` | 以块的形式编写条件分支。 |
| 循环 | `for`、`while` | 以块的形式编写循环。 |
| 循环控制 | `break`、`continue` | 跳出或继续循环。 |
| 多分支 | `switch`、`case` | 把一个值与多个分支匹配。 |

### 表达式与函数

| 功能 | 说明 |
| --- | --- |
| **条件里写表达式** | `if`、`elif`、`while`、`for` 的条件（Expr 模式）可直接写 `hp < 25 && !shielded` 这样的完整表达式。 |
| **表达式语句** | 写 `result = (a + b) * 2`：既可以拖一张 Expr 卡，也可以把这一行直接粘进代码文本；导入时落成 Expr 卡，保存时自动展开为等价指令，重新打开自动折叠回来，写错当场标红。 |
| **随处表达式** | 赋值、函数参数、`return` 返回值等任何值的位置都可以写表达式，包括 `@unit.@health` 成员访问。 |
| **函数** | 定义带参数的函数、调用并返回值；normal（子程序）与 inline（内联）两种模式可在设置中切换。 |
| **函数库** | 所有处理器共享的全局函数，在编辑器内直接编辑；最多 10000 条语句（不占单台处理器 1000 条指令额度），关闭时自动校验保存，文件损坏可自动修复。 |

### 数据结构

声明卡把内存块的一段地址登记为结构化数据。声明本身只是元数据：所有操作都会降级为普通原版指令，产物在任何原版客户端可运行，并可通过 Sugar 载体恢复。

| 声明 | 类型 |
| --- | --- |
| `array` | 内存块区间（基址 + 容量） |
| `matrix` | 二维数组 |
| `record` | 记录 |
| `stack` | 栈 |
| `queue` | 队列 |
| `deque` | 双端队列 |
| `bitset` | 位集 |
| `map` | 哈希表 |
| `uset` | 集合 |
| `list` | 列表 |
| `heap` | 堆 |
| `chain` | 链表 |

数组表达式里可直接写 `buf[i]`、`buf[i] = 5` 这样的下标读写；它们编译为普通原版 `read`、`write` 指令，重新打开自动折回表达式卡。这些源码行也可以直接粘贴：`array buf cell1 0 8` + `x = buf[3]` 会导入一张声明卡和一张 Expr 卡。

每一种 array / 容器 intrinsic 都有自己独立的操作积木，并按结构归入对应分类（栈操作、队列操作、数组算法等）：`fill`、`sum`、`reverse`、`spush`、`qpop`、`dpushf`、`btest`、`mapset`、`uadd`、`lappend`、`hpush`、`cinit`、`cnew` 等。旧八槽 `arrayinit` 只保留存档兼容。所有操作仍会降级为普通原版指令，并可通过 Sugar 载体重新打开。`sortasc` / `sortdesc` 已改为原地希尔排序，对随机或逆序数据明显快于以前的插入排序。

每种数据结构都有一章高级教程：声明卡、函数速查表、转译后的 mlog 逐行解释、复杂度与使用须知。从 [教程目录](docs/tutorials/README.md) 开始。

### 数据结构 getter 语法糖

Expr 模式下，已声明结构可以用下标或方法写法替代 getter intrinsic。下标糖只读，写请用 `lset`、`bset`、`cset`。

| 结构 | 支持的写法 |
| --- | --- |
| `list` | `l[i]`、`l.get(i)`、`l.size()`、`l.find(v)` |
| `stack` | `s.top()`、`s.peek()`、`s.size()` |
| `queue` | `q.front()`、`q.peek()`、`q.size()` |
| `deque` | `d.front()`、`d.back()`、`d.size()` |
| `bitset` | `b[i]`、`b.test(i)`、`b.count()` |
| `map` | `m[k]`、`m.get(k)`、`m.has(k)`、`m.size()` |
| `uset` | `s.has(v)`、`s.size()` |
| `chain` | `c[i]`、`c.get(i)`、`c.head()`、`c.next(i)`、`c.len()` |

它们与对应的 intrinsic（如 `lget(l, i)`、`speek(s)`）完全等价，仍会降级为普通原版指令。

### 编辑器、调试与视图

| 功能 | 说明 |
| --- | --- |
| **恢复结构** | 打开已保存的处理器时，会尽量还原当初的结构化积木（`if`、`for`、`while`、`switch`、函数）以及数组、栈、记录等数据声明卡；只还原对得上的部分，认不出的保持原版指令。纯原版 mlog（别人手写、没有 Logic Sugar 源码）只会尝试恢复控制流，不会凭空长出数据结构。 |
| **原版与 Sugar 双视图** | 随时切换查看生成的原版 mlog 或返回编辑，切换前保护未保存的修改。 |
| **编辑器辅助** | 跳转线着色、隐藏 `__ls_*` 内部变量、Ctrl+点击与 Ctrl+拖动复制积木、悬停提示、搜索高亮、撤销与重做（电脑 `Ctrl+Z`、`Ctrl+Y`，手机底部按钮），以及编译后指令条数对照上限的实时显示。 |
| **断言语句** | 八张卡片给程序加"运行时体检"：数组下标越界、数据类型不符、值与期望不符、打印输出比对不符都会让程序停在出错行，并在处理器上方显示原因；断点可冻结整个游戏、把视角居中到该处理器并报告出错行号；写日志不打断运行。默认断言只存在于编辑器中，保存的代码不含它们；「调试断言构建」（仅单机）开启后才真正运行，联机时保存的程序永远与原版兼容。设置中可禁用断点、把断言失败改为断点、让暂停期间保持视角分离。 |
| **处理器状态指示** | 停机的处理器头顶显示停在哪一条，长等待的处理器画进度圆环，运行出错原地显示消息（有期望值与实际值一并显示）；等待阈值、检查频率与提醒特效可在设置中调节，视野外的处理器不再参与绘制。 |
| **单位 flag 显示** | 设置中可选：在单位正上方显示其逻辑 flag，不同 flag 使用不同的鲜明颜色；默认值 0 不显示。纯展示，不影响存档与联机。 |
| **复制变量与打印缓冲** | 把当前处理器的全部变量按名称整理成保留完整精度的表格复制到剪贴板（可直接粘贴进电子表格），或复制程序当前打印的内容。 |

## 安装

这是 **v5.1.0** 版本，需要 **Mindustry v160.1 或更高版本**（桌面或 Android）。从 [Releases](https://github.com/DeterMination-Wind/LogicSugar/releases) 下载通用 JAR——一个文件同时支持两个平台——放进 Mindustry 的 mods 目录，启动游戏后在模组列表里启用，再打开逻辑处理器编辑器即可使用。

## 从源码构建

前置条件：

- **Java 17+**
- 仓库旁有一份构建好的 Mindustry 源码（编译依赖 `../Mindustry-master/desktop/build/libs/Mindustry.jar`）
- 打包 Android 端需要本地 Android SDK 的 **D8** 和至少一个 platform 的 `android.jar`（通过 `ANDROID_SDK_ROOT`、`ANDROID_HOME` 或 `D8_PATH` 环境变量指定）

~~~powershell
.\gradlew.bat deploy
~~~

输出的 `build/libs/LogicSugar-v<版本>.jar` 是一个同时支持桌面与 Android 的跨平台 JAR；普通的 `build` 任务同样会触发 deploy。

## 致谢

Logic Sugar 的部分设计与实现受益于以下项目，感谢这些作者的付出：

- [MlogAssertions](https://github.com/cardillan/MlogAssertions)（MIT）—— 断言系统直接移植自该项目，语句格式与其保持兼容，Mindcode 生成的断言代码可直接在 Logic Sugar 中打开。
- [Mindcode](https://github.com/cardillan/mindcode)（MIT）—— 表达式子系统（Expr）的部分思路来源于此。
- [mindustry_logic_bang_lang](https://github.com/A4-Tacks/mindustry_logic_bang_lang)（GPL-3.0）—— 反编译系统与静态检查的思路参考（跳转链穿线、logic_lint 风格检查）。
- [logic-assist](https://github.com/nosbhghggg/logic-assist)（GPL-3.0）—— 跳转线按目标着色的思路来源。
- [MI2-Utilities](https://github.com/BlackDeluxeCat/MI2-Utilities)（GPL-3.0）—— 开发过程中的思路参考。

## 许可证

本项目基于 [GNU GPL v3](LICENSE) 许可证开源。
