# Logic Sugar

<h1 align="center">
  <a href="https://github.com/DeterMination-Wind/LogicSugar/releases/latest"><img src="https://img.shields.io/github/v/release/DeterMination-Wind/LogicSugar?display_name=release&label=Latest%20Release&color=green"></a>
  <a href="https://github.com/DeterMination-Wind/LogicSugar/releases"><img src="https://img.shields.io/github/downloads/DeterMination-Wind/LogicSugar/total?label=Downloads&color=blue"></a>
  <a href="LICENSE"><img src="https://img.shields.io/github/license/DeterMination-Wind/LogicSugar?label=License"></a>
  <a href="https://github.com/DeterMination-Wind/LogicSugar"><img src="https://img.shields.io/github/stars/DeterMination-Wind/LogicSugar?style=flat&label=Star%20this%20mod!&color=yellow"></a>
</h1>

[中文](README_zh.md) | [English](README.md)

> 写逻辑时关注结构和意图，而不是在一堆跳转指令里迷路。

Logic Sugar 面向希望让逻辑更易读、更易修改和更易分享的 Mindustry 玩家。它把常见的控制流和计算表达成更接近结构化编程的形式，同时在保存时生成原版兼容的 mlog。

这种兼容性是它最重要的价值：使用 Logic Sugar 编写的程序仍然可以在普通 Mindustry 客户端中运行。它既适合学习逻辑，也适合维护规模较大的处理器程序。

## 功能

- **结构化控制流** —— `if` / `elif` / `else`、`for`、`while`、`switch` / `case`，以及 `break` / `continue`：在逻辑编辑器中以代码块的形式编写，编译为普通原版 jump 指令。
- **Switch 分派优化** —— `auto` 模式会按可执行指令成本，在整数 case 值的比较链与 `@counter` 跳转表之间自动选择；重复 case 值可以共用首个匹配表槽位。设置为 `chainOnly` 可逐字复现旧版比较链输出。跳转表使用数值守卫语义，非整数值或值域跨度超过 255 时自动回退到比较链。
- **函数** —— 定义带参数的函数、调用并返回值；两种编译模式：**normal（子程序）**（每个函数共享一份 `@counter` 子程序）和 **inline（内联）**（函数体复制到每个调用点）。
- **函数库** —— 所有逻辑处理器共享的全局函数，直接在处理器编辑器中编辑，关闭时自动校验保存，库文件损坏时可自动修复。
- **表达式编译** —— 中缀表达式自动展开为原版 `op` 指令链；自 v2.3.0 起，`if` / `elif` / `for` / `while` 的条件也支持完整表达式。
- **原版兼容输出** —— 结构化源码以载体语句（`set __ls_sugar "..."`）随 mlog 一同保存，程序在普通客户端上原样运行，之后仍可重新打开为可编辑的 sugar 代码块。
- **编辑器辅助** —— 跳转线着色、在变量浏览器中隐藏编译内部变量（`__ls_*`、表达式临时变量）、Ctrl+点击 / Ctrl+拖动复制积木、每个积木的悬停提示、搜索框匹配高亮。

## 安装

需要 **Mindustry v155 或更高版本**（桌面或 Android）。从 Releases 下载通用 JAR——单个文件同时支持两个平台——放入 Mindustry 的 mods 目录即可。mod 会自动加载，也可以像其他 mod 一样在游戏内模组列表中启用或禁用。然后打开逻辑编辑器即可使用增强后的编辑流程。

## 构建

前置条件：

- **Java 17+**
- 本仓库旁需有一份已构建的游戏源码：编译依赖 `../Mindustry-master/desktop/build/libs/Mindustry.jar`。
- 打包时需要本地 Android SDK 中的 **D8** 以及至少一个 platform 的 `android.jar`（通过 `ANDROID_SDK_ROOT`/`ANDROID_HOME` 或 `D8_PATH` 环境变量定位）。

~~~powershell
.\gradlew.bat deploy
~~~

deploy 会把桌面字节码与 Android 的 `classes.dex` 合并为单个跨平台 JAR，输出到 `build/libs/LogicSugar-v<版本>.jar`；普通的 `build` 任务也会触发 deploy。

## 许可证

本项目基于 [GNU GPL v3](LICENSE) 许可证开源。
