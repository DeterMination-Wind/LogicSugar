package logicsugar;

import arc.struct.Seq;
import mindustry.logic.LAssembler;
import mindustry.logic.LStatement;
import mindustry.logic.SugarCompiler;
import mindustry.logic.SugarStatements;
import mindustry.logic.SugarStatements.BlockEndStatement;
import mindustry.logic.SugarStatements.BreakStatement;
import mindustry.logic.SugarStatements.ForBeginStatement;
import mindustry.logic.SugarStatements.WhileBeginStatement;
import mindustry.logic.LStatements.JumpStatement;
import logicsugar.assist.expr.ExprCompiler;
import logicsugar.assist.expr.ExprHook;

import java.util.List;

public class SugarCompilerSelfTest{
    public static void main(String[] args){
        registerParsers();
        nestedProgramRoundTrips();
        legacyEndsMigrate();
        malformedStructuresFail();
        semanticErrorsAreLocated();
        jumpsMayTargetStructureBoundaries();
        breaksLeaveNearestStructure();
        generatedCodeIsOptimized();
        vanillaCodePassesThrough();
        expressionOpsRoundTrip();
        structuredTargetsFollowExpressionResize();
        System.out.println("LogicSugar compiler self-test passed.");
    }

    private static void nestedProgramRoundTrips(){
        String sugar = """
            forbeginc i 0 1 lessThanEq 3 2
            set x 1
            blockend
            whilebegin true 11
            switchbegin x 10
            case 1
            print one
            break
            case 2
            print two
            blockend
            blockend
            """;

        String compiled = SugarCompiler.compile(sugar);
        check(SugarCompiler.restore(compiled).equals(sugar), "marker round-trip changed sugar source");
        check(compiled.contains("# @logic-sugar-line forbeginc"), "collapsed state was not persisted");

        Seq<LStatement> lowered = LAssembler.read(compiled, true);
        check(lowered.size == 18, "unexpected lowered instruction count: " + lowered.size);
        for(LStatement statement : lowered){
            check(statement.getClass().getEnclosingClass() != SugarStatements.class, "compiled program contains a sugar statement");
        }
    }

    private static void legacyEndsMigrate(){
        String legacy = "forbegin i 0 1 lessThanEq 3 2\nset x 1\nforend\n";
        String migrated = LAssembler.write(LAssembler.read(legacy, true));
        check(migrated.contains("blockend"), "legacy end did not migrate to blockend");
        check(!migrated.contains("forend"), "legacy end remained in serialized sugar source");
    }

    private static void malformedStructuresFail(){
        expectFailure("forbegin i 0 1 lessThanEq 3 3\nwhilebegin true 4\nblockend\nblockend\n", "crossing blocks");
        expectFailure("forbegin i 0 1 lessThanEq 3 2\nwhilebegin true 2\nblockend\n", "shared end");
        expectFailure("blockend\n", "orphan end");
    }

    private static void vanillaCodePassesThrough(){
        String vanilla = "set x 1\nprint x\n";
        check(SugarCompiler.compile(vanilla).equals(vanilla), "vanilla code was modified");
    }

    private static void semanticErrorsAreLocated(){
        Seq<LStatement> statements = LAssembler.read("case 1\nbreak\n", true);
        boolean[] invalid = SugarCompiler.invalidStatements(statements);
        check(invalid[0], "bare case was not marked invalid");
        check(invalid[1], "bare break was not marked invalid");
        expectFailure("case 1\n", "bare case");
        expectFailure("break\n", "bare break");
    }

    private static void jumpsMayTargetStructureBoundaries(){
        String forContinue = """
            forbegin i 0 1 lessThanEq 3 2
            jump 2 always x false
            blockend
            """;
        String compiledFor = SugarCompiler.compile(forContinue);
        check(compiledFor.contains("jump __ls_stmt_2 always x false"),
            "jump to a block end did not preserve the continue target");

        String beginTarget = """
            whilebegin true 2
            jump 0 always x false
            blockend
            """;
        String compiledWhile = SugarCompiler.compile(beginTarget);
        check(compiledWhile.contains("jump __ls_stmt_0 always x false"),
            "jump to a structured begin was rejected or retargeted");
    }

    private static void breaksLeaveNearestStructure(){
        String forBreak = """
            forbegin i 0 1 lessThanEq 3 2
            break
            blockend
            """;
        check(SugarCompiler.compile(forBreak).contains("jump __ls_stmt_3 always x false"),
            "break inside a for did not leave the loop");

        String whileBreak = """
            whilebegin true 2
            break
            blockend
            """;
        check(SugarCompiler.compile(whileBreak).contains("jump __ls_stmt_3 always x false"),
            "break inside a while did not leave the loop");

        String nested = """
            whilebegin true 5
            switchbegin x 3
            break
            blockend
            print after-switch
            blockend
            """;
        check(SugarCompiler.compile(nested).contains("jump __ls_stmt_4 always x false"),
            "break did not choose the nearest enclosing switch");
    }

