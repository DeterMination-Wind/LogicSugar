package logicsugar.assist;

import arc.Core;
import arc.Events;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.Time;
import mindustry.Vars;
import mindustry.game.EventType.ResetEvent;
import mindustry.game.EventType.Trigger;
import mindustry.game.EventType.WorldLoadEvent;
import mindustry.gen.Building;
import mindustry.gen.Groups;
import mindustry.logic.LExecutor;
import mindustry.logic.LVar;
import mindustry.logic.SugarCompiler;
import mindustry.logic.SugarFunctions;
import mindustry.world.blocks.logic.LogicBlock.LogicBuild;
import logicsugar.assist.expr.ExprCompiler;

import java.lang.reflect.Field;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Hides Logic Sugar's compiler-generated variables (the {@code __ls_*} reserved namespace and
 * {@code _0}, {@code _1}... expression temporaries) from every variable viewer:
 * <ul>
 *   <li>MindustryX's floating logic-support panel (reads {@code executor.allVars});</li>
 *   <li>the vanilla {@code @variables} dialog and MindustryX's processor config panel
 *       (both read {@code executor.vars});</li>
 *   <li>any other code enumerating those two arrays.</li>
 * </ul>
 * Vanilla Mindustry has no {@code allVars} field and no processor-side viewer, so on vanilla
 * this class only trims the same sugar-internal names from the {@code @variables} dialog.
 *
 * <p>Why this is safe: logic instructions bind {@link LVar} objects at assembly time and never
 * look variables up by array index at runtime. {@code executor.vars} is only enumerated for
 * display, save serialization (by name) and the {@code sync} instruction, which uses
 * {@code LVar.id} — a load-time index that stays stable because filtering preserves relative
 * order. The one indexed lookup ({@code nameMap}) is invalidated after every array swap, so
 * name-based reads (the {@code read} instruction, link binding) rebuild against the filtered
 * array. Saved games are unaffected: serialization is name-based and self-consistent.
 *
 * <p>The full arrays are cached per executor and restored when the setting is turned off or the
 * program stops being sugar, so the toggle works live without a recompile.
 */
public final class VarDisplayFilter{
    public static final String settingHideVars = "logicsugar.hideVars";

    /** Seconds between sweeps; cheap enough to be invisible, fast enough to feel instant. */
    private static final float sweepInterval = 0.5f;

    private static Field allVarsField;
    private static boolean allVarsChecked;
    private static Field nameMapField;
    private static float timer;

    /** Pre-filter full arrays, cached for restore. Keyed by executor. */
    private static final Map<LExecutor, LVar[]> fullAllVars = new IdentityHashMap<>();
    private static final Map<LExecutor, LVar[]> fullVars = new IdentityHashMap<>();
    /** Code the cache was captured for; mismatches mean the executor was reloaded. */
    private static final Map<LExecutor, String> cachedCode = new IdentityHashMap<>();

    private VarDisplayFilter(){}

    /** Registers the background sweep and cache lifecycle. Call once from the client-load handler. */
    public static void init(){
        Events.run(Trigger.update, () -> {
            timer += Time.delta;
            if(timer < sweepInterval) return;
            timer = 0f;
            applyToAll();
        });
        Events.on(WorldLoadEvent.class, event -> clearCache());
        Events.on(ResetEvent.class, event -> clearCache());
    }

    /** Re-applies the filter (or restore) to every loaded processor. */
    public static void applyToAll(){
        if(Vars.state == null || !Vars.state.isGame() || Groups.build == null) return;
        for(Building build : Groups.build){
            if(build instanceof LogicBuild logic && logic.executor != null){
                apply(logic.executor);
            }
        }
    }

