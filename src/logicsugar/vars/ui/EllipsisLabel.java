package logicsugar.vars.ui;

import arc.graphics.Color;
import arc.graphics.g2d.Font;
import arc.graphics.g2d.GlyphLayout;
import arc.scene.ui.Label;
import arc.util.Align;

/**
 * 超出可用高度时自动截断并在末尾补省略号的标签（变量表的值列用它限制为两行）。
 *
 * <p>Ported from upstream MlogAssertions v0.11.1
 * ({@code cardillan.mlogassertions.ui.EllipsisLabel})；上游那行注释掉的
 * {@code originalText.replace("\n", " ")} 实验代码没有移植，其余逐字保留。</p>
 *
 * <p>截断用二分查找：先测原文是否放得下，放不下时在整个字符串长度上二分找最长前缀，
 * 所以每帧重排的代价是 O(log n) 次 {@link GlyphLayout} 测量而不是线性扫描。</p>
 */
public class EllipsisLabel extends Label{

    /** 用户设置的原文；{@link #layout()} 只把截断后的文本交给父类。 */
    private String originalText;
    private int maxLines = 1;
    /** 为 true 时正在写回截断文本，{@link #setText} 不能再记录成新的原文。 */
    private boolean updating;

    public EllipsisLabel(CharSequence text){
        super(text);
        originalText = text == null ? "" : text.toString();
        setWrap(true);
    }

    public EllipsisLabel maxLines(int maxLines){
        if(maxLines < 1) throw new IllegalArgumentException("maxLines must be positive");
        this.maxLines = maxLines;
        invalidate();
        return this;
    }

    @Override
    public void setText(CharSequence text){
        originalText = text == null ? "" : text.toString();

        if(!updating){
            super.setText(originalText);
        }else{
            super.setText(text);
        }
    }

    @Override
    public void layout(){
        if(updating || getWidth() <= 0 || originalText.isEmpty()){
            super.layout();
            return;
        }

        Font font = getStyle().font;
        float maxHeight = font.getCapHeight() + (maxLines - 1) * font.getLineHeight();

        // 与父类的 layout 字段同名，这里是刻意遮蔽：只用来测量候选文本，不写回父类状态。
        GlyphLayout layout = new GlyphLayout();

        layout.setText(font, originalText, Color.white, getWidth(), Align.left, true);

        if(layout.height <= maxHeight){
            super.layout();
            return;
        }

        int low = 0;
        int high = Math.min(200, originalText.length());

        while(low < high){
            int mid = (low + high + 1) / 2;

            String candidate = originalText.substring(0, mid).stripTrailing() + "...";
            layout.setText(font, candidate, Color.white, getWidth(), Align.left, true);

            if(layout.height <= maxHeight){
                low = mid;
            }else{
                high = mid - 1;
            }
        }

        updating = true;
        // "..." 是纯标点，不需要 bundle 键。
        super.setText(originalText.substring(0, low).stripTrailing() + "...");
        updating = false;

        super.layout();
    }
}
