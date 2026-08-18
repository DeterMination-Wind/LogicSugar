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
import mindustry.logic.SugarFunctions;
import mindustry.world.blocks.logic.LogicBlock.LogicBuild;
import logicsugar.assist.expr.ExprCompiler;

import java.lang.reflect.Field;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Hides Logic Sugar's compiler-generated variables (the {@code __ls_*} reserved namespace and
 * {@code _0}, {@code _1}... expression temporaries) from MindustryX's editor logic-support
 * panel, which enumerates {@code executor.allVars}. Vanilla Mindustry has no {@code allVars}
 * field, so on vanilla this class is inert.
 *
 * <p>{@code executor.vars} is intentionally left untouched: it is the live index space for
 * the {@code sync} instruction. {@code LExecutor.load()} assigns {@code vars[i].id = i} from
 * the full array, {@code SyncI} sends that absolute index over the network and
 * {@code syncVariable} resolves it with {@code optionalVar(id)} — so compacting the array
 * would make syncs land on the wrong variable (or silently drop when the id exceeds the
 * shorter length). Filtering {@code allVars} instead is safe: it is a display-only array that
 * no instruction, save or sync path reads.
 *
 * <p>Implementation: a half-second background sweep replaces each processor's {@code allVars}
 * array with a filtered copy holding the same live {@link LVar} objects, so value updates keep
 * flowing through the existing rows. The full array is cached per executor and restored when
 * the setting is turned off, so the toggle works live without a recompile. Entries for
 * destroyed processors are evicted on every sweep.
 */
public final class VarDisplayFilter{
    public static final String settingHideVars = "logicsugar.hideVars";

    /** Seconds between sweeps; cheap enough to be invisible, fast enough to feel instant. */
    private static final float sweepInterval = 0.5f;

    private static Field allVarsField;
    private static boolean allVarsChecked;
    private static float timer;

    /** Pre-filter full arrays, cached for restore. Keyed by executor. */
    private static final Map<LExecutor, LVar[]> fullAllVars = new IdentityHashMap<>();
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

    /** Re-applies the filter (or restore) to every loaded processor, evicting dead entries first. */
    public static void applyToAll(){
        evictStale();
        if(Vars.state == null || !Vars.state.isGame() || Groups.build == null) return;
        for(Building build : Groups.build){
            if(build instanceof LogicBuild logic && logic.executor != null){
                apply(logic.executor);
            }
        }
    }

    /** Rebuilds the display array for one executor, honoring the current setting. */
    public static void apply(LExecutor executor){
        try{
            if(executor == null) return;
            Field af = allVarsField();
            if(af == null) return; // vanilla client: nothing to filter
            if(!(af.get(executor) instanceof LVar[] all)) return;

            if(enabled()){
                // Filter once when internal names are present; afterwards the array has none,
                // so this is a no-op — the hidden state stays stable between sweeps.
                if(hasSugarInternals(all)){
                    fullAllVars.put(executor, all);
                    cachedCode.put(executor, codeOf(executor));
                    af.set(executor, filter(all));
                }
            }else{
                // Setting off: restore the full array we hid.
                LVar[] full = takeCache(executor);
                if(full != null) af.set(executor, full);
            }
        }catch(Throwable t){
            // viewers are cosmetic; never break gameplay over a filter failure
            Log.warn("LogicSugar: failed to filter variable display", t);
        }
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
    private static LVar[] takeCache(LExecutor executor){
        LVar[] full = fullAllVars.remove(executor);
        if(full == null) return null;
        String expected = cachedCode.remove(executor);
        if(expected == null || !expected.equals(codeOf(executor))) return null;
        return full;
    }

    private static String codeOf(LExecutor executor){
        return executor.build == null ? null : ((LogicBuild)executor.build).code;
    }

    private static void clearCache(){
        fullAllVars.clear();
        cachedCode.clear();
    }

    /** Drops cache entries whose processor no longer exists, so destroyed builds do not leak. */
    private static void evictStale(){
        Iterator<LExecutor> it = fullAllVars.keySet().iterator();
        while(it.hasNext()){
            LExecutor executor = it.next();
            if(executor.build == null || !executor.build.isValid()){
                it.remove();
                cachedCode.remove(executor);
            }
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
