package logicsugar.vars;

import arc.Core;
import arc.scene.style.TextureRegionDrawable;
import mindustry.gen.Icon;

/**
 * The three kinds of snapshot the {@code snapshot} instruction and the block menus can create.
 *
 * <p>Ported from upstream MlogAssertions v0.11.1. The name is the wire token written by the
 * {@code snapshot} card and read back by its parser, so it must not change; {@link #display()}
 * and the icons are presentation only.</p>
 */
public enum SnapshotType{
    /** Just the target block's own state. */
    isolated(Icon.logic, (char)59406),
    /** The target block plus every block its variables reference (processors/memory). */
    connected(Icon.sitemap, (char)61672),
    /** Every logic processor and memory block on the map. */
    global(Icon.planet, (char)59443),
    ;

    public static final SnapshotType[] all = values();

    public final TextureRegionDrawable icon;
    public final char charIcon;

    SnapshotType(TextureRegionDrawable icon, char charIcon){
        this.icon = icon;
        this.charIcon = charIcon;
    }

    /** Localized label for the select buttons (falls back to the wire token). */
    public String display(){
        return Core.bundle.get("logicsugar.vars.snapshottype." + name(), name());
    }
}
