package logicsugar;

import arc.Core;
import arc.scene.ui.Button;
import arc.scene.ui.layout.Scl;
import arc.util.Log;
import mindustry.Vars;
import mindustry.gen.Icon;
import mindustry.logic.SugarCompiler;
import mindustry.ui.Styles;
import mindustry.ui.dialogs.SettingsMenuDialog;

import java.util.HashMap;
import java.util.Map;

/**
 * Logic Sugar settings: function expansion mode (normal/inline), the function library
 * editor entry, processor-status / unit-flag overlays, and (when not bundled
 * elsewhere) jump line coloring.
 *
 * <p>Everything is added through the {@link SettingsTable} list API so the "reset" button
 * and category rebuilds cannot drop entries.
 */
public final class LogicSugarSettings{
    public static final String settingFuncMode = "logicsugar.funcMode";
    public static final String settingSwitchStrategy = "logicsugar.switchStrategy";
    public static final String settingAssertEmit = "logicsugar.assertEmit";
    /** Profiler 的空数据页是否直接开始统计（也就是「Do not show again」勾选框写入的键）。
     *  默认 false：profiler 是观测工具，不能不经用户同意就开始包装指令。 */
    public static final String settingStartProfilerImmediately = "logicsugar.startProfilerImmediately";

    /** The page this mod owns. Kept so its descriptions can be re-flowed whenever the page is
     *  opened: the line breaks depend on the window width, which can change while the game runs. */
    private static SettingsMenuDialog.SettingsTable settingsTable;
    /** Description text exactly as the bundle delivered it, keyed by setting name. Re-flowing
     *  always starts from this, never from a previously wrapped string, so a wider window really
     *  does get wider lines back instead of staying at the narrowest width ever seen. */
    private static final Map<String, String> rawDescriptions = new HashMap<>();

    private LogicSugarSettings(){}

    /** Adds the Logic Sugar settings category (idempotent). */
    public static void setup(boolean includeJumpLines){
        try{
            SettingsMenuDialog dialog = Vars.ui.settings;
            if(dialog == null) return;
            for(SettingsMenuDialog.SettingsCategory category : dialog.getCategories()){
                if(category.name.equals("@logicsugar.settings")) return;
            }
            dialog.addCategory("@logicsugar.settings", Icon.edit, table -> build(table, includeJumpLines));
            if(settingsTable != null){
                // Vanilla gives a tooltip no width limit at all — it only clamps the position — so
                // a 500-character description slides off both edges. Re-flow it for the window in
                // use at open time; the pass costs nothing when the widths already agree.
                dialog.shown(() -> reflowDescriptions(settingsTable));
            }
        }catch(Exception e){
            Log.warn("LogicSugar: failed to setup settings: @", e);
        }
    }

    private static void build(SettingsMenuDialog.SettingsTable table, boolean includeJumpLines){
        settingsTable = table;
        table.pref(new FuncModeSetting(settingFuncMode, "normal"));
        table.pref(new SwitchStrategySetting(settingSwitchStrategy, "auto"));
        table.pref(new AssertEmitSetting(settingAssertEmit, "strip"));
        table.pref(new EditorConflictSetting(LogicSugarMod.settingEditorConflict, LogicSugarMod.EditorConflict.ask.id));
        table.pref(new LibraryButtonSetting("logicsugar.funclib"));
        addProcessorStatusPrefs(table);
        addVarsPrefs(table);
        addUnitFlagsPref(table);
        addHideVarsPref(table);
        addBoxSelectPrefs(table);
        addCompactCardsPref(table);
        if(includeJumpLines){
            logicsugar.assist.JumpLineColor.buildSettings(table);
        }
        // Settings descriptions are prose and run to ~500 characters, while vanilla's tooltip has
        // no width limit at all, so they used to slide off both edges of the screen. Re-flow them
        // once the list is complete — addDesc() captured the raw text while each setting was being
        // added, which is what the rebuild at the end of reflowDescriptions() corrects. The
        // deferred pass from setup() retries this for whatever window is in use when the page is
        // actually opened, and re-does it after a resize.
        reflowDescriptions(table);
    }

