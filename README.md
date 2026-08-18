# Logic Sugar

[中文](README_zh.md) | [English](README.md)

> Write logic around ideas and structure instead of a wall of jumps.

Logic Sugar improves the Mindustry logic editing experience for people who want programs that are easier to read, change, and share. It lets you express common control flow and calculations in a more structured way, while saving the result as vanilla-compatible mlog.

That compatibility is the important part: a program written with Logic Sugar can continue to work in ordinary Mindustry clients. The mod is useful both for learning logic and for maintaining larger processors where raw jump instructions become difficult to follow.

## Install

Download the universal JAR from Releases and put it in Mindustry's mods directory. Enable it, then open the logic editor to use the enhanced workflow.

## Build

~~~powershell
.\gradlew.bat deploy
~~~

The deploy task creates a desktop-and-Android JAR. It requires a local Android SDK with D8 for the dexing step.
