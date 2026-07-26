package logicsugar;

import arc.Core;
import mindustry.Vars;
import mindustry.game.EventType.ClientLoadEvent;
import mindustry.gen.LogicIO;
import mindustry.logic.LAssembler;
import mindustry.logic.SugarLogicDialog;
import mindustry.logic.SugarStatements;
import mindustry.mod.Mod;

import static arc.Events.on;

public class LogicSugarMod extends Mod{
    private static boolean registered;

    @Override
    public void init(){
        registerStatements();
        on(ClientLoadEvent.class, event -> Core.app.post(() -> {
            if(Vars.ui != null && !(Vars.ui.logic instanceof SugarLogicDialog)){
                Vars.ui.logic = new SugarLogicDialog();
            }
        }));
    }

    private static void registerStatements(){
        if(registered) return;
        registered = true;

        LogicIO.allStatements.add(SugarStatements.ForBeginStatement::new);
        LogicIO.allStatements.add(SugarStatements.ForEndStatement::new);
        LogicIO.allStatements.add(SugarStatements.WhileBeginStatement::new);
        LogicIO.allStatements.add(SugarStatements.WhileEndStatement::new);
        LogicIO.allStatements.add(SugarStatements.SwitchBeginStatement::new);
        LogicIO.allStatements.add(SugarStatements.CaseStatement::new);
        LogicIO.allStatements.add(SugarStatements.BreakStatement::new);
        LogicIO.allStatements.add(SugarStatements.SwitchEndStatement::new);

        LAssembler.customParsers.put("forbegin", SugarStatements::parseForBegin);
        LAssembler.customParsers.put("forend", tokens -> new SugarStatements.ForEndStatement());
        LAssembler.customParsers.put("whilebegin", SugarStatements::parseWhileBegin);
        LAssembler.customParsers.put("whileend", tokens -> new SugarStatements.WhileEndStatement());
        LAssembler.customParsers.put("switchbegin", SugarStatements::parseSwitchBegin);
        LAssembler.customParsers.put("case", SugarStatements::parseCase);
        LAssembler.customParsers.put("break", tokens -> new SugarStatements.BreakStatement());
        LAssembler.customParsers.put("switchend", tokens -> new SugarStatements.SwitchEndStatement());
    }
}
