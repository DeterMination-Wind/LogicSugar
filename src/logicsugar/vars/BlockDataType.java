package logicsugar.vars;

import arc.Core;

/**
 * 变量视图的三种数据来源，以及各自的列宽上限（对话框布局用）。
 *
 * <p>Ported from upstream MlogAssertions v0.11.3 ({@code cardillan.mlogassertions.data.BlockDataType}).
 * 上游直接读 bundle 的 {@code variables}（Mindustry 核心包里的 "Vars"）、
 * {@code varsdialog.memory}、{@code varsdialog.properties}；这里改读 LogicSugar 自己的
 * {@code logicsugar.vars.blocktype.*} 键（中文由 LogicSugar 的 bundle 提供），键不存在时
 * 回落到上游显示用的英文文本。bundle 的键与文本在集成阶段加入，本文件只负责读取。</p>
 */
public enum BlockDataType{
    processor   (10000f, "logicsugar.vars.blocktype.processor", "Vars"),
    memory      (550f,   "logicsugar.vars.blocktype.memory", "Memory"),
    properties  (750f,   "logicsugar.vars.blocktype.properties", "Properties"),
    ;

    public final float maxColWidth;
    public final String name;

    BlockDataType(float maxColWidth, String key, String fallback){
        this.maxColWidth = maxColWidth;
        this.name = Core.bundle.get(key, fallback);
    }
}
