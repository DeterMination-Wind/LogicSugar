package lab;

import java.util.ArrayList;
import java.util.List;

/**
 * 展台表：25 个处理器展台的位置、说明板文本、道具与演示源码文件名。
 *
 * <p>布局是 5x5 的方阵，行 = 主题分类，列 = 该分类下的一站：</p>
 * <pre>
 *   行 0  控制流      if/elif/else · while · for · switch · 短路条件
 *   行 1  表达式/函数 Expr 卡 · 随处表达式 · funcdef · 函数库 · 单位控制卡
 *   行 2  数据结构 I  array · 批量运算 · matrix · span · record
 *   行 3  数据结构 II stack/queue/deque · bitset · map/uset · list/heap · chain
 *   行 4  调试与编辑  状态指示 · 断言 · 跳转线着色 · 反编译重建 · 编辑器辅助
 * </pre>
 */
final class LabStations{
    private LabStations(){}

    /** 一块额外方块（内存块、显示屏…）。{@code link} 非空时会作为处理器的链接变量。 */
    record Prop(String block, int dx, int dy, String link){
    }

    /** 附加处理器（自带一块输出板，链接名同样是 {@code board}）。 */
    record Extra(String demo, String processor, int dx, int dy, int boardDx, int boardDy){
    }

    /** 地图里额外放置的单位（用于单位控制卡 / 单位 flag 显示展台）。 */
    record ExtraUnit(String type, int dx, int dy){
    }

    record Station(String demo, String title, String info, int row, int col, String processor,
                   List<Prop> props, List<Extra> extras, List<ExtraUnit> units,
                   boolean assertEmit, boolean vanillaCode, String library){
    }

    private static final String OUTPUT_PLACEHOLDER = "[lightgray]（运行后这里显示结果）";

    static String outputPlaceholder(){
        return OUTPUT_PLACEHOLDER;
    }