    /**
     * Re-wraps every setting description in place so its hover tooltip fits on screen.
     *
     * <p>Rewriting {@code description} rather than the tooltip label is what makes this survive the
     * "Reset to Defaults" button: that calls {@code SettingsTable.rebuild()}, which re-runs
     * {@code add()} for every setting and builds a fresh tooltip from the same field.</p>
     */
    private static void reflowDescriptions(SettingsMenuDialog.SettingsTable table){
        try{
            boolean changed = false;
            for(SettingsMenuDialog.SettingsTable.Setting setting : table.getSettings()){
                if(setting.name == null || setting.description == null || setting.description.isEmpty()) continue;
                // always re-flow the bundle text, never a previous result: wrapping can only split
                // lines further, so starting from a wrapped string would never widen back after
                // the window grows
                String raw = rawDescriptions.get(setting.name);
                if(raw == null){
                    raw = setting.description;
                    rawDescriptions.put(setting.name, raw);
                }
                String wrapped = logicsugar.assist.SugarTooltip.fit(raw);
                if(!wrapped.equals(setting.description)){
                    setting.description = wrapped;
                    changed = true;
                }
            }
            if(changed) table.rebuild();
        }catch(Exception e){
            Log.warn("LogicSugar: could not re-flow setting descriptions: @", e);
        }
    }

    /** Checkbox for hiding compiler-generated variables in MindustryX's variable viewers. */
    static void addHideVarsPref(SettingsMenuDialog.SettingsTable table){
        table.checkPref(logicsugar.assist.VarDisplayFilter.settingHideVars, true, b -> {
            // Both directions: turning the setting off must restore the full arrays at once.
            logicsugar.assist.VarDisplayFilter.applyToAll();
        });
    }

    /** Checkbox for the compact card layout (zero spacing between statement cards). */
    static void addCompactCardsPref(SettingsMenuDialog.SettingsTable table){
        table.checkPref(mindustry.logic.SugarCanvas.settingCompactCards, true, b -> {
            mindustry.logic.SugarCanvas.refreshLayoutSpace();
        });
    }

    /** Checkboxes for BoxSelect drag behavior (Ctrl+click copy and Ctrl+drag copy). */
    static void addBoxSelectPrefs(SettingsMenuDialog.SettingsTable table){
        table.checkPref(logicsugar.assist.BoxSelect.settingCtrlClickCopy, true);
        table.checkPref(logicsugar.assist.BoxSelect.settingCtrlDragCopy, true);
        // 拖动时是否临时把积木间距扩到 10f。关闭可根治视野/虚拟块偏移，但往折叠语句拖语句会更"随机"。
        table.checkPref(logicsugar.assist.BoxSelect.settingDragExpandSpacing, false);
    }

    /** Sliders for the processor status overlay (wait threshold, scan rate, warn effects)
     *  plus the breakpoint behavior switches (upstream v0.8.2). */
    static void addProcessorStatusPrefs(SettingsMenuDialog.SettingsTable table){
        table.checkPref("logicsugar.disableBreakpoints", false,
            b -> logicsugar.assist.ProcessorStatus.disableBreakpoints = b);
        table.checkPref("logicsugar.assertsAreBreakpoints", false,
            b -> logicsugar.assist.ProcessorStatus.assertsAreBreakpoints = b);
        table.checkPref("logicsugar.detachCameraOnBreakpoint", true,
            b -> logicsugar.assist.ProcessorStatus.detachCameraOnBreakpoint = b);
        table.sliderPref("logicsugar.waitIndication", 1000, 0, 10000, 500, i -> {
            logicsugar.assist.ProcessorStatus.minWaitMillis = i;
            return i == 0 ? Core.bundle.get("logicsugar.off", "off") : (i / 1000.0) + "s";
        });
        // stored value is the step index into ProcessorStatus.UPDATES_PER_TICK
        table.sliderPref("logicsugar.processorScan", 4, 0, logicsugar.assist.ProcessorStatus.UPDATES_PER_TICK.length - 1, i -> {
            int value = logicsugar.assist.ProcessorStatus.updatesPerTick(i);
            logicsugar.assist.ProcessorStatus.scanPerTick = value;
            return Integer.toString(value);
        });
        table.sliderPref("logicsugar.warnEffect", 0, -5, 60, 5, i -> {
            logicsugar.assist.ProcessorStatus.warnEffectFrequency = i;
            return i < 0 ? Core.bundle.get("logicsugar.warn.never", "never")
                : i == 0 ? Core.bundle.get("logicsugar.warn.once", "once")
                : Core.bundle.format("logicsugar.warn.every", i);
        });
    }

