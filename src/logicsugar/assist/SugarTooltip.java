package logicsugar.assist;

import arc.Core;
import arc.func.Floatf;
import arc.graphics.Color;
import arc.graphics.g2d.Font;
import arc.graphics.g2d.GlyphLayout;
import arc.scene.Element;
import arc.scene.ui.Label;
import arc.scene.ui.Tooltip;
import arc.scene.ui.layout.Cell;
import arc.scene.ui.layout.Scl;
import mindustry.ui.Fonts;
import mindustry.ui.Styles;

import java.util.Locale;

/**
 * Hover hints that stay on screen.
 *
 * <p>Vanilla builds every tooltip out of one unbounded {@link Label}: {@code LCanvas.tooltip} and
 * {@code UI.addDescTooltip} only set a background and a margin, and Arc's {@link Tooltip} clamps the
 * container's <em>position</em> but never its width. A bundle string longer than the window
 * therefore runs off both edges — the statement hints reach ~200 characters and the settings
 * descriptions ~500, so both end up unreadable on any window narrower than the text.</p>
 *
 * <p>This is a drop-in replacement for the two vanilla entry points: it keeps their behaviour
 * (normalized bundle key, no hint when the key is missing, desktop hover plus mobile tap) and only
 * changes how the label is built — the string is pre-wrapped against a fraction of the window width
 * before it reaches the container, so the ordinary tooltip machinery measures the wrapped text and
 * places a box that fits.</p>
 */
public final class SugarTooltip{
    /** Share of the window width a tooltip may occupy. */
    private static final float widthFraction = 0.5f;
    /** Width cap in design units, so a wide window at 100% UI scale does not produce a tooltip
     *  with a 150-character line. Scaled by {@link Scl} like every other UI measurement. */
    private static final float designCap = 560f;
    /** Floor for the wrap width: below this even a short hint starts breaking mid-phrase. */
    private static final float minTextWidth = 160f;
    /** Reserved for the container margin, {@code Tooltip.edgeDistance} and a little slack. */
    private static final float edgeSlack = 40f;
    /** Reused for every measurement; the UI is single-threaded, like Arc's own static layouts. */
    private static final GlyphLayout shared = new GlyphLayout();

    private SugarTooltip(){}

    /**
     * Attaches a vanilla-style hover hint addressed by bundle key. Mirrors
     * {@code LCanvas.tooltip}: the key is lower-cased with spaces removed and a missing entry shows
     * nothing, so call sites that pass a vanilla key keep working unchanged.
     */
    public static void hint(Cell<?> cell, String key){
        if(key == null) return;
        // Locale.ROOT rather than the default locale: under a Turkish locale "I".toLowerCase()
        // yields a dotless ı and the key stops resolving (SpotBugs DM_CONVERT_CASE). This mod has
        // already been bitten once by a locale assumption -- see Mods.buildFiles() on Android.
        String lkey = key.toLowerCase(Locale.ROOT).replace(" ", "");
        if(!Core.bundle.has(lkey)) return;
        attach(cell, Core.bundle.get(lkey));
    }

    /**
     * Attaches an already-resolved hover hint. On desktop it opens on hover and closes when the
     * pointer leaves; {@code allowMobile} additionally opens it on tap on mobile, matching
     * {@code UI.addDescTooltip}, which is what the statement cards and the palette buttons want.
     *
     * <p>Like vanilla's {@code Tooltip} this appends a listener, so attaching twice to the same
     * element would show two tooltips. Every call site here attaches to an element it has just
     * built -- the canvas rebuilds cards and palette buttons rather than reusing their elements --
     * so the one thing to keep true is that a rebuild never re-attaches to an element it kept.</p>
     */
    public static void attach(Cell<?> cell, String text){
        if(text == null || text.isEmpty()) return;
        cell.get().addListener(new FittingTooltip(text));
    }

    /** The font the hints are measured with. {@code Fonts.outline} is the widest UI font, so
     *  measuring with it errs towards wrapping one word early instead of one word too late. */
    static Font hintFont(){
        return Fonts.outline != null ? Fonts.outline : Fonts.def;
    }

    /** True once the fonts the measurement needs exist; false during early mod init. */
    public static boolean ready(){
        return hintFont() != null;
    }

    /** Re-flows {@code text} for the current window. */
    public static String fit(String text){
        return fit(text, maxTextWidth());
    }

    /** Re-flows {@code text} to {@code maxWidth} pixels; returns it unchanged when the fonts are
     *  not loaded yet, so a caller running during mod init degrades instead of throwing. */
    public static String fit(String text, float maxWidth){
        Font font = hintFont();
        if(font == null) return text;
        return TextWrap.wrap(text, maxWidth, measurer(font));
    }

    private static Floatf<String> measurer(Font font){
        return text -> {
            shared.setText(font, text);
            return shared.width;
        };
    }

    /** Widest text a tooltip may show, in pixels. The scene is in pixels, so this is directly
     *  comparable to {@link GlyphLayout#width}; the cap is expressed in design units so it stays
     *  a sensible number of characters per line at every UI scale. */
    private static float maxTextWidth(){
        float screen = Core.graphics.getWidth();
        float cap = Math.min(designCap * Scl.scl(1f), screen - edgeSlack);
        return Math.max(minTextWidth, Math.min(screen * widthFraction, cap));
    }

    /**
     * A {@link Tooltip} whose label is re-wrapped whenever the window size changes.
     *
     * <p>The wrap has to happen at show time, not at construction time: the cards and buttons that
     * carry these hints are built once, while the window can be resized afterwards, and the
     * line breaks depend on the width. Overriding {@link Tooltip#setContainerPosition} is the hook
     * Arc already calls before every {@code pack()}, so the container then sizes itself from the
     * text that is actually in the label.</p>
     */
    private static class FittingTooltip extends Tooltip{
        private final String raw;
        private final Label label;
        private float fittedWidth = -1f;

        FittingTooltip(String raw){
            super(t -> {});
            this.raw = raw;
            label = new Label("", Styles.outlineLabel);
            label.setColor(Color.lightGray);
            container.background(Styles.black8).margin(4f).add(label).left();
            allowMobile = true;
        }

        @Override
        protected void setContainerPosition(Element element, float x, float y){
            float width = maxTextWidth();
            if(width != fittedWidth){
                fittedWidth = width;
                label.setText(fit(raw, width));
            }
            super.setContainerPosition(element, x, y);
        }
    }
}
