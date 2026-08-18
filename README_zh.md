# Logic Sugar

[中文](README_zh.md) | [English](README.md)

> 写逻辑时关注结构和意图，而不是在一堆跳转指令里迷路。

Logic Sugar 面向希望让逻辑更易读、更易修改和更易分享的 Mindustry 玩家。它把常见的控制流和计算表达成更接近结构化编程的形式，同时在保存时生成原版兼容的 mlog。

这种兼容性是它最重要的价值：使用 Logic Sugar 编写的程序仍然可以在普通 Mindustry 客户端中运行。它既适合学习逻辑，也适合维护规模较大的处理器程序。

## 安装

从 Releases 下载通用 JAR，放入 Mindustry 的 mods 目录并启用，然后打开逻辑编辑器即可使用增强后的编辑流程。

## 构建

~~~powershell
.\gradlew.bat deploy
~~~

deploy 会生成桌面与 Android 通用 JAR；打包 Android 版本需要本机 Android SDK 中的 D8。