    /** Rebuilds the display arrays for one executor, honoring the current setting. */
    public static void apply(LExecutor executor){
        try{
            if(executor == null) return;
            boolean hide = enabled();
            boolean sugar = isSugarBuild(executor);

            if(hide && sugar){
                // MindustryX editor panel: allVars (includes constants; optional on vanilla).
                Field af = allVarsField();
                if(af != null && af.get(executor) instanceof LVar[] all && hasSugarInternals(all)){
                    fullAllVars.put(executor, all);
                    cachedCode.put(executor, codeOf(executor));
                    af.set(executor, filter(all));
                }
                // Processor config panel + vanilla @variables dialog: vars.
                if(hasSugarInternals(executor.vars)){
                    fullVars.put(executor, executor.vars);
                    cachedCode.put(executor, codeOf(executor));
                    executor.vars = filter(executor.vars);
                    invalidateNameMap(executor);
                }
            }else{
                // Setting off, or not a sugar program: restore the full arrays we hid.
                LVar[] full = takeCache(executor, fullVars);
                if(full != null){
                    executor.vars = full;
                    invalidateNameMap(executor);
                }
                Field af = allVarsField();
                LVar[] fullAll = af == null ? null : takeCache(executor, fullAllVars);
                if(fullAll != null) af.set(executor, fullAll);
            }
        }catch(Throwable t){
            // viewers are cosmetic; never break gameplay over a filter failure
            Log.warn("LogicSugar: failed to filter variable display", t);
        }
    }

    private static boolean isSugarBuild(LExecutor executor){
        return executor.build != null && SugarCompiler.isSugarProgram(((LogicBuild)executor.build).code);
    }

    /** Whether the setting is on. Defaults to hidden; fails open when settings are unavailable. */
    public static boolean enabled(){
        try{
            return Core.settings.getBool(settingHideVars, true);
        }catch(Throwable t){
            return true;
        }
    }

    /** True for compiler-generated names: the __ls_ reserved namespace and _&lt;digits&gt; temporaries. */
    public static boolean isSugarInternal(String name){
        return name.startsWith(SugarFunctions.reservedPrefix) || ExprCompiler.isTemp(name);
    }

    private static boolean hasSugarInternals(LVar[] vars){
        for(LVar v : vars){
            if(isSugarInternal(v.name)) return true;
        }
        return false;
    }

    private static LVar[] filter(LVar[] vars){
        Seq<LVar> kept = null;
        for(int i = 0; i < vars.length; i++){
            if(isSugarInternal(vars[i].name)){
                if(kept == null){
                    kept = new Seq<>(vars.length);
                    for(int j = 0; j < i; j++) kept.add(vars[j]);
                }
            }else if(kept != null){
                kept.add(vars[i]);
            }
        }
        return kept == null ? vars : kept.toArray(LVar.class);
    }

    /** Fetches a cached full array, dropping it when the executor was reloaded since. */
    private static LVar[] takeCache(LExecutor executor, Map<LExecutor, LVar[]> map){
        LVar[] full = map.remove(executor);
        if(full == null) return null;
        String expected = cachedCode.get(executor);
        if(expected == null || !expected.equals(codeOf(executor))) return null;
        return full;
    }

    private static String codeOf(LExecutor executor){
        return executor.build == null ? null : ((LogicBuild)executor.build).code;
    }

    private static void clearCache(){
        fullAllVars.clear();
        fullVars.clear();
        cachedCode.clear();
    }

    /**
     * Drops the lazily-built name→index map after an array swap. Without this, name-based
     * lookups ({@code read} with a string, link binding) would resolve stale indexes against
     * the filtered array and hit the wrong variable.
     */
    private static void invalidateNameMap(LExecutor executor){
        try{
            if(nameMapField == null){
                nameMapField = LExecutor.class.getDeclaredField("nameMap");
                nameMapField.setAccessible(true);
            }
            nameMapField.set(executor, null);
        }catch(Throwable ignored){
            // the lookup map is an optimization; worst case it rebuilds stale — ignore
        }
    }

    /** Resolves MindustryX's optional LExecutor.allVars field once (absent on vanilla). */
    private static Field allVarsField(){
        if(!allVarsChecked){
            allVarsChecked = true;
            try{
                allVarsField = LExecutor.class.getField("allVars");
            }catch(NoSuchFieldException e){
                allVarsField = null;
            }
        }
        return allVarsField;
    }
}
