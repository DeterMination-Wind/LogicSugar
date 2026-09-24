package logicsugar.assist;

import arc.Core;
import arc.Events;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.Time;
import mindustry.Vars;
import mindustry.game.EventType.ResetEvent;
import mindustry.game.EventType.SaveWriteEvent;
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
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Hides Logic Sugar's compiler-generated variables (the {@code __ls_*} reserved namespace and
 * {@code _0}, {@code _1}... expression temporaries) from both variable viewers:
 * <ul>
 *   <li>MindustryX's floating logic-support panel, which enumerates {@code executor.allVars}
 *       (a display-only array MindustryX patches in; absent on vanilla);</li>
 *   <li>the vanilla {@code @variables} dialog and MindustryX's processor config panel, which
 *       enumerate {@code executor.vars}.</li>
 * </ul>
 *
 * <p><b>Networked sessions never touch {@code vars}.</b> It is the live index space for the
 * {@code sync} instruction: {@code LExecutor.load()} assigns {@code vars[i].id = i},
 * {@code SyncI} sends that absolute index over the network and {@code syncVariable} resolves it
 * with {@code optionalVar(id)}. A compacted array would make syncs land on the wrong variable
 * (or silently drop when the id exceeds the shorter length). While
 * {@link mindustry.net.Net#active()} is true, {@code vars} is left exactly as the game built
 * it, and a single-player filter still installed when a session starts is undone on the next
 * frame, before further syncs can run.
 *
 * <p><b>Single-player {@code vars} filtering is safe for saves:</b> {@code LogicBlock.write()}
 * serializes the name/value of every non-null {@code vars} entry, so the full array is restored
 * for the duration of {@link SaveWriteEvent}. Hidden state therefore still persists in
 * single-player saves. {@code allVars} is purely a display array that no instruction, save or
 * sync path reads, so it is filtered in every session.
 *
 * <p>Implementation: a half-second background sweep replaces each processor's arrays with
 * filtered copies holding the same live {@link LVar} objects, so value updates keep flowing
 * through the existing rows, and drops the lazily-built {@code nameMap} so name-based lookups
 * rebuild against the swapped {@code vars}. The full arrays are cached per executor and restored
 * when the setting is turned off, the session becomes networked, or the game is saved. Entries
 * for destroyed processors are evicted on every sweep.
 */
public final class VarDisplayFilter{
    public static final String settingHideVars = "logicsugar.hideVars";

    /** Seconds between sweeps; cheap enough to be invisible, fast enough to feel instant. */
    private static final float sweepInterval = 0.5f;

    private static Field allVarsField;
    private static boolean allVarsChecked;
    private static Field nameMapField;
    private static boolean nameMapChecked;
    private static float timer;

    /** Pre-filter full arrays, cached for restore. Keyed by executor. */
    private static final Map<LExecutor, LVar[]> fullAllVars = new IdentityHashMap<>();
    private static final Map<LExecutor, LVar[]> fullVars = new IdentityHashMap<>();
    /** Code each cache was captured for; mismatches mean the executor was reloaded. */
    private static final Map<LExecutor, String> allVarsCode = new IdentityHashMap<>();
    private static final Map<LExecutor, String> varsCode = new IdentityHashMap<>();

    private VarDisplayFilter(){}

    /** Registers the background sweep and cache lifecycle. Call once from the client-load handler. */
    public static void init(){
        Events.run(Trigger.update, () -> {
            // A network session can start without any world event firing. Undo a single-player
            // vars filter on the first frame of the session, before syncs can observe it.
            if(!fullVars.isEmpty() && netActive()){
                restoreCachedVars();
            }

            timer += Time.delta;
            if(timer < sweepInterval) return;
            timer = 0f;
            applyToAll();
        });
        // LogicBlock.write() serializes every non-null executor.vars entry by name, so put the
        // full array back for the duration of the write: single-player saves keep hidden state.
        Events.on(SaveWriteEvent.class, event -> restoreCachedVars());
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

    /** Rebuilds the display arrays for one executor, honoring the current setting. */
    public static void apply(LExecutor executor){
        try{
            if(executor == null) return;

            // A compacted vars array must never survive into a networked session.
            boolean multiplayer = netActive();
            if(multiplayer && !fullVars.isEmpty()){
                restoreCachedVars();
            }

            if(enabled()){
                // Filter once when internal names are present; afterwards the arrays have none,
                // so this is a no-op — the hidden state stays stable between sweeps.
                Field af = allVarsField();
                if(af != null && af.get(executor) instanceof LVar[] all && hasSugarInternals(all)){
                    fullAllVars.put(executor, all);
                    allVarsCode.put(executor, codeOf(executor));
                    af.set(executor, filter(all));
                }

                // vars backs the vanilla @variables dialog and MindustryX's processor config
                // panel. Only compact it while no network session can read the index space.
                if(!multiplayer && nameMapField() != null && hasSugarInternals(executor.vars)
                    && invalidateNameMap(executor)){
                    fullVars.put(executor, executor.vars);
                    varsCode.put(executor, codeOf(executor));
                    executor.vars = filter(executor.vars);
                }
            }else{
                // Setting off: restore the full arrays we hid.
                Field af = allVarsField();
                if(af != null){
                    LVar[] fullAll = takeCache(executor, fullAllVars, allVarsCode);
                    if(fullAll != null) af.set(executor, fullAll);
                }
                LVar[] full = takeCache(executor, fullVars, varsCode);
                if(full != null && invalidateNameMap(executor)){
                    executor.vars = full;
                }
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
    private static LVar[] takeCache(LExecutor executor, Map<LExecutor, LVar[]> arrays, Map<LExecutor, String> codes){
        LVar[] full = arrays.remove(executor);
        if(full == null) return null;
        String expected = codes.remove(executor);
        if(expected == null || !expected.equals(codeOf(executor))) return null;
        return full;
    }

    /** True while a client or server session is active. Fails safe when it cannot tell. */
    private static boolean netActive(){
        try{
            return Vars.net == null || Vars.net.active();
        }catch(Throwable t){
            return true;
        }
    }

    /** Puts every cached full vars array back before a sync or a save can observe the swap. */
    private static void restoreCachedVars(){
        if(fullVars.isEmpty()) return;
        List<LExecutor> executors = new ArrayList<>(fullVars.keySet());
        for(LExecutor executor : executors){
            LVar[] full = takeCache(executor, fullVars, varsCode);
            if(full == null) continue;
            try{
                if(invalidateNameMap(executor)) executor.vars = full;
            }catch(Throwable t){
                Log.warn("LogicSugar: failed to restore variable display", t);
            }
        }
    }

    /** Resolves the lazily-built name-to-index map so it can be dropped after an array swap. */
    private static Field nameMapField(){
        if(!nameMapChecked){
            nameMapChecked = true;
            try{
                nameMapField = LExecutor.class.getDeclaredField("nameMap");
                nameMapField.setAccessible(true);
            }catch(Throwable t){
                nameMapField = null;
            }
        }
        return nameMapField;
    }

    /**
     * Drops the lazily-built name-to-index map before an array swap. Without this, name-based
     * lookups ({@code read} with a string, link binding) would resolve stale indexes against
     * the compacted array and hit the wrong variable.
     *
     * @return false when the map cannot be dropped; callers must then leave {@code vars} alone.
     */
    private static boolean invalidateNameMap(LExecutor executor){
        Field field = nameMapField();
        if(field == null) return true;
        try{
            field.set(executor, null);
            return true;
        }catch(Throwable t){
            Log.warn("LogicSugar: failed to clear the executor variable name cache", t);
            return false;
        }
    }

    private static String codeOf(LExecutor executor){
        return executor.build == null ? null : ((LogicBuild)executor.build).code;
    }

    private static void clearCache(){
        fullAllVars.clear();
        fullVars.clear();
        allVarsCode.clear();
        varsCode.clear();
    }

    /** Drops cache entries whose processor no longer exists, so destroyed builds do not leak. */
    private static void evictStale(){
        evictStale(fullAllVars, allVarsCode);
        evictStale(fullVars, varsCode);
    }

    private static void evictStale(Map<LExecutor, LVar[]> arrays, Map<LExecutor, String> codes){
        Iterator<LExecutor> it = arrays.keySet().iterator();
        while(it.hasNext()){
            LExecutor executor = it.next();
            if(executor.build == null || !executor.build.isValid()){
                it.remove();
                codes.remove(executor);
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