    /** Checkboxes for drawing unit flags and assigning distinct colors per flag value. */
    static void addUnitFlagsPref(SettingsMenuDialog.SettingsTable table){
        table.checkPref(logicsugar.assist.UnitFlags.settingShowFlags, false,
            b -> logicsugar.assist.UnitFlags.enabled = b);
        table.checkPref(logicsugar.assist.UnitFlags.settingColorizeFlags, false,
            b -> logicsugar.assist.UnitFlags.colorize = b);
    }

    /** Click-to-cycle picker for the function expansion mode. */
    public static class FuncModeSetting extends SettingsMenuDialog.SettingsTable.Setting{
        private final String def;
        private String current;
        private Button button;

        public FuncModeSetting(String name, String def){
            super(name);
            this.def = def;
            Core.settings.defaults(name, def);
            this.current = Core.settings.getString(name, def);
        }

        @Override
        public void add(SettingsMenuDialog.SettingsTable table){
            // single-cell row: the settings table is a grid, so splitting title/control
            // into two cells would get pushed right past the panel by the wide vanilla rows.
            // The button uses minWidth, never a fixed width: the value label (e.g. English
            // "auto (table when cheaper)") is wider than any fixed width would allow, and a
            // fixed cell made the label overflow onto the title text.
            addDesc(table.table(box -> {
                box.left();
                box.add(title).padRight(12f).padLeft(4f);
                button = box.button(button -> button.add(label()), Styles.logict, () -> {
                    current = SugarCompiler.FuncMode.parse(current) == SugarCompiler.FuncMode.normal ? "inline" : "normal";
                    Core.settings.put(name, current);
                    button.clearChildren();
                    button.add(label());
                }).minWidth(150f).height(44f).get();
            }).minWidth(Math.min(500f, Core.graphics.getWidth() / 1.2f / Scl.scl(1f))).fillX().left().padTop(4f).get());
            table.row();
        }

        private String label(){
            return Core.bundle.get("logicsugar.settings.funcmode." + current, current);
        }
    }

    /** Click-to-cycle picker for the switch lowering strategy (auto / chainOnly). */
    public static class SwitchStrategySetting extends SettingsMenuDialog.SettingsTable.Setting{
        private final String def;
        private String current;
        private Button button;

        public SwitchStrategySetting(String name, String def){
            super(name);
            this.def = def;
            Core.settings.defaults(name, def);
            this.current = Core.settings.getString(name, def);
        }

        @Override
        public void add(SettingsMenuDialog.SettingsTable table){
            // single-cell row: the settings table is a grid, so splitting title/control
            // into two cells would get pushed right past the panel by the wide vanilla rows.
            // The button uses minWidth, never a fixed width: the value label (e.g. English
            // "auto (table when cheaper)") is wider than any fixed width would allow, and a
            // fixed cell made the label overflow onto the title text.
            addDesc(table.table(box -> {
                box.left();
                box.add(title).padRight(12f).padLeft(4f);
                button = box.button(button -> button.add(label()), Styles.logict, () -> {
                    current = SugarCompiler.SwitchStrategy.parse(current) == SugarCompiler.SwitchStrategy.auto
                        ? "chainOnly" : "auto";
                    Core.settings.put(name, current);
                    button.clearChildren();
                    button.add(label());
                }).minWidth(150f).height(44f).get();
            }).minWidth(Math.min(500f, Core.graphics.getWidth() / 1.2f / Scl.scl(1f))).fillX().left().padTop(4f).get());
            table.row();
        }

        private String label(){
            return Core.bundle.get("logicsugar.settings.switchstrategy." + current, current);
        }
    }

    public static class AssertEmitSetting extends SettingsMenuDialog.SettingsTable.Setting{
        private final String def;
        private String current;
        private Button button;

        public AssertEmitSetting(String name, String def){
            super(name);
            this.def = def;
            Core.settings.defaults(name, def);
            this.current = Core.settings.getString(name, def);
        }

