package logicsugar.vars;

import arc.Core;
import arc.func.Cons;
import arc.graphics.Color;
import arc.util.Align;
import mindustry.Vars;
import mindustry.core.GameState;

import java.util.Arrays;

/**
 * 变量数据层（{@code logicsugar.vars}）的无界面自检。
 *
 * <p>为什么单独一个自检：{@link MemoryText} 的导出/导入是本层唯一真正有「线格式」的部分
 * （内存表的列、类型名与转义规则，游戏里的导入就靠它还原整块内存），而它只依赖
 * {@link VariableValues} 接口，不需要处理器、内存块或任何活的建筑即可精确验证。
 * 断言用的是一个与 {@link MemoryVars} 同构（数值槽/对象槽分开、盘外 sentinel 表示数值槽）
 * 的纯数组替身，所以这里既覆盖上游语义又不需要加载地图。</p>
 *
 * <p>另外钉住两处「看起来像显示文本、其实是线格式」的事实：{@link ValueType#title} 与
 * {@link MemoryText} 的表头必须保持英文原样，否则导出过的文本再也导不回来。</p>
 */
public final class VarsDataTest{
    private VarsDataTest(){
    }

    public static void main(String[] args){
        // BaseVariableValues 的 timestamp 是实例字段，读 Vars.state.tick；自检里没有启动器，
        // 手工放一个 GameState（纯数据对象，不碰任何图形资源）。
        Vars.state = new GameState();

        valueTypeClassification();
        memoryTextRoundTrip();
        memoryTextRejectsBadLines();
        memoryTextSkipsUnimportable();
        memoryTextAcceptsNumericLiterals();
        blockDataTypeNames();
        varsOptionsDefaults();

        System.out.println("LogicSugar vars data self-test passed.");
    }

    // ===== 分类 =====

    private static void valueTypeClassification(){
        MemoryView view = new MemoryView(7);
        view.set(0, 0);
        view.set(1, 42);
        view.set(2, -7);
        view.set(3, 3.5);
        view.set(4, Color.white.toDoubleBits());
        view.set(5, "text");
        view.set(6, null);

        checkEquals("zero", view.type(0).name(), "0 是 zero（与 integer 共用显示名）");
        checkEquals("integer", view.type(1).name(), "42 是 integer");
        checkEquals("integer", view.type(2).name(), "-7 是 integer");
        checkEquals("number", view.type(3).name(), "3.5 是 number");
        checkEquals("color", view.type(4).name(), "white 的位模式落在颜色区间");
        checkEquals("string", view.type(5).name(), "字符串槽是 string");
        checkEquals("nothing", view.type(6).name(), "空对象槽是 nothing");

        check(ValueType.integer.numeric && ValueType.number.numeric && ValueType.color.numeric && ValueType.zero.numeric && ValueType.nothing.numeric, "数值类型标 numeric");
        check(!ValueType.string.numeric && !ValueType.link.numeric && !ValueType.content.numeric && !ValueType.unit.numeric, "对象类型不标 numeric");
        checkEquals(" integer ", ValueType.integer.paddedTitle, "paddedTitle 两侧留空格（显示用）");

        check(!view.isObj(1) && !view.isObj(2) && view.isObj(5) && view.isObj(6), "isObj 只对对象槽为 true");
        checkEquals("text", view.obj(5), "obj 返回字符串本身");
        check(view.obj(6) == null, "空对象槽的 obj 是 null");

        // ValueType.title 是线格式（MemoryText 的类型列），不能翻译
        checkEquals("null", ValueType.nothing.title, "nothing.title");
        checkEquals("integer", ValueType.zero.title, "zero.title 与 integer 相同（上游如此）");
        checkEquals("integer", ValueType.integer.title, "integer.title");
        checkEquals("number", ValueType.number.title, "number.title");
        checkEquals("color", ValueType.color.title, "color.title");
        checkEquals("string", ValueType.string.title, "string.title");
        checkEquals("link", ValueType.link.title, "link.title");
        checkEquals("unit", ValueType.unit.title, "unit.title");
        checkEquals("building", ValueType.building.title, "building.title");
    }

    // ===== 导出 / 导入往返 =====

