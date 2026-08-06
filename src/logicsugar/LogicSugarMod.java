package logicsugar;

import arc.Core;
import arc.scene.Element;
import arc.struct.Seq;
import arc.util.Log;
import mindustry.Vars;
import mindustry.game.EventType.ClientLoadEvent;
import mindustry.gen.LogicIO;
import mindustry.logic.LAssembler;
import mindustry.logic.LogicDialog;
import mindustry.logic.SugarLogicDialog;
import mindustry.logic.SugarStatements;
import mindustry.mod.Mod;
import mindustry.ui.dialogs.SettingsMenuDialog;
import logicsugar.assist.BoxSelect;
import logicsugar.assist.JumpLineColor;
import logicsugar.assist.expr.ExprHook;

import static arc.Events.on;

public class LogicSugarMod extends Mod{
    public static boolean bekBundled = false;

    private static boolean registered;

    @Override
    public void init(){
        registerStatements();
        on(ClientLoadEvent.class, event -> Core.app.post(() -> {
            if(Vars.ui != null && !(Vars.ui.logic instanceof SugarLogicDialog)){
                LogicDialog old = Vars.ui.logic;
                SugarLogicDialog sugar = new SugarLogicDialog();
                transferOverlayPanels(old, sugar);
                Vars.ui.logic = sugar;
            }
            if(Vars.ui != null && Vars.ui.logic != null){
                if(!bekBundled) JumpLineColor.setupSettings();
                Vars.ui.logic.hidden(JumpLineColor::clearCache);
                BoxSelect.init();
                ExprHook.init();
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
        LogicIO.allStatements.add(SugarStatements.CaseStatement::new);
        LogicIO.allStatements.add(SugarStatements.BreakStatement::new);
        LogicIO.allStatements.add(SugarStatements.BlockEndStatement::new);

        LAssembler.customParsers.put("forbegin", SugarStatements::parseForBegin);
        LAssembler.customParsers.put("forbeginc", tokens -> SugarStatements.parseForBegin(tokens, true));
        LAssembler.customParsers.put("whilebegin", SugarStatements::parseWhileBegin);
        LAssembler.customParsers.put("whilebeginc", tokens -> SugarStatements.parseWhileBegin(tokens, true));
        LAssembler.customParsers.put("switchbegin", SugarStatements::parseSwitchBegin);
        LAssembler.customParsers.put("switchbeginc", tokens -> SugarStatements.parseSwitchBegin(tokens, true));
        LAssembler.customParsers.put("case", SugarStatements::parseCase);
        LAssembler.customParsers.put("break", tokens -> new SugarStatements.BreakStatement());
        LAssembler.customParsers.put("blockend", tokens -> new SugarStatements.BlockEndStatement());

        // Read-only compatibility for markers produced by the first development version.
        LAssembler.customParsers.put("forend", tokens -> new SugarStatements.BlockEndStatement());
        LAssembler.customParsers.put("whileend", tokens -> new SugarStatements.BlockEndStatement());
        LAssembler.customParsers.put("switchend", tokens -> new SugarStatements.BlockEndStatement());
    }

    public void bekBuildSettings(SettingsMenuDialog.SettingsTable table){
        JumpLineColor.buildSettings(table);
    }
}
