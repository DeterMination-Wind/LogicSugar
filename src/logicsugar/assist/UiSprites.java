package logicsugar.assist;

import arc.Core;
import arc.graphics.g2d.TextureRegion;
import arc.scene.style.TextureRegionDrawable;
import arc.util.Log;
import logicsugar.LogicSugarMod;
import mindustry.Vars;
import mindustry.mod.Mods.LoadedMod;

/**
 * 自绘 UI 图标（{@code assets/sprites/ui/*.png}）的图集加载器。
 *
 * <p>Mindustry 的图集打包会把 mod 的 sprites 命名成 {@code <mod.name>-<文件名>}，所以运行时
 * 前缀必须按「当前实际加载我们的那个 mod」拼。三种形态自动成立：dev 构建
 * {@code LogicSugar-dev-*}、正式 {@code LogicSugar-*}、Neon 聚合构建 {@code Neon-*}
 * （Neon 的 sync 把同一批 png 复制进它自己的资源目录，此时 {@code LogicSugarMod} 不再是
 * 任何 mod 的 main 类，{@link Vars#mods}{@code .getMod} 返回 null，需要按类加载器反查宿主
 * mod）。</p>
 *
 * <p>无头/图集缺失时退回游戏自带的 error 区域并只每个图标记一次日志——图标是纯装饰，
 * 不允许因此让任何界面或游戏启动失败。</p>
 *
 * <p>本类是 profiler 与变量/日志界面共用的入口（{@code ProfilerIcons}、
 * {@code VarsIcons}）；不要在界面代码里另拼前缀。</p>
 */
public final class UiSprites{
    private static String prefix;
    private static final arc.struct.ObjectSet<String> warned = new arc.struct.ObjectSet<>();

    private UiSprites(){
    }

    /** 解析并缓存 {@code <atlas prefix><name>}；缺图时返回 error 区域。 */
    public static TextureRegionDrawable load(String name){
        String atlasName = prefix() + name;

        try{
            if(Core.atlas != null && !Core.atlas.has(atlasName)){
                warnOnce(atlasName, "atlas region '" + atlasName + "' is missing (the icon falls back to the error region)");
            }
            return new TextureRegionDrawable(Core.atlas == null ? null : Core.atlas.find(atlasName));
        }catch(Throwable t){
            warnOnce(atlasName, "cannot resolve the UI icon '" + name + "': " + t);
            return new TextureRegionDrawable((TextureRegion)null);
        }
    }

    private static void warnOnce(String key, String message){
        if(warned.contains(key)) return;
        warned.add(key);
        Log.warn("LogicSugar: @", message);
    }

    /** 图集前缀（含结尾的 '-'）；解析一次后缓存。 */
    static String prefix(){
        if(prefix == null){
            prefix = resolve();
            Log.info("LogicSugar: UI icons use the atlas prefix '@'", prefix);
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

        Log.warn("LogicSugar: cannot determine the mod's atlas prefix; UI icons may be missing");
        return "";
    }
}
