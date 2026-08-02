package logicsugar.assist;

import arc.*;
import arc.func.*;
import arc.graphics.*;
import arc.scene.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.scene.style.*;
import arc.util.*;
import mindustry.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.logic.*;
import mindustry.logic.LStatements.*;
import mindustry.ui.*;
import mindustry.ui.dialogs.*;

import java.util.*;

/**
 * 跳转线着色功能。
 *
 * 为 logic 编辑器中的 jump 跳转线按目标着色。
 * 跳转到同一目标的线条颜色相同，便于区分不同跳转分支。
 *
 * 三种着色模式：
 *   0 = 关闭（白色）
 *   1 = 分散色（按目标 index，黄金角度 HSV）
 *   2 = 积木色（按目标积木类别颜色提亮 1.4 倍）
 *
 * JumpStatement.dest 是 public 字段，直接 instanceof + 强转访问，无需反射。
 */
public class JumpLineColor{

    // ---------- Settings ----------
    private static final String SETTING_ENABLED = "la-jump-color-enabled";
    private static final String SETTING_BLOCKCOLOR = "la-jump-color-blockcolor";

    // ---------- Patch marker ----------
    private static final String MARKER = "la-jump-color-patched";

    // ---------- Color cache ----------
    private static final float GOLDEN_ANGLE = 137.508f;
    private static final Map<Integer, Color> indexColorCache = new HashMap<>();
    private static final Map<String, Color> blockColorCache = new HashMap<>();

    // ===== Mode =====

    /**
     * 获取当前着色模式。
     * 0 = 关闭, 1 = 分散色, 2 = 积木色
     */
    public static int getMode(){
        try{
            if(!Core.settings.getBool(SETTING_ENABLED, true)) return 0;
            return Core.settings.getBool(SETTING_BLOCKCOLOR, false) ? 2 : 1;
        }catch(Exception e){
            return 1;
        }
    }

    // ===== Color generation =====

    /**
     * 按目标 index 生成黄金角度 HSV 颜色。
     * 使用黄金角度分布确保相邻 index 颜色差异最大。
     */
    public static Color colorForIndex(int index){
        if(indexColorCache.containsKey(index)) return indexColorCache.get(index);

        Color c = new Color();
        // fromHsv 不设置 alpha，需手动补 a = 1.0（arc Color.java 已知陷阱）
        c.fromHsv(((index * GOLDEN_ANGLE) % 360f + 360f) % 360f, 0.7f, 1.0f);
        c.a = 1.0f;
        indexColorCache.put(index, c);
        return c;
    }

    /**
     * 提亮源颜色（用于积木色模式）。
     * 将源颜色乘以 1.4 倍，并确保 alpha = 1.0。
     */
    public static Color brighten(Color src){
        Color c = new Color();
        c.set(src);
        c.mul(1.4f);
        c.a = 1.0f;
        return c;
    }

    /**
     * 根据目标积木和当前模式获取颜色。
     */
    public static Color getColorForTarget(LCanvas.StatementElem dest){
        int mode = getMode();
        if(mode == 0) return Color.white;

        if(mode == 2){
            // 积木色模式：按目标积木类别颜色
            LStatement st = dest.st;
            if(st != null){
                LCategory cat = st.category();
                if(cat != null && cat.color != null){
                    String key = cat.name;
                    if(!blockColorCache.containsKey(key)){
                        blockColorCache.put(key, brighten(cat.color));
                    }
                    return blockColorCache.get(key);
                }
            }
            return Color.white;
        }

        // 分散色模式：按目标 index
        return colorForIndex(dest.index);
    }

    // ===== Patch JumpCurve =====

    /**
     * 为单个 JumpCurve 注入着色回调。
     *
     * 使用 Element.update(Runnable) 设置每帧回调，
     * 该回调在 JumpCurve.act() 中 super.act() 之后执行，
     * 覆盖原版 JumpButton.update() 设置的白色。
     *
     * 着色时序（每帧）：
     *   1. StatementElem.act() -> JumpButton.update() -> color = 白色
     *   2. jumps.act() -> JumpCurve.act() -> super.act() -> 本回调 -> color = 彩色
     *   3. draw() -> 读取 button.color -> 绘制彩色线条和箭头
     */
    public static void patchCurve(LCanvas.JumpCurve curve){
        if(curve == null || MARKER.equals(curve.userObject)) return;
        curve.userObject = MARKER;

        curve.update(() -> {
            try{
                if(getMode() == 0) return;

                LCanvas.JumpButton button = curve.button;
                if(button == null) return;
                LCanvas.StatementElem elem = button.elem;
                if(elem == null) return;
                LStatement st = elem.st;
                if(st == null) return;

                // 获取跳转目标：JumpStatement.dest 是 public 字段，直接强转
                LCanvas.StatementElem dest = null;
                if(st instanceof JumpStatement){
                    dest = ((JumpStatement)st).dest;
                }

                if(dest == null){
                    button.color.set(Color.white);
                }else if(button.hasMouse()){
                    // 悬停时高亮
                    button.color.set(Pal.place);
                }else if(dest.parent != null){
                    // 按目标着色
                    button.color.set(getColorForTarget(dest));
                    button.color.a = 1.0f;
                }else{
                    button.color.set(Color.white);
                }

                // 同步按钮样式颜色
                ImageButton.ImageButtonStyle style = button.getStyle();
                if(style != null) style.imageUpColor = button.color;

            }catch(Exception e){
                Log.warn("[LogicAssist] JumpLineColor error: " + e);
            }
        });
    }

    /**
     * 遍历画布中所有 JumpCurve 并注入着色回调。
     */
    public static void patchAllCurves(LCanvas canvas){
        if(canvas == null || canvas.statements == null) return;
        Group jumps = SugarCanvas.getJumpLayer(canvas);
        if(jumps == null) return;

        for(Element child : jumps.getChildren()){
            if(child instanceof LCanvas.JumpCurve){
                patchCurve((LCanvas.JumpCurve)child);
            }
        }
    }

    // ===== Settings =====

    public static void buildSettings(SettingsMenuDialog.SettingsTable table){
        table.checkPref(SETTING_ENABLED, true, b -> {});
        table.checkPref(SETTING_BLOCKCOLOR, false, b -> {});
    }

    /**
     * 在主设置菜单中添加 "Logic Assist" 分类。
     * 包含跳转线着色的两个开关。
     *
     * 使用 SettingsTable.checkPref() 标准 API（自动处理设置读写和 UI 构建）。
     * Java 模组优势：Boolc lambda 无需 Rhino 的 new Boolc(){ get: method } 包装。
     */
    public static void setupSettings(){
        try{
            SettingsMenuDialog sd = Vars.ui.settings;
            if(sd == null) return;

            // 检查是否已添加（避免重复）
            for(SettingsMenuDialog.SettingsCategory cat : sd.getCategories()){
                if(cat.name.equals("@la.settings")) return;
            }

            sd.addCategory("@la.settings", Icon.edit, JumpLineColor::buildSettings);
        }catch(Exception e){
            Log.warn("[LogicAssist] Failed to setup settings: " + e);
        }
    }

    /** 清空颜色缓存，在编辑器关闭时调用 */
    public static void clearCache(){
        indexColorCache.clear();
        blockColorCache.clear();
    }
}