    private static void generatedCodeIsOptimized(){
        String sugar = """
            whilebegin true 6
            op div _0 4 5
            op div _1 5 4
            op mul _0 _0 _1
            op add _0 i _0
            op sqrt x _0 0
            blockend
            """;
        String compiled = SugarCompiler.compile(sugar);
        String lowered = loweredCode(compiled);
        check(lowered.contains("op add _0 i 1"), "constant expression was not folded");
        check(!lowered.contains("op div _0 4 5"), "unused constant division remained in output");
        check(!lowered.contains("__ls_stmt_1:"), "unreferenced statement label was emitted");

        String switchCode = loweredCode(SugarCompiler.compile("""
            switchbegin x 3
            case 1
            print one
            blockend
            """));
        check(switchCode.contains("jump __ls_case_1 equal x 1"), "switch did not compare its source value directly");
        check(!switchCode.contains("__ls_switch_"), "switch temporary variable was emitted");
    }

    private static String loweredCode(String compiled){
        int marker = compiled.indexOf("# @logic-sugar-v1 begin");
        return marker < 0 ? compiled : compiled.substring(0, marker);
    }

    private static void expressionOpsRoundTrip(){
        List<ExprCompiler.OpLine> ops = ExprCompiler.compile("result", "cos(a) * 10 + x");
        check(opText(ops).equals("op cos _0 a 0\nop mul _0 _0 10\nop add result _0 x"),
            "expression compiler emitted unexpected op chain");

        String restored = ExprCompiler.rebuild(ops);
        check(restored != null, "expression compiler did not restore an op chain");
        check(opText(ExprCompiler.compile("result", restored)).equals(opText(ops)),
            "restored expression changed the generated op chain");
    }

    private static String opText(List<ExprCompiler.OpLine> ops){
        StringBuilder result = new StringBuilder();
        for(int i = 0; i < ops.size(); i++){
            if(i > 0) result.append('\n');
            result.append(ops.get(i).toText());
        }
        return result.toString();
    }

    private static void structuredTargetsFollowExpressionResize(){
        ForBeginStatement forBegin = new ForBeginStatement();
        forBegin.destIndex = 9;
        WhileBeginStatement whileBegin = new WhileBeginStatement();
        whileBegin.destIndex = 7;
        JumpStatement jump = new JumpStatement();
        jump.destIndex = 8;

        // Folding three op statements into one removes two statements at index 4.
        ExprHook.adjustStatementIndex(forBegin, 4, -2);
        ExprHook.adjustStatementIndex(whileBegin, 4, -2);
        ExprHook.adjustStatementIndex(jump, 4, -2);
        check(forBegin.destIndex == 7, "for end target was not shifted after Expr folding");
        check(whileBegin.destIndex == 5, "while end target was not shifted after Expr folding");
        check(jump.destIndex == 6, "jump target was not shifted after Expr folding");

        // Expanding the Expr block restores the original target positions.
        ExprHook.adjustStatementIndex(forBegin, 1, 2);
        ExprHook.adjustStatementIndex(whileBegin, 1, 2);
        ExprHook.adjustStatementIndex(jump, 1, 2);
        check(forBegin.destIndex == 9, "for end target was not restored after Expr expansion");
        check(whileBegin.destIndex == 7, "while end target was not restored after Expr expansion");
        check(jump.destIndex == 8, "jump target was not restored after Expr expansion");
    }

    private static void expectFailure(String source, String scenario){
        try{
            SugarCompiler.compile(source);
            throw new AssertionError("Expected failure for " + scenario);
        }catch(IllegalArgumentException expected){
            // Expected validation failure.
        }
    }

    private static void check(boolean condition, String message){
        if(!condition) throw new AssertionError(message);
    }

    private static void registerParsers(){
        LAssembler.customParsers.put("forbegin", SugarStatements::parseForBegin);
        LAssembler.customParsers.put("forbeginc", tokens -> SugarStatements.parseForBegin(tokens, true));
        LAssembler.customParsers.put("whilebegin", SugarStatements::parseWhileBegin);
        LAssembler.customParsers.put("whilebeginc", tokens -> SugarStatements.parseWhileBegin(tokens, true));
        LAssembler.customParsers.put("switchbegin", SugarStatements::parseSwitchBegin);
        LAssembler.customParsers.put("switchbeginc", tokens -> SugarStatements.parseSwitchBegin(tokens, true));
        LAssembler.customParsers.put("case", SugarStatements::parseCase);
        LAssembler.customParsers.put("break", tokens -> new BreakStatement());
        LAssembler.customParsers.put("blockend", tokens -> new BlockEndStatement());
        LAssembler.customParsers.put("forend", tokens -> new BlockEndStatement());
        LAssembler.customParsers.put("whileend", tokens -> new BlockEndStatement());
        LAssembler.customParsers.put("switchend", tokens -> new BlockEndStatement());
    }
}
