package logicsugar.assist;

import arc.Core;
import mindustry.logic.LExecutor;

import java.lang.reflect.Modifier;

/**
 * Client-side override of {@link LExecutor#maxInstructions} (vanilla 1000, up to 2000 here).
 *
 * <p>Two LogicSugar-specific payoffs beyond the debugging use case: the persistence carrier
 * lines ({@code set __ls_sugar ...}) count toward the instruction budget, so a larger limit
 * directly reduces the "carrier dropped with a warning" degradation on big sugar programs;
 * and debug builds that emit assertion instructions (SugarAsserts) fit alongside the lowered
 * program.</p>
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

    /** Applies the stored override (vanilla default when the setting is untouched). */
    public static void apply(){
        if(!canOverride()) return;
        int value = Core.settings == null ? defaultLimit : Core.settings.getInt(settingMaxInstructions, defaultLimit);
        LExecutor.maxInstructions = value;
    }

    /** Applies an explicit limit (settings slider callback and tests). */
    public static void apply(int limit){
        if(!canOverride()) return;
        LExecutor.maxInstructions = limit;
    }
}
