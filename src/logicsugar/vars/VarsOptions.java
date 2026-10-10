package logicsugar.vars;

import arc.Core;
import arc.graphics.Color;
import arc.util.Align;

/**
 * 变量视图的显示状态。上游把它散放在 {@code VarsDialog} 的静态字段里；这里单独成类：五个视图开关
 * 由变量对话框读写并持久化（{@link #load()}/{@link #save()}），其余显示设置（有效位数 / 对齐 /
 * 刷新频率）由 LogicSugar 的设置页写入、由变量对话框读取。
 *
 * <p>Ported from upstream MlogAssertions v0.11.6 ({@code cardillan.mlogassertions.ui.VarsDialog}
 * 的静态字段 + {@code Constants.COLOR_LIMIT}）。{@link #COLOR_LIMIT} 是「数值落在颜色区间」
 * 的上界（white 的位模式，见 {@link BaseVariableValues#type}），不是可由用户修改的显示偏好。</p>
 *
 * <p>五个视图开关（hex / sorted / filtered / hideLinks / fullPrecision）在界面上切换时写进
 * {@code Core.settings}，启动时由 {@link #load()} 读回（上游 v0.11.6 的会话级开关也是持久化的）。
 * 键名前缀沿用本 mod 的 {@code logicsugar.*}，不跟上游的 {@code mlogdevtools-*}：那些键属于上游的
 * 身份/设置命名空间，改了等于让既有用户的偏好失效（与「不跟随身份类改动」同一条理由）。</p> */
public final class VarsOptions{
    /** 持久化键（与上游同名设置的语义对应，但用本 mod 的键前缀）。 */
    public static final String keyHex = "logicsugar.varsHex";
    public static final String keySorted = "logicsugar.varsSorted";
    public static final String keyHideTemps = "logicsugar.varsHideTemps";
    public static final String keyHideLinks = "logicsugar.varsHideLinks";
    public static final String keyFullPrecision = "logicsugar.varsFullPrecision";

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

    /** 读回五个视图开关（启动时由 {@code VarsAccess.applySettings} 调）。
     *  {@code Core.settings == null}（无头自检、启动早期）时静默跳过，保留当前值。 */
    public static void load(){
        if(Core.settings == null) return;
        hex = Core.settings.getBool(keyHex, hex);
        sorted = Core.settings.getBool(keySorted, sorted);
        filtered = Core.settings.getBool(keyHideTemps, filtered);
        hideLinks = Core.settings.getBool(keyHideLinks, hideLinks);
        fullPrecision = Core.settings.getBool(keyFullPrecision, fullPrecision);
    }

    /** 写回五个视图开关（对话框的每个显示开关都经由 {@code refreshView}/{@code updateView} 调这里）。
     *  {@code Core.settings == null} 时静默跳过（无头自检）。 */
    public static void save(){
        if(Core.settings == null) return;
        Core.settings.put(keyHex, hex);
        Core.settings.put(keySorted, sorted);
        Core.settings.put(keyHideTemps, filtered);
        Core.settings.put(keyHideLinks, hideLinks);
        Core.settings.put(keyFullPrecision, fullPrecision);
    }

    private VarsOptions(){
    }
}
