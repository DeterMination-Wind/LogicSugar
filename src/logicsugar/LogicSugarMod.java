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
import logicsugar.assist.UnitFlags;
import logicsugar.assist.VarDisplayFilter;
import logicsugar.assist.data.ArrayBulkModule;
import logicsugar.assist.data.BitsetModule;
import logicsugar.assist.data.ChainModule;
import logicsugar.assist.data.ContainerModule;
import logicsugar.assist.data.DataModules;
import logicsugar.assist.data.ListHeapModule;
import logicsugar.assist.data.MapModule;
import logicsugar.assist.data.RecordModule;
import logicsugar.assist.data.SetModule;
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
                ProcessorStatus.init();
                ProcessorStatus.applySettings();
                UnitFlags.init();
                UnitFlags.applySettings();
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

    /** Shared registration for game init, the decompiler preflight and headless tests.
     *  Idempotent: repeated calls do not duplicate palette cards or parsers. */
    public static void registerStatements(){
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
        LogicIO.allStatements.add(SugarStatements.ArrayStatement::new);
        LogicIO.allStatements.add(SugarStatements.MatrixStatement::new);
        LogicIO.allStatements.add(SugarStatements.ArrayInitStatement::new);

        // Data subsystem modules (F2 framework): registering a module installs its
        // expression intrinsics provider (needed before the first compile/editor use);
        // registerParsers() below installs the declaration-card parsers. Both are
        // idempotent, so a repeated init() cannot duplicate palette entries or parsers.
        DataModules.register(new ArrayBulkModule());
        DataModules.register(new RecordModule());
        DataModules.register(new ContainerModule());
        DataModules.register(new BitsetModule());
        DataModules.register(new MapModule());
        DataModules.register(new SetModule());
        DataModules.register(new ListHeapModule());
        DataModules.register(new ChainModule());

        // single registration point shared with the decompiler preflight and the self-tests
        SugarStatements.installParsers();
        // record/stack/queue/deque/bitset/map/uset/list/heap/chain declaration cards + parsers
        DataModules.registerParsers();
    }

    /** Host (Neon) settings aggregation: function mode, library entry, overlays and jump line coloring. */
    public void bekBuildSettings(SettingsMenuDialog.SettingsTable table){
        table.pref(new LogicSugarSettings.FuncModeSetting(LogicSugarSettings.settingFuncMode, "normal"));
        table.pref(new LogicSugarSettings.AssertEmitSetting(LogicSugarSettings.settingAssertEmit, "strip"));
        table.pref(new LogicSugarSettings.LibraryButtonSetting("logicsugar.funclib"));
        LogicSugarSettings.addProcessorStatusPrefs(table);
        LogicSugarSettings.addUnitFlagsPref(table);
        LogicSugarSettings.addHideVarsPref(table);
        LogicSugarSettings.addBoxSelectPrefs(table);
        LogicSugarSettings.addCompactCardsPref(table);
        JumpLineColor.buildSettings(table);
    }
}
