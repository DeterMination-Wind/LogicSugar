package logicsugar.assist;

import arc.Core;
import arc.Events;
import mindustry.Vars;
import mindustry.game.EventType;
import mindustry.logic.LExecutor;

import java.lang.reflect.Modifier;

/**
 * Client-side override of {@link LExecutor#maxInstructions} (vanilla 1000, up to 2000 here).
 *
 * <p><strong>Multiplayer gate (project hard requirement):</strong> vanilla clients parse at
 * most {@code LExecutor.maxInstructions} lines ({@code LParser} stops at the static limit),
 * and a label jump past that boundary fails the whole assembly — the game's catch wipes the
 * processor code and persists the empty result. Programs above the vanilla limit can
 * therefore only exist in single-player/editor sessions: while {@code Vars.net.active()}
 * (connected or hosting) the vanilla limit is enforced, whatever the slider says. The
 * limit is re-evaluated on session transitions (connect, world load, reset).</p>
 *
 * <p>Two LogicSugar-specific payoffs in the single-player case: the persistence carrier
 * lines ({@code set __ls_sugar ...}) count toward the instruction budget, so a larger limit
 * directly reduces the "carrier dropped with a warning" degradation on big sugar programs;
 * and single-player debug builds that emit assertion instructions (SugarAsserts) fit
 * alongside the lowered program.</p>
 *
 * <p>Degrades gracefully: the static field is non-final on every build this mod targets,
 * but if a future build makes it final the settings row disappears instead of crashing
 * (same guard as the upstream MlogAssertions mod).</p>
 */
public final class InstructionLimit{
    public static final String settingMaxInstructions = "logicsugar.maxInstructions";
    public static final int defaultLimit = 1000;
    public static final int minLimit = 1000;
    public static final int maxLimit = 2000;
    public static final int step = 100;

    private static Boolean canOverride;
    private static boolean initialized;

    private InstructionLimit(){}

    /** Whether {@link LExecutor#maxInstructions} can be reassigned on this game build. */
    public static boolean canOverride(){
        if(canOverride == null){
            try{
                canOverride = !Modifier.isFinal(LExecutor.class.getField("maxInstructions").getModifiers());
            }catch(NoSuchFieldException e){
                canOverride = false;
            }
        }
        return canOverride;
    }

    /** Whether the override may currently apply: single-player/editor only. Multiplayer
     *  sessions (connected or hosting) always run at the vanilla limit. */
    public static boolean sessionAllows(){
        return Vars.net == null || !Vars.net.active();
    }

    /** Pure decision behind {@link #effectiveLimit()}: the vanilla default wins whenever a
     *  multiplayer session is active. Extracted for the self-test. */
    static int effectiveLimit(int settingValue, boolean netActive){
        return netActive ? defaultLimit : settingValue;
    }

    /** The limit to enforce right now: the user's setting in single-player/editor, the
     *  vanilla default in multiplayer. */
    public static int effectiveLimit(){
        if(!canOverride()) return defaultLimit;
        int setting = Core.settings == null ? defaultLimit : Core.settings.getInt(settingMaxInstructions, defaultLimit);
        return effectiveLimit(setting, !sessionAllows());
    }

    /** Applies the limit for the current session; also hooks the session-transition events
     *  so the vanilla clamp engages the moment a multiplayer session starts. Idempotent. */
    public static synchronized void init(){
        if(initialized) return;
        initialized = true;

        Events.on(EventType.ClientServerConnectEvent.class, e -> apply());
        Events.on(EventType.WorldLoadEvent.class, e -> apply());
        Events.on(EventType.ResetEvent.class, e -> apply());
        apply();
    }

    /** Applies the limit for the current session (vanilla default while in multiplayer). */
    public static void apply(){
        if(!canOverride()) return;
        LExecutor.maxInstructions = effectiveLimit();
    }

    /** Settings slider callback; applies the session-aware decision with the explicit
     *  value (not re-reading the setting, whose persist order vs this callback is not
     *  contractual). */
    public static void apply(int limit){
        if(!canOverride()) return;
        LExecutor.maxInstructions = effectiveLimit(limit, !sessionAllows());
    }
}
