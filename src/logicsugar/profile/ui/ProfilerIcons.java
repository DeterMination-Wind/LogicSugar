package logicsugar.profile.ui;

import arc.Core;
import arc.graphics.g2d.TextureRegion;
import arc.scene.style.TextureRegionDrawable;
import arc.util.Log;
import logicsugar.LogicSugarMod;
import mindustry.Vars;
import mindustry.mod.Mods.LoadedMod;

/**
 * ProfileDialog 的 6 张自绘图标（上游 MlogAssertions v0.11.3 的 {@code Icon2}）。
 *
 * <p>图标文件放在 {@code assets/sprites/ui/*.png}，Mindustry 的图集打包会把它们命名成
 * {@code <mod.name>-<文件名>}，所以运行时前缀必须按「当前实际加载我们的那个 mod」拼。
 * 三种形态自动成立：dev 构建 {@code LogicSugar-dev-*}、正式 {@code LogicSugar-*}、
 * Neon 聚合构建 {@code Neon-*}（Neon 的 sync 把同一批 png 复制进它自己的资源目录，
 * 此时 {@code LogicSugarMod} 不再是任何 mod 的 main 类，{@link Vars#mods}{@code .getMod}
 * 返回 null，需要按类加载器反查宿主 mod）。</p>
 *
 * <p>无头/图集缺失时退回游戏自带的 error 区域并只记一次日志——图标是纯装饰，
 * 不允许因此让 profiler 或游戏启动失败。</p>
 */
public final class ProfilerIcons{
    public static final TextureRegionDrawable branching = load("branching");
    public static final TextureRegionDrawable percent = load("percent");
    public static final TextureRegionDrawable sortAsc = load("sort-asc");
    public static final TextureRegionDrawable sortDesc = load("sort-desc");
    public static final TextureRegionDrawable sum = load("sum");
    public static final TextureRegionDrawable time = load("time");

    private static String prefix;
    private static boolean warned;

    private ProfilerIcons(){
    }

    private static TextureRegionDrawable load(String name){
        String atlasName = prefix() + name;

        try{
            if(Core.atlas != null && !Core.atlas.has(atlasName)){
                warnOnce("atlas region '" + atlasName + "' is missing (profiler icons fall back to the error region)");
            }
            return new TextureRegionDrawable(Core.atlas == null ? null : Core.atlas.find(atlasName));
        }catch(Throwable t){
            warnOnce("cannot resolve the profiler icon '" + name + "': " + t);
            return new TextureRegionDrawable((TextureRegion)null);
        }
    }

    private static void warnOnce(String message){
        if(warned) return;
        warned = true;
        Log.warn("LogicSugar: @", message);
    }

    /** 图集前缀（含结尾的 '-'）；解析一次后缓存。 */
    static String prefix(){
        if(prefix == null){
            prefix = resolve();
            Log.info("LogicSugar: profiler icons use the atlas prefix '@'", prefix);
        }
        return prefix;
    }

    private static String resolve(){
        // 1) 独立/dev 形态：本 mod 就是被加载的 mod 之一
        try{
            LoadedMod mod = Vars.mods == null ? null : Vars.mods.getMod(LogicSugarMod.class);
            if(mod != null && mod.name != null && !mod.name.isEmpty()) return mod.name + "-";
        }catch(Throwable ignored){
        }

        // 2) 聚合形态（Neon）：找那个「加载了我们这个 LogicSugarMod 类」的 mod
        try{
            if(Vars.mods != null){
                for(LoadedMod mod : Vars.mods.list()){
                    if(mod.loader == null || mod.name == null) continue;
                    try{
                        if(Class.forName("logicsugar.LogicSugarMod", false, mod.loader) == LogicSugarMod.class){
                            return mod.name + "-";
                        }
                    }catch(Throwable ignored){
                    }
                }
            }
        }catch(Throwable ignored){
        }

        // 3) 最后按图集本身探测（覆盖前缀被自定义/重打包的情形）
        try{
            if(Core.atlas != null){
                for(String candidate : new String[]{"LogicSugar-", "Neon-", ""}){
                    if(Core.atlas.has(candidate + "branching")) return candidate;
                }
            }
        }catch(Throwable ignored){
        }

        warnOnce("cannot determine the mod's atlas prefix; profiler icons may be missing");
        return "";
    }
}
