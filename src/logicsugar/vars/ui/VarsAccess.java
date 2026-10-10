package logicsugar.vars.ui;

import arc.Core;
import arc.Events;
import arc.util.Log;
import logicsugar.vars.Snapshots;
import logicsugar.vars.VarsOptions;
import mindustry.game.EventType;
import mindustry.gen.Building;

/**
 * 变量界面的两个「入口侧」职责：三击任意方块打开属性界面，以及把设置项读进
 * {@link VarsOptions} / {@link Snapshots}。
 *
 * <p>上游 MlogAssertions 把这两件事分别放在 {@code LogicDialogAddon}（三击状态机）与
 * {@code Settings}（读取）里；LogicSugar 的设置页是 {@code logicsugar.LogicSugarSettings}
 * 的注册表，读取入口集中在 {@link #applySettings()}（与
 * {@code ProcessorStatus.applySettings} 同一模式）。三击入口跳过本客户端不可访问的方块
 * （上游 v0.11.6 的 {@code displayable()} 守卫），{@link VarsOptions#load()} 也在这里读回
 * 五个持久化的视图开关（上游 v0.11.6 的视图偏好是持久化的）。</p>
 */
public final class VarsAccess{
    /** 三击打开属性界面的时间窗（毫秒）；0 = 关闭。设置项 {@code logicsugar.tripleTap}。 */
    public static int tripleTapMillis = 500;

    private static final TripleTap taps = new TripleTap();
    private static boolean initialized;

    private VarsAccess(){
    }

    public static void init(){
        if(initialized) return;
        initialized = true;

        Events.on(EventType.TapEvent.class, e -> {
            if(tripleTapMillis <= 0) return;
            Building build = e.tile == null ? null : e.tile.build;
            // 上游 v0.11.6：本客户端不可访问的方块（世界处理器、禁止编辑的方块）连对话框都不开——
            // VarsDialog 会去读一个这里拿不到的实体，三击直接崩。displayable() 是 public API，
            // 跨类加载器安全（Building 实现 ui.Displayable 的默认方法）。
            if(build != null && !build.displayable()) return;
            if(!taps.tap(build, System.currentTimeMillis(), tripleTapMillis)) return;
            // 与上游一样延到下一帧再开：TapEvent 还在输入处理中，此时弹对话框会和方块选中/
            // 配置面板的收起动作打架。
            Core.app.post(() -> new VarsDialog(build).show());
        });
    }

    /** 把已保存的设置读进运行期字段（设置页可能从未打开过）。 */
    public static void applySettings(){
        if(Core.settings == null) return;
        tripleTapMillis = Core.settings.getInt("logicsugar.tripleTap", tripleTapMillis);
        Snapshots.maxSnapshots = Core.settings.getInt("logicsugar.snapshotLimit", Snapshots.maxSnapshots);
        int frequency = Core.settings.getInt("logicsugar.varUpdateFrequency", VarsOptions.updateFrequency);
        VarsOptions.updateFrequency = frequency <= 0 ? 15 : frequency;
        VarsOptions.significantDigits = Core.settings.getInt("logicsugar.varsDigits", VarsOptions.significantDigits);
        VarsOptions.alignment = Core.settings.getInt("logicsugar.varsAlignment", VarsOptions.alignment);
        // 五个视图开关是持久化偏好（上游 v0.11.6 同）：对话框每次切换都会写盘，这里启动时读回
        VarsOptions.load();
        try{
            Snapshots.updateLimit();
        }catch(Throwable t){
            Log.warn("LogicSugar: failed to apply the snapshot limit: @", t);
        }
    }
}
