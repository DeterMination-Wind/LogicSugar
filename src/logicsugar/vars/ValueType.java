package logicsugar.vars;

import arc.graphics.Color;
import mindustry.graphics.Pal;

/**
 * 变量视图一行里的数据类型：决定该行按数值还是按对象解释、用什么颜色显示，以及
 * 在存储快照时如何分类统计。
 *
 * <p>Ported from upstream MlogAssertions v0.11.1 ({@code cardillan.mlogassertions.data.ValueType}),
 * verbatim. {@link #title} 是<b>线格式</b>的一部分：{@link MemoryText} 导出的内存表把类型
 * 写成 title，导入时要求字符串完全相等才认得出（{@code MemoryText.type(String)}），
 * 所以这些英文名不能翻译、不能改名。{@link #paddedTitle} 与两个 shade 只用于显示。</p>
 */
public enum ValueType{
    nothing     ("null",     true,  Color.darkGray),
    zero        ("integer",  true,  new Color(0x4f4f4fff)),
    color       ("color",    true,  Pal.berylShot),
    integer     ("integer",  true,  Pal.logicWorld),
    number      ("number",   true,  Pal.place),
    link        ("link",     false, Pal.tungstenShot),
    string      ("string",   false, Pal.ammo.cpy().mul(0.75f)),
    content     ("content",  false, Pal.logicOperations),
    building    ("building", false, Pal.logicBlocks),
    unit        ("unit",     false, Pal.logicUnits),
    team        ("team",     false, Pal.logicControl),
    enumerated  ("enum",     false, Pal.logicIo),
    dead        ("dead",     false, Pal.rubble),
    unknown     ("unknown",  false, Color.white),
    ;

    public final String title;
    public final boolean numeric;
    public final String paddedTitle;
    public final Color shade;
    public final Color darkShade;

    ValueType(String title, boolean numeric, Color shade){
        this.title = title;
        this.numeric = numeric;
        this.paddedTitle = " " + title + " ";
        this.shade = shade;
        this.darkShade = shade.cpy().mul(0.5f);
    }
}
