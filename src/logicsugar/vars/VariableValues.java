package logicsugar.vars;

import arc.func.Cons;
import mindustry.gen.Building;

/**
 * 一个变量视图的数据源：可以是一块处理器的活变量、一块内存、一个建筑的传感器读数，
 * 也可以是它们的快照。
 *
 * <p>Ported from upstream MlogAssertions v0.11.1 ({@code cardillan.mlogassertions.data.VariableValues}),
 * verbatim. 行按 index 访问（{@link #size()} 是行数）；{@link #isObj(int)} 决定该行走
 * {@link #obj(int)} 还是 {@link #num(int)}，两者对同一行只有一个是有效的。</p>
 */
public interface VariableValues{
    long timestamp();
    String time();

    BlockDataType dataType();
    Building building();
    String buildingDesc();
    String buildingPos();
    String buildingDescMulti();

    /** 是否是活数据（快照返回 false）。 */
    boolean live();
    /** 数据源此刻是否可用（活数据恒为 true）。 */
    boolean valid();

    int size();
    String label(int index, boolean hex);
    String formatted(int index, boolean hex, int significantDigits);

    /** 用于复制到剪贴板的文本形式：字符串不加引号，其余与 {@link #formatted} 相同。 */
    String clipboard(int index, boolean hex);
    ValueType type(int index);

    boolean isObj(int index);
    boolean isLink(int index);
    Object obj(int index);
    double num(int index);

    String textBuffer();

    void clear();
    void setView(boolean sorted, boolean filtered, boolean hideLinks);

    void eachObject(Cons<Object> getter);

    /** Stores a number into the given slot. Values of sources which cannot be modified are ignored. */
    default void set(int index, double value){
    }

    /** Stores an object (a String or null) into the given slot. Values of sources which
     * cannot be modified are ignored. */
    default void set(int index, Object value){
    }
}
