package logicsugar;

import arc.struct.Seq;
import mindustry.logic.LAssembler;
import mindustry.logic.LStatement;
import mindustry.logic.SugarCompiler;
import mindustry.logic.SugarStatements;
import mindustry.logic.SugarStatements.BlockEndStatement;
import mindustry.logic.SugarStatements.BreakStatement;

public class SugarCompilerSelfTest{
    public static void main(String[] args){
        registerParsers();
        nestedProgramRoundTrips();
        legacyEndsMigrate();
        malformedStructuresFail();
        vanillaCodePassesThrough();
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
        check(lowered.size == 19, "unexpected lowered instruction count: " + lowered.size);
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
