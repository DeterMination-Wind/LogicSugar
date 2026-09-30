package logicsugar;

import arc.struct.Seq;
import mindustry.Vars;
import mindustry.logic.GlobalVars;
import mindustry.logic.LAssembler;
import mindustry.logic.LStatement;
import mindustry.logic.LStatements.InvalidStatement;
import mindustry.logic.SugarCompiler;
import logicsugar.LogicSugarMod;

/** Flag-claim unit cards: vanilla product, delayed confirm, carrier restore. */
public class UnitControlTest{
    public static void main(String[] args){
        Vars.logicVars = new GlobalVars();
        LogicSugarMod.registerStatements();
        oneUnitClaimsThenConfirms();
        nextSkipsOwnedFlag();
        releaseClearsOwnFlagOnly();
        loopLowersBodyAndContinue();
        productIsVanilla();
        carrierRoundTrip();
        System.out.println("LogicSugar unit control self-test passed.");
    }

    private static void oneUnitClaimsThenConfirms(){
        String code = executable("unitbind @poly unit\nprint unit\n");
        check(code.contains("op mul __ls_ub_uid @thisx 100000\n"), "uid must be packed from @thisx: " + code);
        check(code.contains("op add __ls_ub_uid __ls_ub_uid @thisy\n"), "uid must include @thisy");
        check(code.contains("op add __ls_ub_uid __ls_ub_uid 1\n"), "uid must not be 0 at the origin");
        check(code.contains("ubind @poly\n"), "missing ubind");
        check(code.contains("sensor __ls_ub_c_0 @unit @controlled\n"), "must skip controlled units");
        int claim = code.indexOf("ucontrol flag __ls_ub_uid 0 0 0 0\n");
        check(claim >= 0, "missing flag claim");
        String after = code.substring(claim);
        check(after.startsWith("ucontrol flag __ls_ub_uid 0 0 0 0\nend\nend\nend\nend\nubind __ls_ub_h_0\n"),
            "claim must yield four ticks and rebind before confirm: " + after.substring(0, Math.min(after.length(), 180)));
        check(after.contains("sensor __ls_ub_f_0 @unit @flag\njump __ls_ub_loop_0 notEqual __ls_ub_f_0 __ls_ub_uid\n"),
            "confirm must reject a flag that is no longer ours");
        check(code.contains("jump __ls_ub_take_0 equal __ls_ub_f_0 __ls_ub_uid\n"), "must rebind a unit already wearing our flag");
        check(code.contains("set unit @unit\n"), "must assign the variable");
        check(code.contains("set unit null\n"), "must clear the variable when no unit is claimed");
    }

    private static void nextSkipsOwnedFlag(){
        String code = executable("unitnext @flare scout\n");
        check(code.contains("ubind @flare\n"), "next must scan the requested type");
        check(!code.contains("jump __ls_ub_take_0 equal __ls_ub_f_0 __ls_ub_uid\n"),
            "next must not treat an already-owned unit as the next idle one");
        check(code.contains("jump __ls_ub_loop_0 notEqual __ls_ub_f_0 0\n"), "next must skip non-zero flags");
        check(code.contains("set scout @unit\n"), "next must assign its variable");
    }

    private static void releaseClearsOwnFlagOnly(){
        String code = executable("unitfree unit\n");
        check(code.contains("ubind unit\n"), "release must bind the named unit");
        check(code.contains("jump __ls_ub_free_0 notEqual __ls_ub_f_0 __ls_ub_uid\n"), "release must ignore a foreign flag");
        check(code.contains("ucontrol flag 0 0 0 0 0\n"), "release must clear the flag");
        check(code.contains("ucontrol unbind 0 0 0 0 0\n"), "release must drop logic control");
        check(code.contains("set unit null\n"), "release must clear the variable");
    }

    private static void loopLowersBodyAndContinue(){
        String code = executable("unitfor 3 @poly unit 999\nprint unit\ncontinue\nblockend\nprint done\n");
        check(code.contains("print unit\n"), "loop body must be lowered");
        check(code.contains("jump __ls_ub_step_0 always x false\n"), "continue must jump to the loop step");
        check(code.contains("op add __ls_ub_n_0 __ls_ub_n_0 1\n"), "each delivered unit counts");
        check(code.contains("jump __ls_ub_scan_0 always x false\n"), "the loop must scan again");
        check(code.contains("op sub __ls_ub_r_0 3 __ls_ub_o_0\n"), "new claims must be capped at count - owned");
        check(code.contains("jump __ls_stmt_4 notEqual __ls_ub_p_0 0\n"), "a finished pass must leave the loop: " + code);
        String broken = executable("unitfor n @mega unit 999\nbreak\nblockend\nprint done\n");
        check(broken.contains("jump __ls_stmt_3 always x false\n"), "break must leave the unit loop: " + broken);
    }

    private static void productIsVanilla(){
        String code = executable("unitbind @poly unit\nunitnext @poly other\nunitfor 2 @poly unit 999\nprint unit\nblockend\nunitfree unit\n");
        for(String line : code.split("\n")){
            if(line.isEmpty() || line.endsWith(":") || line.startsWith("#")) continue;
            String op = line.split(" ")[0];
            check(op.equals("op") || op.equals("set") || op.equals("ubind") || op.equals("sensor")
                || op.equals("ucontrol") || op.equals("jump") || op.equals("end") || op.equals("print"),
                "non-vanilla instruction: " + line);
        }
        Seq<LStatement> read = LAssembler.read(code, true);
        for(LStatement statement : read){
            check(!(statement instanceof InvalidStatement), "vanilla assembler rejected: " + statement);
        }
    }

    private static void carrierRoundTrip(){
        String source = "unitbind @poly unit\nunitfor 2 @poly unit 999\nprint unit\nblockend\nunitnext @mega extra\nunitfree unit\n";
        String compiled = SugarCompiler.compile(source, SugarCompiler.FuncMode.normal);
        check(SugarCompiler.isSugarProgram(compiled), "missing carrier");
        String restored = SugarCompiler.restore(compiled);
        check(restored.contains("unitbind @poly unit"), restored);
        check(restored.contains("unitfor 2 @poly unit"), restored);
        check(restored.contains("unitnext @mega extra"), restored);
        check(restored.contains("unitfree unit"), restored);
        check(SugarCompiler.verifyRestore(compiled, restored), "verifyRestore rejected the unit-control carrier");
    }

    private static String executable(String sugar){
        String compiled = SugarCompiler.compile(sugar, SugarCompiler.FuncMode.normal);
        int at = compiled.indexOf("set __ls_sugar ");
        check(at >= 0, "no carrier in " + compiled);
        return compiled.substring(0, at);
    }

    private static void check(boolean condition, String message){
        if(!condition) throw new AssertionError(message);
    }
}