    private static void memoryTextRoundTrip(){
        String escaped = "quote\" backslash\\ newline\ntab\tctrl\u0007 emoji\uD83D\uDE00";

        MemoryView source = new MemoryView(9);
        source.set(0, 42);
        source.set(1, -7);
        source.set(2, 3.5);
        source.set(3, Color.white.toDoubleBits());
        source.set(4, Double.NaN);
        source.set(5, Double.POSITIVE_INFINITY);
        source.set(6, escaped);
        source.set(7, null);
        source.set(8, 0);

        String text = MemoryText.write(source, false);
        MemoryView target = new MemoryView(9);

        checkEquals(null, MemoryText.validate(text, 9), "自己导出的文本必须通过校验");
        checkEquals(null, MemoryText.read(text, 9, target), "自己导出的文本必须能导入");

        check(target.num(0) == 42, "整数往返");
        check(target.num(1) == -7, "负数往返");
        check(target.num(2) == 3.5, "小数往返");
        check(Double.doubleToRawLongBits(target.num(3)) == Double.doubleToRawLongBits(Color.white.toDoubleBits()), "颜色按位模式往返");
        checkEquals("string", target.type(6).name(), "字符串槽仍是 string");
        checkEquals(escaped, target.obj(6), "引号/反斜杠/换行/制表/控制字符/代理对往返");
        check(target.type(7) == ValueType.nothing, "空对象往返仍是空对象");
        check(target.num(8) == 0 && !target.isObj(8), "0 往返");

        // 非有限数没有字面量，写成 null 后导入成空对象——这正是游戏处理它们的方式
        check(target.type(4) == ValueType.nothing && target.isObj(4) && target.obj(4) == null, "NaN 写成 null 后导入为空对象");
        check(target.type(5) == ValueType.nothing && target.isObj(5) && target.obj(5) == null, "Inf 写成 null 后导入为空对象");

        // 三列表格的确切文本（表头 + 十进制地址 + 类型名 + mlog 字面量）
        String expected =
                "Slot\tType\tValue\n" +
                "0\tinteger\t42\n" +
                "1\tinteger\t-7\n" +
                "2\tnumber\t3.5\n" +
                "3\tcolor\t%ffffffff\n" +
                "4\tnumber\tnull\n" +
                "5\tnumber\tnull\n" +
                "6\tstring\t\"quote\\\" backslash\\\\ newline\\ntab\\u0009ctrl\\u0007 emoji\\ud83d\\ude00\"\n" +
                "7\tnull\tnull\n" +
                "8\tinteger\t0\n";
        checkEquals(expected, text, "导出文本与上游线格式逐字一致");
    }

    // ===== 坏行 =====

    private static void memoryTextRejectsBadLines(){
        String text =
                "Slot\tType\tValue\n" +
                "0\tinteger\t5\n" +
                "x\tinteger\t1\n" +          // 3: 地址不是整数
                "1\tinteger\tnotanumber\n" + // 4: 数值字面量解析失败
                "2\tbogus\t3\n" +            // 5: 类型名不认识
                "3\tinteger\t7\textra\n" +   // 6: 列数不对
                "\n" +
                "5\tinteger\t9\n";           // 8: 地址超出容量（cap = 4）

        MemoryView target = new MemoryView(4);
        target.set(0, 111);

        checkEquals("3, 4, 5, 6, 8", MemoryText.validate(text, 4), "坏行按 1 起算的行号列出");

        // 两段式协议：validate 报错时调用方不会调用 read，目标保持原样
        check(target.num(0) == 111, "校验失败时目标不被修改");

        MemoryView applied = new MemoryView(4);
        applied.set(0, 111);
        checkEquals("3, 4, 5, 6, 8", MemoryText.read(text, 4, applied), "read 返回同一份行号清单");
        check(applied.num(0) == 5, "合法行照常写入");
        check(!applied.isObj(1) && applied.num(1) == 0, "坏行 4 的槽位保持原值");
        check(!applied.isObj(2) && applied.num(2) == 0, "坏行 5 的槽位保持原值");
        check(!applied.isObj(3) && applied.num(3) == 0, "坏行 6 的槽位保持原值");

        // 空文本是「没有要导入的内容」，不是错误
        checkEquals(null, MemoryText.read(null, 4, target), "null 文本不报错");
        checkEquals(null, MemoryText.read("  \n\n", 4, target), "空白文本不报错");
        check(target.num(0) == 111, "空文本不修改目标");
    }

    // ===== 不可还原的类型 =====

    private static void memoryTextSkipsUnimportable(){
        String text =
                "Slot\tType\tValue\n" +
                "0\tstring\tplain text without quotes\n" +
                "1\tlink\t5\n" +
                "2\tunit\t3\n" +
                "3\tnull\tnull\n";

        MemoryView target = new MemoryView(4);
        target.set(1, 123);

        checkEquals(null, MemoryText.validate(text, 4), "引用类型不算坏行");
        checkEquals(null, MemoryText.read(text, 4, target), "read 同样不报错");
        checkEquals("plain text without quotes", target.obj(0), "未加引号的字符串按原文导入");
        check(target.num(1) == 123 && !target.isObj(1), "link 行被静默跳过，旧值不变");
        check(target.type(3) == ValueType.nothing, "null 还原成空对象");
    }

    // ===== 数值字面量 =====

    private static void memoryTextAcceptsNumericLiterals(){
        String text =
                "Slot\tType\tValue\n" +
                "0\tinteger\t0x2A\n" +
                "1\tinteger\t-0x7\n" +
                "2\tinteger\t0b101\n" +
                "3\tcolor\t%ff0000\n" +
                "4\tnumber\t1.5e3\n";

        MemoryView target = new MemoryView(5);
        checkEquals(null, MemoryText.read(text, 5, target), "十六进制/二进制/颜色/科学计数法都能解析");

        check(target.num(0) == 42, "0x2A = 42");
        check(target.num(1) == -7, "-0x7 = -7");
        check(target.num(2) == 5, "0b101 = 5");
        check(Double.doubleToRawLongBits(target.num(3)) == 0xFF0000FFL, "%ff0000 的 alpha 补 255");
        check(target.num(4) == 1500, "1.5e3 = 1500");
    }

