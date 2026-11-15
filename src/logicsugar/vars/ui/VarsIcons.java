package logicsugar.vars.ui;

import arc.scene.style.TextureRegionDrawable;
import logicsugar.assist.UiSprites;

/**
 * 变量/日志界面共用的自绘图标。日志图标来自上游 v0.12.0 的 {@code Icon2.log}。
 *
 * <p>图集前缀与缺图兜底由 {@link UiSprites} 统一处理，不要在这里另拼 mod 名。</p>
 */
public final class VarsIcons{
    public static final TextureRegionDrawable log = UiSprites.load("log");

    private VarsIcons(){
    }
}
