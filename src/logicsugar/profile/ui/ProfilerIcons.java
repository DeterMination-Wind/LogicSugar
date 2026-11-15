package logicsugar.profile.ui;

import arc.scene.style.TextureRegionDrawable;
import logicsugar.assist.UiSprites;

/**
 * ProfileDialog 的 6 张自绘图标（上游 MlogAssertions v0.11.3 的 {@code Icon2}）。
 *
 * <p>图标文件放在 {@code assets/sprites/ui/*.png}，图集前缀的解析与缺图兜底统一由
 * {@link UiSprites} 负责（dev / 正式 / Neon 聚合三形态）。</p>
 */
public final class ProfilerIcons{
    public static final TextureRegionDrawable branching = UiSprites.load("branching");
    public static final TextureRegionDrawable percent = UiSprites.load("percent");
    public static final TextureRegionDrawable sortAsc = UiSprites.load("sort-asc");
    public static final TextureRegionDrawable sortDesc = UiSprites.load("sort-desc");
    public static final TextureRegionDrawable sum = UiSprites.load("sum");
    public static final TextureRegionDrawable time = UiSprites.load("time");

    private ProfilerIcons(){
    }
}