    static List<Station> all(){
        List<Station> out = new ArrayList<>();

        // ---- 行 0：控制流 ----
        out.add(station("if-else", "1. if / elif / else", 0, 0, "logic-processor", null)
            .info("[accent]1. if / elif / else[]\n"
                + "条件分支直接拖积木，条件里还能嵌 [accent]Expr[] 表达式（hp < 25）。\n"
                + "保存时整块降级成原版 jump，任何原版客户端都能打开、能运行。\n"
                + "[lightgray]点开左侧处理器看糖码；输出板显示当前分支。").build());

        out.add(station("while", "2. while + break / continue", 0, 1, "logic-processor", null)
            .info("[accent]2. while + break / continue[]\n"
                + "continue 跳过奇数、break 在 i>10 时退出，两者都归属最近的循环，\n"
                + "嵌套循环里也不会跳错层。\n"
                + "[lightgray]输出板显示 2+4+6+8+10 的结果。").build());

        out.add(station("for", "3. for 循环", 0, 2, "logic-processor", null)
            .info("[accent]3. for 循环[]\n"
                + "一张卡写全：循环变量 / 初值 / 步长 / 条件。\n"
                + "保存时编译成「先检查、后回跳」的原版指令。\n"
                + "[lightgray]输出板显示 1+2+…+10 的结果。").build());

        out.add(station("switch", "4. switch / case / default", 0, 3, "logic-processor", null)
            .info("[accent]4. switch / case / default[]\n"
                + "一个值匹配多个分支；case 值都是小整数时，编译器可以按代价\n"
                + "选择「比较链」或「@counter 跳转表」（设置里可强制 chainOnly）。\n"
                + "[lightgray]输出板显示匹配到的分支。").build());

        out.add(station("exprsc", "5. 短路条件 exprsc", 0, 4, "logic-processor", null)
            .info("[accent]5. 短路条件 exprsc[]\n"
                + "exprsc 里的 && / || 按短路求值：左边能定结果时右边不求值，\n"
                + "每个子条件编译成独立的条件跳转。\n"
                + "[lightgray]切到 Original 视图能看清短路跳转的形状。").build());

        // ---- 行 1：表达式与函数 ----
        out.add(station("expr-card", "6. Expr 卡", 1, 0, "logic-processor", null)
            .info("[accent]6. Expr 卡[]\n"
                + "一整行表达式写在一张卡上：area = w * h、diag = sqrt(w*w + h*h)。\n"
                + "保存时展开成 op 指令，重开时自动折回卡片；写错当场标红。\n"
                + "[lightgray]输出板显示计算结果。").build());

        out.add(station("expr-anywhere", "7. 随处表达式", 1, 1, "logic-processor", null)
            .info("[accent]7. 随处表达式[]\n"
                + "函数实参、return 返回值、if / while 条件里都能直接写表达式，\n"
                + "不必先算进临时变量。\n"
                + "[lightgray]输出板显示 midpoint(6, 12) 的返回值。").build());

        out.add(station("func", "8. funcdef 函数", 1, 2, "logic-processor", null)
            .info("[accent]8. funcdef 函数[]\n"
                + "带参数、带返回值的自定义函数。normal 模式（默认）让多次调用共享\n"
                + "同一段子程序体；inline 模式在每个调用点内联展开，设置里可切换。\n"
                + "[lightgray]两个函数各调用一次，结果在输出板。").build());

        out.add(station("funclib", "9. 函数库 functions.txt", 1, 3, "logic-processor", "library.txt")
            .info("[accent]9. 函数库 functions.txt[]\n"
                + "所有处理器共享的全局函数库（设置 ⇒ 函数库，上限 10000 条语句）。\n"
                + "保存时只把用到的子集写进载体（set __ls_lib \"...\"），\n"
                + "别人没有这份库也能重开这个处理器。").build());

        out.add(station("unitfor", "10. 单位控制卡 unitfor", 1, 4, "logic-processor", null)
            .units(new ExtraUnit("poly", 4, 6), new ExtraUnit("poly", 6, 6),
                new ExtraUnit("flare", 4, 9), new ExtraUnit("flare", 6, 9))
            .info("[accent]10. 单位控制卡 unitfor / unitbind[]\n"
                + "unitfor 遍历本队 @poly（本区 2 只）并逐个绑进变量，unitbind 再绑定一只读它的血量。\n"
                + "另两只 @flare 打上 flag 7：配合设置里的「单位 flag 显示」，单位头顶会画出彩色数字。\n"
                + "[lightgray]坑：单位卡用 flag 认领单位（只认 flag 0 或本处理器 uid），\n"
                + "给 unitfor 正在遍历的那类单位打自定义 flag，下一轮就会被当成别人的单位、数量变 0。").build());

        // ---- 行 2：数据结构 I ----
        out.add(station("array", "11. array 数组 + buf[i]", 2, 0, "logic-processor", null)
            .prop("memory-cell", 2, 6, "cell1")
            .info("[accent]11. array 数组 + 下标糖 buf[i][]\n"
                + "声明卡只是编译期元数据（不产出任何 mlog 行），buf[i] 编译成原版 read/write；\n"
                + "区间越界在保存时就能发现。\n"
                + "[lightgray]输出板显示 buf[5]。").build());

        out.add(station("array-bulk", "12. 数组批量运算卡", 2, 1, "logic-processor", null)
            .prop("memory-cell", 2, 6, "cell1")
            .info("[accent]12. 数组批量运算卡（datacall）[]\n"
                + "fill / sort / sum / min / find … 整段数组运算各是一张卡，\n"
                + "每个实参一个输入框，悬停可见参数名。\n"
                + "[lightgray]输出板显示排序后的 sum / min / 下标。").build());

        out.add(station("matrix", "13. matrix 矩阵", 2, 2, "logic-processor", null)
            .prop("memory-cell", 2, 6, "cell1")
            .info("[accent]13. matrix 矩阵[]\n"
                + "二维数组，m[行][列] 按行主序展开成一维地址，\n"
                + "下标糖与数组一致（m[1][2] = 42 直接写）。").build());

        out.add(station("span", "14. span 合并内存", 2, 3, "logic-processor", null)
            .prop("memory-cell", 2, 6, "cell1")
            .prop("memory-cell", 4, 6, "cell2")
            .info("[accent]14. span 合并内存[]\n"
                + "把多块等容量内存拼成一段连续逻辑地址：span big \"cell1 + cell2\"。\n"
                + "buf[9] 落在 cell1、buf[12] 落在 cell2，换算全部由编译器完成。").build());

        out.add(station("record", "15. record 记录", 2, 4, "logic-processor", null)
            .info("[accent]15. record 记录[]\n"
                + "编译期的结构体：字段就是普通 mlog 变量（p.hp ⇒ p_hp），\n"
                + "读写都是 O(1)，展开后没有任何额外开销。\n"
                + "[lightgray]输出板显示 p.hp / p.team。").build());

        // ---- 行 3：数据结构 II ----
        out.add(station("containers", "16. stack / queue / deque", 3, 0, "logic-processor", null)
            .prop("memory-cell", 2, 6, "cell1")
            .info("[accent]16. stack / queue / deque[]\n"
                + "三种容器共用一块内存的不同地址段（栈 0、队列 8、双端队列 16）。\n"
                + "getter 糖 s.top() / q.front() / d.back() 与运算卡完全等价。").build());

        out.add(station("bitset", "17. bitset 位集", 3, 1, "logic-processor", null)
            .prop("memory-cell", 2, 6, "cell1")
            .info("[accent]17. bitset 位集[]\n"
                + "一个槽存 64 个布尔位；b[i] 是位下标糖，\n"
                + "set / reset / test / count 都是 datacall 运算卡。").build());

        out.add(station("map-uset", "18. map / uset", 3, 2, "logic-processor", null)
            .prop("memory-cell", 2, 6, "cell1")
            .info("[accent]18. map 哈希表 / uset 无序集合[]\n"
                + "m[k] 等价于 map_get，u.has(v) / u.size() 等价于集合运算卡。\n"
                + "键与元素都是数值，容量由声明卡的内存区间决定。").build());

        out.add(station("list-heap", "19. list / heap", 3, 3, "logic-processor", null)
            .prop("memory-cell", 2, 6, "cell1")
            .info("[accent]19. list 列表 / heap 小顶堆[]\n"
                + "list 是紧凑顺序表（vector_*），heap 用小顶堆反复取最小值。\n"
                + "插入删除要移动元素，复杂度与 C++ STL 不完全相同（见教程）。").build());

        out.add(station("chain", "20. chain 链表", 3, 4, "logic-processor", null)
            .prop("memory-cell", 2, 6, "cell1")
            .info("[accent]20. chain 链表[]\n"
                + "每个节点占「值槽 + next 槽」，空闲链由 chain_alloc / chain_free 管理。\n"
                + "c[i] / c.next(i) / c.len() 是方法糖。").build());

        // ---- 行 4：调试与编辑器 ----
        out.add(station("status", "21. 处理器状态指示", 4, 0, "logic-processor", null)
            .extra(new Extra("wait", "logic-processor", 2, 6, 7, 6))
            .info("[accent]21. 处理器状态指示[]\n"
                + "停机：程序执行到 stop 后停住，头顶显示「已停机于第 N 条」。\n"
                + "长等待：wait 超过阈值时头顶画一圈进度圆环（阈值与扫描频率在设置里）。\n"
                + "[lightgray]本区两块处理器：上面的停机，下面的在 wait 5 秒循环里。").build());

        out.add(station("assert", "22. 断言语句（调试构建）", 4, 1, "logic-processor", null)
            .assertEmit()
            .info("[accent]22. 断言语句 assert（调试构建）[]\n"
                + "assert 卡记下期望值；AssertEmit = emit 时写成真实的断言指令，\n"
                + "运行时失败会把期望值 / 实际值直接显示在处理器头顶。\n"
                + "[scarlet]这是调试构建：含非原版指令，联机与纯原版客户端会显示成无效语句。\n"
                + "[]把设置里的断言构建改回 strip 重新保存，即可还原成原版兼容程序。").build());

        out.add(station("jump-lines", "23. 跳转线着色", 4, 2, "logic-processor", null)
            .info("[accent]23. 跳转线着色[]\n"
                + "结构语句的回跳与分支都编译成原版 jump，编辑器里画成跳转线；\n"
                + "设置里「跳转线着色」三档：关闭 / 按目标分散配色 / 用目标积木的分类色。\n"
                + "本程序有 while 回跳与 if / else 两条分支的跳转线。\n"
                + "[lightgray]切到 Original 视图能看到它们对应的 jump 指令。").build());

        out.add(station("rebuild", "24. 从源码重建（反编译）", 4, 3, "logic-processor", null)
            .vanillaCode()
            .info("[accent]24. 从源码重建（反编译）[]\n"
                + "这个处理器里保存的是手写的原版 mlog，没有 LogicSugar 载体。\n"
                + "打开编辑器时 LogicSugar 会尝试把 jump 结构恢复成循环积木，\n"
                + "并在顶部提示「由原版指令推断」——随时可以切回 Original 视图。\n"
                + "[lightgray]推断只在重编译校验通过时生效，认不出的部分保持原版。").build());

        out.add(station("editor-qol", "25. 编辑器辅助功能", 4, 4, "logic-processor", null)
            .info("[accent]25. 编辑器辅助功能[]\n"
                + "· 撤销 / 重做：Ctrl+Z / Ctrl+Y（手机在底栏）\n"
                + "· Ctrl+点击、Ctrl+拖动复制积木；Ctrl+C / Ctrl+V 跨处理器复制糖码\n"
                + "· 悬停提示超宽自动折行；搜索高亮；框选拖动；跳转线着色\n"
                + "· 编辑菜单：复制全部变量（精确表格）/ 复制打印缓冲\n"
                + "· 底部显示编译后指令条数 / 上限；设置里可切换「逻辑编辑器冲突」策略\n"
                + "[lightgray]点开处理器，随手拖一张积木试试。").build());

        return out;
    }

