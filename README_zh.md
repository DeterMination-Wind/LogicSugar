# Logic Sugar

[中文](README_zh.md) | [English](README.md)

<p align="center"><img src="assets/LOGO.jpg" width="500" alt="Logic Sugar Logo"></p>

Logic Sugar 为 Mindustry 逻辑编辑器添加了 `for`、`while`、`switch`、`break` 等结构化控制流积木，还提供了跳转线着色、框选批处理和表达式编辑功能。所有内容保存时自动转为原版兼容的 `mlog` 指令。

## 特性

### 结构化语句

在逻辑处理器中编写可读的循环和分支结构，编译为标准 `jump`/`op` 指令——未安装此模组的玩家看到的是普通原版逻辑。

<details>
<summary><b>for</b> — 带初值、步长和结束条件的循环</summary>

```
for i = 0; i < 10; i += 2
  ...
end
```
</details>

<details>
<summary><b>while</b> — 条件循环</summary>

```
while signal == 1
  ...
end
```
</details>

<details>
<summary><b>switch</b> — 多分支选择</summary>

```
switch state
  case 0
    ...
    break
  case 1
    ...
    break
end
```
</details>

- 自动缩进、折叠和结构辅助线
- 错误保留在编辑器中方便修复

### 跳转线着色

为 `jump` 跳转线按目标着色，不同分支清晰可辨。可在游戏模组设置中切换。

- **关闭**：全部白色（原版）
- **分散色**：按目标索引黄金角度 HSV 着色
- **积木色**：按目标积木类别颜色提亮 1.4 倍

### 框选批处理

批量选择、移动、复制、删除逻辑积木。

- 在空白画布上拖动进行框选；**蓝色** = 移动模式，**绿色** = 复制模式（通过选中积木上的复制图标切换）
- `Ctrl` + 点击 → 复制拖动单个积木；`Delete`/`Backspace` → 删除选中；右键/`Esc` → 取消
- 选中后，积木按钮变为批量操作：垃圾桶 → 全部删除，`+` → 复制，复制图标 → 切换模式

### 表达式编辑（`Expr` 积木）

在 `Expr` 积木中编写数学表达式，编译为 `op` 指令链，也可将 `op` 链折叠回可读表达式。

**编译**：`result = cos(a) * 10 + x` →

```
op cos _0 a 0
op mul _0 _0 10
op add x _0 x
```

- **折叠**：打开编辑器时，连续的 `op` 链自动折叠回表达式形式
- **保存**：表达式展开为标准 `op` 指令——原版兼容
- **语法高亮**：数字（金色）、函数（珊瑚色）、变量（白色）、运算符（浅灰）
- **错误提示**：语法错误以红色显示在表达式下方，并标明具体原因

**支持的运算符**

| 类别 | 运算符 |
|---|---|
| 一元函数 | `not abs sign log log10 floor ceil round sqrt rand sin cos tan asin acos atan` |
| 二元函数 | `max(a,b) min(a,b) angle(a,b) angleDiff(a,b) len(a,b) noise(a,b) logn(a,b)` |
| 逻辑 | `\|\|` `&&` ` xor ` |
| 比较 | `==` `!=` `===` `<` `>` `<=` `>=` |
| 位运算 | `&` `<<` `>>` `>>>` |
| 算术 | `+` `-` `*` `/` `//` `%` `%%` `^` |

### 滚动条增强

彩色滚动条（每段按积木类别着色）、点击跳转、悬停预览。

## 构建

```
gradlew deploy
```

输出：`build/libs/LogicSugar.jar`（桌面与 Android 通用 JAR）。放入 Mindustry 的 `mods/` 目录即可。

## 致谢

- [logic-assist](https://github.com/nosbhghggg/logic-assist) — 提供了跳转线着色、框选批处理和表达式编辑功能的实现基础
- [MI2-utilities](https://github.com/BlackDeluxeCat/MI2-Utilities-Java) — 拖拽移动和跳转索引转换逻辑
- [mindcode](https://github.com/cardillan/mindcode) — op 链反编译、运算符分类、优化规则
- [MindustryX](https://github.com/TinyLake/MindustryX/) — JUMP 按钮参考实现

## License

GPL-3.0-or-later。参见 [LICENSE](LICENSE)。