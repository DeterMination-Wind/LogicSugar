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

- **结构化控制流** —— `if` / `elif` / `else`、`for`、`while`、`switch` / `case`、`break` / `continue` 以块的形式编写，保存时编译为普通 mlog。
- **条件里写表达式** —— `if` / `elif` / `while` / `for` 的条件（Expr 模式）可直接写 `hp < 25 && !shielded` 这样的完整表达式。
- **表达式语句** —— `result = (a + b) * 2` 一行搞定：保存时自动展开为等价指令，重新打开自动折叠回来，写错当场标红。
- **随处表达式** —— 赋值、函数参数、`return` 返回值等任何值的位置都可以写表达式，包括 `@unit.@health` 成员访问。
- **数组** —— 用 `array` 卡把内存块的一段地址（基址 + 容量）登记为命名数组，表达式里直接写 `buf[i]`、`buf[i] = 5` 这样的下标读写；编译为普通原版 `read` / `write` 指令，重新打开自动折回表达式卡。
- **数据结构** —— `matrix`（二维数组）、`arrayinit`、`record`、`stack` / `queue` / `deque`、`bitset`、`map`（哈希表）、`uset`（集合）、`list`、`heap`、`chain`（链表）等卡片把内存块的一段地址登记为结构化数据；`sum` / `avg` / `sortasc` / `reverse` / `bsearch`、`spush` / `qpop` / `dpushf`、`btest`、`mapset`、`uadd`、`lappend`、`hpush`、`cinit` / `cnew` 等表达式展开为普通原版指令。声明卡不进入保存的代码，重新打开自动折回，产物在任何原版客户端可运行。
- **函数** —— 定义带参数的函数、调用并返回值；normal（子程序）与 inline（内联）两种模式可在设置中切换。
- **函数库** —— 所有处理器共享的全局函数，在编辑器内直接编辑，关闭时自动校验保存，文件损坏可自动修复。
- **恢复结构** —— 打开普通 mlog 自动识别并恢复其中的 `if` / `for` / `while` / `switch` / 函数结构；仅恢复验证无误的部分，其余保持原样。
- **原版 / Sugar 双视图** —— 随时切换查看生成的原版 mlog 或返回编辑，切换前保护未保存的修改。
- **编辑器辅助** —— 跳转线着色、隐藏 `__ls_*` 内部变量、Ctrl+点击 / Ctrl+拖动复制积木、悬停提示、搜索高亮、撤销/重做（电脑 Ctrl+Z / Ctrl+Y，手机底部按钮），以及编译后指令条数对照上限的实时显示。
- **断言语句** —— 八张卡片给程序加"运行时体检"：数组下标越界、数据类型不符、值与期望不符、打印输出比对不符都会让程序停在出错行并在处理器上方显示原因；断点可冻结整个游戏、把视角居中到该处理器并报告出错行号；写日志不打断运行。默认断言只存在于编辑器中，保存的代码不含它们；「调试断言构建」（仅单机）开启后才真正运行，联机时保存的程序永远与原版兼容。设置中可禁用断点、把断言失败改为断点、让暂停期间保持视角分离。
- **处理器状态指示** —— 停机的处理器头顶显示停在哪一条，长等待的处理器画进度圆环，运行出错原地显示消息（有期望/实际值时一并显示）；等待阈值、检查频率与提醒特效可在设置中调节，视野外的处理器不再参与绘制。
- **复制变量 / 复制打印缓冲** —— 把当前处理器的全部变量按名称整理成保留完整精度的表格复制到剪贴板（可直接粘贴进电子表格），或复制程序当前打印的内容。

## 安装

这是面向 BE 的预发布版本，需要 **Mindustry BE 构建 27771 或更高版本**（桌面或 Android），不适用于普通稳定版 Mindustry。从 [Releases](https://github.com/DeterMination-Wind/LogicSugar/releases) 下载通用 JAR——一个文件同时支持两个平台——放进 Mindustry 的 mods 目录，启动游戏后在模组列表里启用，再打开逻辑处理器编辑器即可使用。

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