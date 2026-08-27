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
- **函数** —— 定义带参数的函数、调用并返回值；normal（子程序）与 inline（内联）两种模式可在设置中切换。
- **函数库** —— 所有处理器共享的全局函数，在编辑器内直接编辑，关闭时自动校验保存，文件损坏可自动修复。
- **恢复结构** —— 打开普通 mlog 自动识别并恢复其中的 `if` / `for` / `while` / `switch` / 函数结构；仅恢复验证无误的部分，其余保持原样。
- **原版 / Sugar 双视图** —— 随时切换查看生成的原版 mlog 或返回编辑，切换前保护未保存的修改。
- **编辑器辅助** —— 跳转线着色、隐藏 `__ls_*` 内部变量、Ctrl+点击 / Ctrl+拖动复制积木、悬停提示、搜索高亮。

## 安装

需要 **Mindustry v155 或更高版本**（桌面或 Android）。从 [Releases](https://github.com/DeterMination-Wind/LogicSugar/releases) 下载通用 JAR——一个文件同时支持两个平台——放进 Mindustry 的 mods 目录，启动游戏后在模组列表里启用，再打开逻辑处理器编辑器即可使用。

## 从源码构建

前置条件：

- **Java 17+**
- 仓库旁有一份构建好的 Mindustry 源码（编译依赖 `../Mindustry-master/desktop/build/libs/Mindustry.jar`）
- 打包 Android 端需要本地 Android SDK 的 **D8** 和至少一个 platform 的 `android.jar`（通过 `ANDROID_SDK_ROOT`、`ANDROID_HOME` 或 `D8_PATH` 环境变量指定）

~~~powershell
.\gradlew.bat deploy
~~~

输出的 `build/libs/LogicSugar-v<版本>.jar` 是一个同时支持桌面与 Android 的跨平台 JAR；普通的 `build` 任务同样会触发 deploy。

## 许可证

本项目基于 [GNU GPL v3](LICENSE) 许可证开源。