        @Override
        public void add(SettingsMenuDialog.SettingsTable table){
            // single-cell row: the settings table is a grid, so splitting title/control
            // into two cells would get pushed right past the panel by the wide vanilla rows.
            // The button uses minWidth, never a fixed width: the value label (e.g. English
            // "auto (table when cheaper)") is wider than any fixed width would allow, and a
            // fixed cell made the label overflow onto the title text.
            addDesc(table.table(box -> {
                box.left();
                box.add(title).padRight(12f).padLeft(4f);
                button = box.button(button -> button.add(label()), Styles.logict, () -> {
                    current = SugarCompiler.AssertEmit.parse(current) == SugarCompiler.AssertEmit.strip
                        ? "emit" : "strip";
                    Core.settings.put(name, current);
                    button.clearChildren();
                    button.add(label());
                }).minWidth(150f).height(44f).get();
            }).minWidth(Math.min(500f, Core.graphics.getWidth() / 1.2f / Scl.scl(1f))).fillX().left().padTop(4f).get());
            table.row();
        }

        private String label(){
            return Core.bundle.get("logicsugar.settings.assertemit." + current, current);
        }
    }

    /** 快照上限的档位表（与上游 MlogAssertions 相同）；0 = 完全关闭快照：不创建、不显示、
     *  配置面板上的快照按钮也不出现。 */
    static final int[] SNAPSHOT_LIMITS = {0, 5, 10, 20, 50, 100, 200, 500, 1000};

    /** 变量/内存/属性界面与快照的设置行。与 {@link #addProcessorStatusPrefs} 同一约定：自有
     *  设置页（{@link #build}）与 Neon 聚合页（{@code LogicSugarMod.bekBuildSettings}）都必须
     *  调用本方法，否则聚合形态下这些设置无从修改（AGENTS.md 的双形态要求）。 */
    static void addVarsPrefs(SettingsMenuDialog.SettingsTable table){
        // 三击任意方块打开属性界面的时间窗（上游 setting.triple-tap-speed）
        table.sliderPref("logicsugar.tripleTap", 500, 0, 3000, 50, i -> {
            logicsugar.vars.ui.VarsAccess.tripleTapMillis = i;
            return i == 0 ? Core.bundle.get("logicsugar.off", "off") : i + " ms";
        });
        // stored value is the step index into SNAPSHOT_LIMITS
        table.sliderPref("logicsugar.snapshotLimit", 3, 0, SNAPSHOT_LIMITS.length - 1, i -> {
            int value = SNAPSHOT_LIMITS[i];
            logicsugar.vars.Snapshots.maxSnapshots = value;
            logicsugar.vars.Snapshots.updateLimit();
            return value == 0 ? Core.bundle.get("logicsugar.off", "off") : Integer.toString(value);
        });
        table.checkPref("logicsugar.snapshotOnBreakpoint", false,
            b -> logicsugar.assist.ProcessorStatus.snapshotOnBreakpoint = b);
        table.checkPref("logicsugar.snapshotOnAssertion", false,
            b -> logicsugar.assist.ProcessorStatus.snapshotOnAssertion = b);
        table.sliderPref("logicsugar.varUpdateFrequency", 15, 1, 60, 5, i -> {
            logicsugar.vars.VarsOptions.updateFrequency = i;
            return Core.bundle.format("logicsugar.vars.ticks", i);
        });
        table.sliderPref("logicsugar.varsDigits", 7, 3, 16, 1, i -> {
            logicsugar.vars.VarsOptions.significantDigits = i;
            return i >= 16 ? Core.bundle.get("logicsugar.vars.fullDigits", "all digits") : Integer.toString(i);
        });
        table.pref(new VarsAlignmentSetting("logicsugar.varsAlignment", logicsugar.vars.VarsOptions.alignment));
        // profiler（上游 v0.11.3 的 start-profiler-immediatelly）：默认关闭；在 Vars 界面的
        // 「Do not show again」勾选它，下一次打开 profiler 页面就直接开始统计。
        table.checkPref(settingStartProfilerImmediately, false);
    }

    /** 变量表数值列的对齐方式（左/中/右循环，存 arc 的 {@code Align} 常量）。 */
    public static class VarsAlignmentSetting extends SettingsMenuDialog.SettingsTable.Setting{
        private static final int[] VALUES = {arc.util.Align.left, arc.util.Align.center, arc.util.Align.right};
        private int current;
        private Button button;

