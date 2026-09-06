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

/**
 * Logic Sugar settings: function expansion mode (normal/inline), the function library
 * editor entry, and (when not bundled elsewhere) jump line coloring.
 *
 * <p>Everything is added through the {@link SettingsTable} list API so the "reset" button
 * and category rebuilds cannot drop entries.
 */
public final class LogicSugarSettings{
    public static final String settingFuncMode = "logicsugar.funcMode";
    public static final String settingSwitchStrategy = "logicsugar.switchStrategy";
    public static final String settingAssertEmit = "logicsugar.assertEmit";

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
        }catch(Exception e){
            Log.warn("LogicSugar: failed to setup settings: @", e);
        }
    }

    private static void build(SettingsMenuDialog.SettingsTable table, boolean includeJumpLines){
        table.pref(new FuncModeSetting(settingFuncMode, "normal"));
        table.pref(new SwitchStrategySetting(settingSwitchStrategy, "auto"));
        table.pref(new AssertEmitSetting(settingAssertEmit, "strip"));
        table.pref(new LibraryButtonSetting("logicsugar.funclib"));
        addProcessorStatusPrefs(table);
        addHideVarsPref(table);
        addBoxSelectPrefs(table);
        addCompactCardsPref(table);
        if(includeJumpLines){
            logicsugar.assist.JumpLineColor.buildSettings(table);
        }
    }

    /** Checkbox for hiding compiler-generated variables in MindustryX's variable browser. */
    static void addHideVarsPref(SettingsMenuDialog.SettingsTable table){
        table.checkPref(logicsugar.assist.VarDisplayFilter.settingHideVars, true, b -> {
            if(b) logicsugar.assist.VarDisplayFilter.applyToAll();
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

    /** Sliders for the processor status overlay (wait threshold, scan rate, warn effects). */
    static void addProcessorStatusPrefs(SettingsMenuDialog.SettingsTable table){
        table.sliderPref("logicsugar.waitIndication", 1000, 0, 10000, 500, i -> {
            logicsugar.assist.ProcessorStatus.minWaitMillis = i;
            return i == 0 ? Core.bundle.get("logicsugar.off", "off") : (i / 1000.0) + "s";
        });
        table.sliderPref("logicsugar.processorScan", 50, 5, 200, 5, i -> {
            logicsugar.assist.ProcessorStatus.scanPerTick = i;
            return Integer.toString(i);
        });
        table.sliderPref("logicsugar.warnEffect", 0, -5, 60, 5, i -> {
            logicsugar.assist.ProcessorStatus.warnEffectFrequency = i;
            return i < 0 ? Core.bundle.get("logicsugar.warn.never", "never")
                : i == 0 ? Core.bundle.get("logicsugar.warn.once", "once")
                : Core.bundle.format("logicsugar.warn.every", i);
        });
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
            // into two cells would get pushed right past the panel by the wide vanilla rows
            addDesc(table.table(box -> {
                box.left();
                box.add(title).padRight(12f).padLeft(4f);
                button = box.button(button -> button.add(label()), Styles.logict, () -> {
                    current = SugarCompiler.FuncMode.parse(current) == SugarCompiler.FuncMode.normal ? "inline" : "normal";
                    Core.settings.put(name, current);
                    button.clearChildren();
                    button.add(label());
                }).size(150f, 44f).get();
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
            // into two cells would get pushed right past the panel by the wide vanilla rows
            addDesc(table.table(box -> {
                box.left();
                box.add(title).padRight(12f).padLeft(4f);
                button = box.button(button -> button.add(label()), Styles.logict, () -> {
                    current = SugarCompiler.SwitchStrategy.parse(current) == SugarCompiler.SwitchStrategy.auto
                        ? "chainOnly" : "auto";
                    Core.settings.put(name, current);
                    button.clearChildren();
                    button.add(label());
                }).size(150f, 44f).get();
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
            // into two cells would get pushed right past the panel by the wide vanilla rows
            addDesc(table.table(box -> {
                box.left();
                box.add(title).padRight(12f).padLeft(4f);
                button = box.button(button -> button.add(label()), Styles.logict, () -> {
                    current = SugarCompiler.AssertEmit.parse(current) == SugarCompiler.AssertEmit.strip
                        ? "emit" : "strip";
                    Core.settings.put(name, current);
                    button.clearChildren();
                    button.add(label());
                }).size(150f, 44f).get();
            }).minWidth(Math.min(500f, Core.graphics.getWidth() / 1.2f / Scl.scl(1f))).fillX().left().padTop(4f).get());
            table.row();
        }

        private String label(){
            return Core.bundle.get("logicsugar.settings.assertemit." + current, current);
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
