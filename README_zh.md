# Logic Sugar

<h1 align="center">
  <a href="https://github.com/DeterMination-Wind/LogicSugar/releases/latest"><img src="https://img.shields.io/github/v/release/DeterMination-Wind/LogicSugar?display_name=release&label=Latest%20Release&color=green"></a>
  <a href="https://github.com/DeterMination-Wind/LogicSugar/releases"><img src="https://img.shields.io/github/downloads/DeterMination-Wind/LogicSugar/total?label=Downloads&color=blue"></a>
  <a href="LICENSE"><img src="https://img.shields.io/github/license/DeterMination-Wind/LogicSugar?label=License"></a>
  <a href="https://github.com/DeterMination-Wind/LogicSugar"><img src="https://img.shields.io/github/stars/DeterMination-Wind/LogicSugar?style=flat&label=Star%20this%20mod!&color=yellow"></a>
</h1>

[中文](README_zh.md) | [English](README.md)

> 让 Mlog 变成高级语言

Logic Sugar 是为熟悉高级语言（包括 Python,C++ 等）的玩家量身定制的。

通过基于对 Mlog 的基本操作的封装，Logic Sugar 实现了 `for`,`Func` 等众多功能。基于链接的内存元，还可以创建例如 `vector`,`map` 等高级数据结构，为 `vector` 等函数提供了类 C++ STL 的内置函数（ `sort` 等）。

Logic Sugar 支持多人游戏，这意味着您也可以高效理解其他同样使用 Logic Sugar 的玩家的代码。

一切旨在让 Mlog 编写更高效。

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
| **在条件判断中写表达式** | `if`、`elif`、`while`、`for` 的条件（Expr 模式）可直接写 `hp < 25 && !shielded` 这样的完整表达式。 |
| **Expr积木** | 写 `result = (a + b) * 2`：既可以拖一张 Expr 卡，也可以把这一行直接粘进代码文本；导入时落成 Expr 卡，保存时自动展开为等价指令，重新打开自动折叠回来，写错当场标红。 |
| **随处表达式** | 赋值、函数参数、`return` 返回值等任何值的位置都可以写表达式，包括 `@unit.@health` 成员访问。 |
| **函数** | 定义带参数的函数、调用并返回值；normal（子程序）与 inline（内联）两种模式可在设置中切换。 |
| **函数库** | 所有处理器共享的全局函数，减少重复码字。在设置处直接编辑；最多 10000 条语句。 |

### 数据结构

> [!note]
> 这是原版兼容的，但是部分数据结构的操作复杂度和 C++ `STL` 并不相同，详细查看 [教程目录](docs/tutorials/README.md)

> 由 Logic Sugar 模组创建的，含有特殊积木的 Logic Sugar Code ，下文称作 “糖码”。编译后或由原版编辑器创建的 Mlog 则为 “Mlog”

| 声明 | 类型 |
| --- | --- |
| `array` | 标准数组|
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

支持在Expr中使用 `buf[i]`、`buf[i] = 5` 这样的下标读写

每种数据结构的对应函数均可以在游戏内的 *添加积木* 界面里查看查看。

每种数据结构都有一章高级教程：声明卡、函数速查表、转译后的 mlog 逐行解释、复杂度与使用须知。从 [教程目录](docs/tutorials/README.md) 开始。

#### getter 语法糖

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

它们与对应的单独操作积木（如 `vector_at(l, i)`、`stack_top(s)`）完全等价。

### 编辑器、调试与视图

| 功能 | 说明 |
| --- | --- |
| **从源码重建** | 打开已保存的处理器时，会尽量重建 Mlog 为糖码（偏保守，只会尝试恢复控制流，不会恢复数据结构） |
| **编辑器辅助** | 跳转线着色、Ctrl+点击与 Ctrl+拖动复制积木、悬停提示、搜索高亮、撤销与重做（电脑 `Ctrl+Z`、`Ctrl+Y`，手机底部按钮），以及编译后指令条数对照上限的实时显示。 |
| **断言语句** | 提供了一些可用于调试并显示报错信息在处理器头上的语句，具体可看 [上游README](https://github.com/cardillan/MlogAssertions/blob/main/README.md) |
| **处理器状态指示** | 停机的处理器头顶显示停在哪一条，长等待的处理器画进度圆环，运行出错原地显示消息（有期望值与实际值一并显示）。 |
| **单位 flag 显示** | 设置中可选：在单位正上方显示其逻辑 flag，不同 flag 使用不同的鲜明颜色；默认值 0 不显示。 |
| **复制变量与打印缓冲** | 把当前处理器的全部变量按名称整理成保留完整精度的表格复制到剪贴板（可直接粘贴进电子表格），或复制 Mlog 的输出缓冲区。 |

## 安装

最新为 **v5.1.0** 版本，需要 **Mindustry v160.1 或更高版本**（桌面或 Android）。从 [Releases](https://github.com/DeterMination-Wind/LogicSugar/releases) 下载通用 JAR，放进 Mindustry 的 mods 目录，启动游戏后在模组列表里启用，再打开逻辑处理器编辑器即可使用。

> [!note]
> 若网络环境不支持 Github 高速下载，可以加入 [qq群](https://qm.qq.com/q/QjHwsXMQ48) ，或者使用 [游戏启动器](https://github.com/DeterMination-Wind/Xenon) 获得国内服务器镜像下载功能

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

- [MlogAssertions](https://github.com/cardillan/MlogAssertions)（MIT）—— 断言系统直接移植自该项目，语句格式与其保持兼容，目前，Mindcode 生成的断言代码可直接在 Logic Sugar 中打开。
- [Mindcode](https://github.com/cardillan/mindcode)（MIT）—— 表达式子系统（Expr）的部分思路来源于此。
- [mindustry_logic_bang_lang](https://github.com/A4-Tacks/mindustry_logic_bang_lang)（GPL-3.0）—— 反编译系统与静态检查的思路参考（跳转链穿线、logic_lint 风格检查）。
- [logic-assist](https://github.com/nosbhghggg/logic-assist)（GPL-3.0）—— 跳转线按目标着色的思路来源，本项目最初基于此 Mod 开发
- [MI2-Utilities](https://github.com/BlackDeluxeCat/MI2-Utilities)（GPL-3.0）logic-assist 的致谢中包含了 Mi2U ~虽然我也不知道为什么~

## 许可证

本项目基于 [GNU GPL v3](LICENSE) 许可证开源。