        public VarsAlignmentSetting(String name, int def){
            super(name);
            Core.settings.defaults(name, def);
            this.current = Core.settings.getInt(name, def);
            logicsugar.vars.VarsOptions.alignment = current;
        }

        @Override
        public void add(SettingsMenuDialog.SettingsTable table){
            // single-cell row for the same grid alignment reason as FuncModeSetting
            addDesc(table.table(box -> {
                box.left();
                box.add(title).padRight(12f).padLeft(4f);
                button = box.button(button -> button.add(label()), Styles.logict, () -> {
                    int index = 0;
                    for(int i = 0; i < VALUES.length; i++){
                        if(VALUES[i] == current) index = i;
                    }
                    current = VALUES[(index + 1) % VALUES.length];
                    Core.settings.put(name, current);
                    logicsugar.vars.VarsOptions.alignment = current;
                    button.clearChildren();
                    button.add(label());
                }).minWidth(150f).height(44f).get();
            }).minWidth(Math.min(500f, Core.graphics.getWidth() / 1.2f / Scl.scl(1f))).fillX().left().padTop(4f).get());
            table.row();
        }

        private String label(){
            return Core.bundle.get("logicsugar.vars.align." + current, Integer.toString(current));
        }
    }

    /** Click-to-cycle picker for how the install pass treats a logic editor another mod already owns. */
    public static class EditorConflictSetting extends SettingsMenuDialog.SettingsTable.Setting{
        private final String def;
        private String current;
        private Button button;

        public EditorConflictSetting(String name, String def){
            super(name);
            this.def = def;
            Core.settings.defaults(name, def);
            this.current = Core.settings.getString(name, def);
        }

        @Override
        public void add(SettingsMenuDialog.SettingsTable table){
            // single-cell row for the same grid alignment reason as FuncModeSetting
            addDesc(table.table(box -> {
                box.left();
                box.add(title).padRight(12f).padLeft(4f);
                button = box.button(button -> button.add(label()), Styles.logict, () -> {
                    current = next(LogicSugarMod.EditorConflict.parse(current)).id;
                    Core.settings.put(name, current);
                    button.clearChildren();
                    button.add(label());
                    // Apply it now: the setting used to be read only during startup, so switching it
                    // looked broken until the next launch, and the two states the user could see
                    // (label vs. live editor) disagreed.
                    LogicSugarMod.reapplyEditorConflict();
                }).minWidth(150f).height(44f).get();
            }).minWidth(Math.min(500f, Core.graphics.getWidth() / 1.2f / Scl.scl(1f))).fillX().left().padTop(4f).get());
            table.row();
        }

        /** takeover -> ask -> stepAside -> coexist -> takeover, so the default is one click away in
         *  either direction. Package-private so the test can pin the invariant that actually matters:
         *  one lap visits every state exactly once and returns to the start. */
        static LogicSugarMod.EditorConflict next(LogicSugarMod.EditorConflict value){
            return switch(value){
                case takeover -> LogicSugarMod.EditorConflict.ask;
                case ask -> LogicSugarMod.EditorConflict.stepAside;
                case stepAside -> LogicSugarMod.EditorConflict.coexist;
                case coexist -> LogicSugarMod.EditorConflict.takeover;
            };
        }

        private String label(){
            return Core.bundle.get("logicsugar.settings.editorconflict." + current, current);
        }
    }

    /** Button entry to the function library editor; part of the settings list so rebuilds keep it. */
    public static class LibraryButtonSetting extends SettingsMenuDialog.SettingsTable.Setting{
        public LibraryButtonSetting(String name){
            super(name);
        }

        @Override
        public void add(SettingsMenuDialog.SettingsTable table){
            // single-cell row for the same grid alignment reason as FuncModeSetting
            addDesc(table.table(box -> {
                box.left();
                box.add(title).padRight(12f).padLeft(4f);
                box.button("@logicsugar.funclib.open", Icon.book, () -> new FunctionLibraryDialog().show()).size(220f, 46f);
            }).minWidth(Math.min(500f, Core.graphics.getWidth() / 1.2f / Scl.scl(1f))).fillX().left().padTop(4f).get());
            table.row();
        }
    }
}