    // ===== 视图名与显示选项 =====

    private static void blockDataTypeNames(){
        // 名称读的是 logicsugar.vars.* 键；键不存在时（无界面自检、bundle 未集成）用上游英文文本。
        checkName(BlockDataType.processor, "logicsugar.vars.blocktype.processor", "Vars");
        checkName(BlockDataType.memory, "logicsugar.vars.blocktype.memory", "Memory");
        checkName(BlockDataType.properties, "logicsugar.vars.blocktype.properties", "Properties");

        check(BlockDataType.processor.maxColWidth == 10000f, "processor 列宽上限");
        check(BlockDataType.memory.maxColWidth == 550f, "memory 列宽上限");
        check(BlockDataType.properties.maxColWidth == 750f, "properties 列宽上限");
        check(SnapshotType.all.length == 3, "快照类型（isolated/connected/global）未变");
    }

    private static void varsOptionsDefaults(){
        check(!VarsOptions.hex, "hex 默认关闭（十进制显示）");
        check(VarsOptions.sorted, "处理器变量默认排序");
        check(!VarsOptions.filtered, "默认不隐藏临时变量");
        check(!VarsOptions.hideLinks, "默认不隐藏链接变量");
        check(!VarsOptions.fullPrecision, "默认按 significantDigits 显示");
        check(VarsOptions.significantDigits == 7, "默认 7 位有效数字");
        check(VarsOptions.alignment == Align.right, "数值列默认右对齐");
        check(VarsOptions.updateFrequency == 15, "默认每 15 帧刷新一次");
        check(Double.doubleToRawLongBits(VarsOptions.COLOR_LIMIT) == Double.doubleToRawLongBits(Color.white.toDoubleBits()), "COLOR_LIMIT 是 white 的位模式");
    }

    // ===== 断言工具 =====

    private static void checkName(BlockDataType type, String key, String fallback){
        check(type.name != null && !type.name.isEmpty(), "视图名不能为空");
        checkEquals(Core.bundle.get(key, fallback), type.name, "视图名来自 " + key + "（缺失时回落 " + fallback + "）");
    }

    private static void check(boolean condition, String message){
        if(!condition) throw new AssertionError(message);
    }

    private static void checkEquals(String expected, String actual, String message){
        if(expected == null ? actual != null : !expected.equals(actual)){
            throw new AssertionError(message + ": expected <" + expected + "> but was <" + actual + ">");
        }
    }

    /** 同 {@link #checkEquals(String, String, String)}，但接受任意对象槽的值（可为 null）。 */
    private static void checkEquals(String expected, Object actual, String message){
        if(expected == null ? actual != null : !expected.equals(actual)){
            throw new AssertionError(message + ": expected <" + expected + "> but was <" + actual + ">");
        }
    }

    // ===== 纯数组替身 =====

    /**
     * 与 {@link MemoryVars} 同构的内存槽位替身：数值槽存 {@code double}，对象槽存对象，
     * 用一个本地 sentinel 区分两者（上游用的是 {@code MemoryBuild} 的私有静态 sentinel，
     * 这里不需要活建筑）。语义与上游 MemoryVars 一致：{@code set(index, null)} 写的是
     * 「空对象槽」，与数值槽是两个不同的状态。
     */
    private static final class MemoryView extends BaseVariableValues{
        private static final Object numberSlot = new Object();

        private final Object[] objectMemory;
        private final double[] numberMemory;

        MemoryView(int capacity){
            super(null);
            objectMemory = new Object[capacity];
            numberMemory = new double[capacity];
            Arrays.fill(objectMemory, numberSlot);
        }

        @Override
        public BlockDataType dataType(){
            return BlockDataType.memory;
        }

        @Override
        public int size(){
            return objectMemory.length;
        }

        @Override
        public String label(int index, boolean hex){
            return " " + (hex ? Integer.toHexString(index).toUpperCase() : Integer.toString(index)) + " ";
        }

        @Override
        public boolean isObj(int index){
            return objectMemory[index] != numberSlot;
        }

        @Override
        public boolean isLink(int index){
            return false;
        }

        @Override
        public Object obj(int index){
            return objectMemory[index];
        }

        @Override
        public double num(int index){
            return numberMemory[index];
        }

        @Override
        public String textBuffer(){
            return "";
        }

        @Override
        public void clear(){
            Arrays.fill(objectMemory, numberSlot);
            Arrays.fill(numberMemory, 0);
        }

        @Override
        public void set(int index, double value){
            objectMemory[index] = numberSlot;
            numberMemory[index] = value;
        }

        @Override
        public void set(int index, Object value){
            objectMemory[index] = value;
        }

        @Override
        public void setView(boolean sorted, boolean filtered, boolean hideLinks){
        }

        @Override
        public void eachObject(Cons<Object> getter){
            for(int index = 0; index < objectMemory.length; index++){
                if(objectMemory[index] != numberSlot) getter.get(objectMemory[index]);
            }
        }
    }
}
