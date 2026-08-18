package logicsugar;

import arc.Core;
import arc.scene.ui.Button;
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
        table.pref(new LibraryButtonSetting("logicsugar.funclib"));
        addHideVarsPref(table);
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
            table.left();
            table.add(title).padRight(12f).padLeft(4f);
            button = table.button(button -> button.add(label()), Styles.logict, () -> {
                current = SugarCompiler.FuncMode.parse(current) == SugarCompiler.FuncMode.normal ? "inline" : "normal";
                Core.settings.put(name, current);
                button.clearChildren();
                button.add(label());
            }).size(150f, 44f).get();
            addDesc(button);
            table.row();
        }

        private String label(){
            return Core.bundle.get("logicsugar.settings.funcmode." + current, current);
        }
    }

    /** Button entry to the function library editor; part of the settings list so rebuilds keep it. */
    public static class LibraryButtonSetting extends SettingsMenuDialog.SettingsTable.Setting{
        public LibraryButtonSetting(String name){
            super(name);
        }

        @Override
        public void add(SettingsMenuDialog.SettingsTable table){
            table.left();
            table.add(title).padRight(12f).padLeft(4f);
            Button button = table.button("@logicsugar.funclib.open", Icon.book, () -> new FunctionLibraryDialog().show())
                .size(220f, 46f).get();
            addDesc(button);
            table.row();
        }
    }
}
