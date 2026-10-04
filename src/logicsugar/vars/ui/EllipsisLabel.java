package logicsugar.vars.ui;

import arc.graphics.Color;
import arc.graphics.g2d.Font;
import arc.graphics.g2d.GlyphLayout;
import arc.scene.ui.Label;
import arc.util.Align;

/**
 * 超出可用高度时自动截断并在末尾补省略号的标签（变量表的值列用它限制为两行）。
 *
 * <p>Ported from upstream MlogAssertions v0.11.3
 * ({@code cardillan.mlogassertions.ui.EllipsisLabel})；上游那行注释掉的
 * {@code originalText.replace("\n", " ")} 实验代码没有移植，其余逐字保留。</p>
 *
 * <p>截断用二分查找：先测原文是否放得下，放不下时在整个字符串长度上二分找最长前缀，
 * 所以每帧重排的代价是 O(log n) 次 {@link GlyphLayout} 测量而不是线性扫描。
 * v0.11.3 给原文加了 {@link #maxStringLength} 上限：MB 级字符串否则会让每次测量
 * （尤其二分里「放不下」的候选）都与整串一起做 GlyphLayout，直接卡住渲染线程。</p>
 */
public class EllipsisLabel extends Label{
    /** 放进 {@link GlyphLayout} 测量与二分上界的最长原文：MB 级字符串（内存块里的长文本、
     *  被截断的 mlog 字符串）会让二分退化成每次 layout 都与整个字符串一起测量，帧率骤降；
     *  超过这个长度就只保留前缀（上游 v0.11.3 的修复，见 {@link #setText}）。 */
    public static final int maxStringLength = 256;

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
        // 超长文本只留前 maxStringLength 个字符（二分上界与测量代价都由此封顶）。
        originalText = truncate(text);

        if(!updating){
            super.setText(originalText);
        }else{
            super.setText(text);
        }
    }

    /** 记录的原文：{@code null} 当空串，超长只保留 {@link #maxStringLength} 个字符。
     *  单独成 public static 方法是为了让无头自检能直接验证截断（本类构造需要活场景）。 */
    public static String truncate(CharSequence text){
        if(text == null) return "";
        return text.length() > maxStringLength ? text.subSequence(0, maxStringLength).toString() : text.toString();
    }

    /** 在 {@code 0..length} 上二分找「最后一个满足 fits 的前缀长度」，返回 0 表示连一个
     *  字符都放不下。{@code fits(len)} 负责真实测量（layout 里是 GlyphLayout 的高度），
     *  二分次数是 O(log length)——MB 级字符串也只做十余次测量，绝不会线性变慢。
     *  与 {@link #truncate} 一样单独抽出供无头自检计数。 */
    public static int fittingPrefix(int length, java.util.function.IntPredicate fits){
        int low = 0;
        int high = Math.max(0, length);

        while(low < high){
            int mid = (low + high + 1) / 2;

            if(fits.test(mid)){
                low = mid;
            }else{
                high = mid - 1;
            }
        }

        return low;
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

        // 只有短文本才值得先试「整串放得下」；长文本必然放不下，直接进二分。
        if(originalText.length() < maxStringLength){
            layout.setText(font, originalText, Color.white, getWidth(), Align.left, true);

            if(layout.height <= maxHeight){
                super.layout();
                return;
            }
        }

        int low = fittingPrefix(originalText.length(), mid -> {
            String candidate = originalText.substring(0, mid).stripTrailing() + "...";
            layout.setText(font, candidate, Color.white, getWidth(), Align.left, true);
            return layout.height <= maxHeight;
        });

        updating = true;
        // "..." 是纯标点，不需要 bundle 键。
        super.setText(originalText.substring(0, low).stripTrailing() + "...");
        updating = false;

        super.layout();
    }
}
