package logicsugar.assist;

import arc.Core;
import arc.Events;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Font;
import arc.math.geom.Rect;
import arc.scene.ui.layout.Scl;
import arc.util.Align;
import mindustry.Vars;
import mindustry.game.EventType;
import mindustry.gen.Groups;
import mindustry.gen.Unit;
import mindustry.graphics.Drawf;
import mindustry.graphics.Layer;
import mindustry.ui.Fonts;

import static mindustry.Vars.tilesize;

/**
 * Optional map overlay that draws each unit's logic {@code flag} in red immediately above
 * the unit. Display-only: nothing is written to saves or the instruction stream, so the
 * overlay is safe in multiplayer (vanilla clients simply do not see it).
 *
 * <p>Zero is the vanilla default and is omitted so unmarked units stay clean. Non-finite
 * values are skipped. Units outside the camera (plus a generous margin) and units hidden
 * by fog are not drawn. Text placement matches {@link ProcessorStatus}: the label sits
 * on the top edge of the hitbox, using {@code Drawf.text()} for the pixelate-aware layer
 * and {@code Fonts.outline} in world space.</p>
 */
public final class UnitFlags{
    public static final String settingShowFlags = "logicsugar.showUnitFlags";

    /** Live mirror of the setting so the draw path does not hit Core.settings every unit. */
    public static volatile boolean enabled = false;

    static final Color color = Color.red;
    static final float layer = Layer.overlayUI;
    /** World-space gap between the top of the hitbox and the bottom of the label. */
    static final float yPad = 2f;

    static final Rect bounds = new Rect();

    private static Font drawFont;
    private static float drawLine;
    private static boolean initialized;

    private UnitFlags(){}

    /** Reads the stored checkbox (the settings UI may not have been built yet). */
    public static void applySettings(){
        if(Core.settings == null) return;
        enabled = Core.settings.getBool(settingShowFlags, false);
    }

    public static synchronized void init(){
        if(initialized) return;
        initialized = true;

        Events.run(EventType.Trigger.drawOver, UnitFlags::draw);
    }

    static void draw(){
        if(!enabled) return;
        if(Vars.state == null || !Vars.state.isGame() || Groups.unit == null) return;

        Core.camera.bounds(bounds);
        bounds.grow(tilesize * 8f);

        Draw.z(layer);
        float z = Drawf.text();

        Font font = Fonts.outline;
        boolean ints = font.usesIntegerPositions();
        font.getData().setScale(0.25f / Scl.scl(1f));
        font.setUseIntegerPositions(false);
        font.setColor(color);
        drawFont = font;
        drawLine = font.getLineHeight();

        Groups.unit.each(UnitFlags::drawUnit);

        drawFont = null;

        font.setUseIntegerPositions(ints);
        font.getData().setScale(1f);
        font.setColor(Color.white);
        Draw.z(z);
    }

    private static void drawUnit(Unit unit){
        if(unit == null || unit.dead) return;
        if(!shouldDraw(unit.flag)) return;
        if(!bounds.contains(unit.x, unit.y)) return;
        if(Vars.state.rules.fog && Vars.player != null && unit.inFogTo(Vars.player.team())) return;

        drawFont.draw(formatFlag(unit.flag), unit.x, labelY(unit.y, unit.hitSize) + drawLine, Align.center);
    }

    /** Zero is the unmarked default; NaN / inf are not useful as labels. */
    static boolean shouldDraw(double flag){
        return flag != 0d && Double.isFinite(flag);
    }

    /** Whole numbers print without a trailing {@code .0}; other values keep full double text. */
    static String formatFlag(double flag){
        long asLong = (long)flag;
        if(asLong == flag) return Long.toString(asLong);
        return Double.toString(flag);
    }

    /** Bottom of the label: top of the hitbox plus a small pad. */
    static float labelY(float unitY, float hitSize){
        return unitY + hitSize / 2f + yPad;
    }
}
