# Logic Sugar v5.1.2

> [!IMPORTANT]
> 最低要求 **Mindustry v160.1**（桌面 / Android）。
>
> 保存出去的程序仍然是普通原版 mlog：没装模组的客户端能运行，联机（加入或自建服务器）不受影响。

> [!NOTE]
> v5.1 线的补丁版，修正数组批量运算 `copy` / `indexof` 的两个运行时缺陷，语法与卡 API 未变。
> 注意：这两个运算的内置函数体有改动，**旧版本保存过、且用过它们的处理器**重开时会回落到原版视图（见「兼容」）。

## 中文

**修复**

- **`copy` 的读/写基址不再交叉。** `copy(dmem, dbase, smem, sbase, size)` 以前读用目标基址、写用源基址：同一块内存里的两个数组复制会**反向**赋值（`dst := src` 写反），跨块复制会写到错误区域。现在读用源基址（`sbase` + `smem`）、写用目标基址（`dbase` + `dmem`）。
- **`indexof` 命中即停。** 旧实现在命中后仍要把整段数组读完（读取发生在「已找到就 continue」的跳转之前）。现在第一个匹配就直接跳出循环，只有未命中才读完 `size` 个元素。新增 `FakeMemory` 读计数用例，覆盖命中 / 重复 / 未命中，normal 与 inline 两种模式。
- **文档复杂度修正。** `map` / `uset` 的未命中查询与插新键都无法在空槽提前停止探测，恒为 Θ(capacity)；中英文复杂度表、README 摘要与 `indexof` 的复杂度行已同步更正。

**兼容**

- **内置函数体断代（carrier break）。** `copy` / `indexof` 的 `__ls_builtin_*` 函数体是编译期烘焙进保存产物的。用**旧版本**保存过、且用过这两个运算的处理器，在 v5.1.2 下重开时载体校验会失败，`array` 这类只存在于载体的卡片无法被恢复，编辑器回落到原版视图——**可执行的 mlog 不变、程序照常运行**，丢失的只是结构化编辑视图，需要重新拖出卡片。没用过这两个运算的存档不受影响。

## English

**Fixes**

- **`copy` no longer crosses its read/write bases.** `copy(dmem, dbase, smem, sbase, size)` used to read through the destination base and write through the source base, so copying between two arrays in the same memory block assigned **dst := src** (inverted), and cross-block copies wrote to the wrong region. It now reads with the source base (`sbase` + `smem`) and writes with the destination base (`dbase` + `dmem`).
- **`indexof` stops at the first hit.** The old body still read the whole array after a hit (the read ran before the "already found, continue" jump). It now breaks out of the loop on the first match and reads all `size` slots only on a miss. New `FakeMemory` read-counter cases pin hit / duplicate / miss, in both normal and inline mode.
- **Doc complexity corrections.** A miss or a new-key insert in `map` / `uset` cannot stop probing at an empty slot and is always Θ(capacity); the zh/en complexity tables, the README summary and the `indexof` complexity row now say so.

**Compatibility**

- **Builtin body break (carrier break).** The `copy` / `indexof` `__ls_builtin_*` bodies are baked into the saved product at compile time. A processor saved by an **older** build that used either operation will fail carrier verification when reopened under v5.1.2, so cards that live only in the carrier (such as `array`) are not recovered and the editor falls back to the vanilla view — the **executable mlog is unchanged and the program still runs**; only the structured editing view is lost, and the cards have to be placed again. Saves that never used these two operations are unaffected.