    /** 展台构造器：默认摆件是「处理器 + 说明板 + 输出板」。 */
    private static final class Builder{
        final String demo, title;
        final int row, col;
        final String processor;
        final String library;
        String info = "";
        boolean assertEmit, vanillaCode;
        final List<Prop> props = new ArrayList<>();
        final List<Extra> extras = new ArrayList<>();
        final List<ExtraUnit> units = new ArrayList<>();

        Builder(String demo, String title, int row, int col, String processor, String library){
            this.demo = demo;
            this.title = title;
            this.row = row;
            this.col = col;
            this.processor = processor;
            this.library = library;
        }

        Builder info(String value){
            info = value;
            return this;
        }

        Builder prop(String block, int dx, int dy, String link){
            props.add(new Prop(block, dx, dy, link));
            return this;
        }

        Builder extra(Extra value){
            extras.add(value);
            return this;
        }

        Builder units(ExtraUnit... values){
            for(ExtraUnit value : values) units.add(value);
            return this;
        }

        Builder assertEmit(){
            assertEmit = true;
            return this;
        }

        Builder vanillaCode(){
            vanillaCode = true;
            return this;
        }

        Station build(){
            return new Station(demo, title, info, row, col, processor, props, extras, units,
                assertEmit, vanillaCode, library);
        }
    }

    private static Builder station(String demo, String title, int row, int col, String processor, String library){
        return new Builder(demo, title, row, col, processor, library);
    }
}
