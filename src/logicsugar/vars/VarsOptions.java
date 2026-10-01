package logicsugar.vars;

import arc.graphics.Color;
import arc.util.Align;

/**
 * 变量视图的会话级显示状态。上游把它散放在 {@code VarsDialog} 的静态字段里；这里单独成类，
 * 由 LogicSugar 的设置项写入，由变量对话框读取。
 *
 * <p>Ported from upstream MlogAssertions v0.11.1 ({@code cardillan.mlogassertions.ui.VarsDialog}
 * 的静态字段 + {@code Constants.COLOR_LIMIT}）。{@link #COLOR_LIMIT} 是「数值落在颜色区间」
 * 的上界（white 的位模式，见 {@link BaseVariableValues#type}），不是可由用户修改的显示偏好。</p>
 */
public final class VarsOptions{
    /** 数值行显示为十六进制（{@code formatted}/{@link MemoryText#write} 的 hex 参数）。 */
    public static boolean hex = false;
    /** 处理器变量按名字排序（内存视图不支持排序）。 */
    public static boolean sorted = true;
    /** 隐藏程序临时变量（{@code *tmp*}）。 */
    public static boolean filtered = false;
    /** 隐藏链接变量（常量且首字符不是 '@'）。 */
    public static boolean hideLinks = false;
    /** 小数显示全部有效位（{@code significantDigits} 按 16 处理）。 */
    public static boolean fullPrecision = false;
    /** 有限小数显示的有效位数（1..15；16 及以上按 double 全精度）。 */
    public static int significantDigits = 7;
    /** 数值列对齐方式（{@link Align#left}/{@link Align#center}/{@link Align#right}）。 */
    public static int alignment = Align.right;
    /** 活数据刷新的帧间隔。 */
    public static int updateFrequency = 15;
    /** 「颜色」判定上界：white 的位模式。 */
    public static final double COLOR_LIMIT = Color.white.toDoubleBits();

    private VarsOptions(){
    }
}
