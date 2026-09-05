package logicsugar;

import arc.Core;
import arc.scene.Element;
import arc.struct.Seq;
import arc.util.Log;
import mindustry.Vars;
import mindustry.game.EventType.ClientLoadEvent;
import mindustry.gen.LogicIO;
import mindustry.logic.LogicDialog;
import mindustry.logic.SugarFunctions;
import mindustry.logic.SugarLogicDialog;
import mindustry.logic.SugarStatements;
import mindustry.mod.Mod;
import mindustry.ui.dialogs.SettingsMenuDialog;
import logicsugar.assist.BoxSelect;
import logicsugar.assist.JumpLineColor;
import logicsugar.assist.ProcessorStatus;
import logicsugar.assist.VarDisplayFilter;
import logicsugar.assist.expr.ExprHook;

import static arc.Events.on;

public class LogicSugarMod extends Mod{
    public static boolean bekBundled = false;

    private static boolean registered;

    @Override
    public void init(){
        registerStatements();
        SugarFunctions.setLibrarySource(FunctionLibrary::index);
        on(ClientLoadEvent.class, event -> Core.app.post(() -> {
            if(Vars.ui != null && !(Vars.ui.logic instanceof SugarLogicDialog)){
                LogicDialog old = Vars.ui.logic;
                SugarLogicDialog sugar = new SugarLogicDialog();
                transferOverlayPanels(old, sugar);
                Vars.ui.logic = sugar;
            }
            if(Vars.ui != null && Vars.ui.logic != null){
                Vars.ui.logic.hidden(JumpLineColor::clearCache);
                BoxSelect.init();
                ExprHook.init();
                VarDisplayFilter.init();
                logicsugar.assist.InstructionLimit.apply();
                ProcessorStatus.init();
                ProcessorStatus.applySettings();
                // When bundled into Neon, every settings row is registered through
                // bekBuildSettings (host sets bekBundled, host calls bekBuildSettings), so the
                // mod-owned category is skipped entirely to avoid duplicate entries.
                if(!bekBundled){
                    LogicSugarSettings.setup(true);
                }
            }
        }));
    }

    /** Transfers foreign overlay panels (e.g. MindustryX logic support) from the old dialog onto the new one.
     * Only children other than the canvas/buttons are moved, preserving their z-order above both. */
    private static void transferOverlayPanels(LogicDialog old, SugarLogicDialog sugar){
        if(old == null) return;
        int transferred = 0;
        Seq<Element> children = old.getChildren().copy();
        for(Element child : children){
            if(child == old.canvas || child == old.buttons) continue;
            child.remove();
            sugar.addChild(child);
            transferred++;
        }
        if(transferred > 0){
            sugar.invalidateHierarchy();
            Log.info("LogicSugar: transferred @ overlay panel(s)", transferred);
        }
    }

    private static void registerStatements(){
        if(registered) return;
        registered = true;

        LogicIO.allStatements.add(SugarStatements.ForBeginStatement::new);
        LogicIO.allStatements.add(SugarStatements.WhileBeginStatement::new);
        LogicIO.allStatements.add(SugarStatements.SwitchBeginStatement::new);
        LogicIO.allStatements.add(SugarStatements.IfBeginStatement::new);
        LogicIO.allStatements.add(SugarStatements.CaseStatement::new);
        LogicIO.allStatements.add(SugarStatements.ElseIfStatement::new);
        LogicIO.allStatements.add(SugarStatements.ElseStatement::new);
        LogicIO.allStatements.add(SugarStatements.BreakStatement::new);
        LogicIO.allStatements.add(SugarStatements.ContinueStatement::new);
        LogicIO.allStatements.add(SugarStatements.BlockEndStatement::new);
        LogicIO.allStatements.add(SugarStatements.FuncDefStatement::new);
        LogicIO.allStatements.add(SugarStatements.FuncCallStatement::new);
        LogicIO.allStatements.add(SugarStatements.ReturnStatement::new);

        // single registration point shared with the decompiler preflight and the self-tests
        SugarStatements.installParsers();
    }

    /** Host (Neon) settings aggregation: function mode, library entry and jump line coloring. */
    public void bekBuildSettings(SettingsMenuDialog.SettingsTable table){
        table.pref(new LogicSugarSettings.FuncModeSetting(LogicSugarSettings.settingFuncMode, "normal"));
        table.pref(new LogicSugarSettings.AssertEmitSetting(LogicSugarSettings.settingAssertEmit, "strip"));
        table.pref(new LogicSugarSettings.LibraryButtonSetting("logicsugar.funclib"));
        LogicSugarSettings.addInstructionLimitPref(table);
        LogicSugarSettings.addProcessorStatusPrefs(table);
        LogicSugarSettings.addHideVarsPref(table);
        LogicSugarSettings.addBoxSelectPrefs(table);
        LogicSugarSettings.addCompactCardsPref(table);
        JumpLineColor.buildSettings(table);
    }
}
