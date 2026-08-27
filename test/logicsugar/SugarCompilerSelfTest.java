package logicsugar;

import arc.struct.Seq;
import mindustry.Vars;
import mindustry.logic.GlobalVars;
import mindustry.logic.LAssembler;
import mindustry.logic.LExecutor;
import mindustry.logic.LStatement;
import mindustry.logic.LVar;
import mindustry.logic.SugarCompiler;
import mindustry.logic.SugarFunctions;
import mindustry.logic.SugarStatements;
import mindustry.logic.SugarStatements.BlockEndStatement;
import mindustry.logic.SugarStatements.BreakStatement;
import mindustry.logic.SugarStatements.ContinueStatement;
import mindustry.logic.SugarStatements.ForBeginStatement;
import mindustry.logic.SugarStatements.FuncCallStatement;
import mindustry.logic.SugarStatements.FuncDefStatement;
import mindustry.logic.SugarStatements.ReturnStatement;
import mindustry.logic.SugarStatements.WhileBeginStatement;
import mindustry.logic.LStatements.JumpStatement;
import logicsugar.assist.expr.ExprCompiler;
import logicsugar.assist.expr.ExprHook;
import logicsugar.assist.expr.ExprStatement;
import mindustry.world.blocks.logic.LogicBlock;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

public class SugarCompilerSelfTest{
    public static void main(String[] args){
        registerParsers();
        nestedProgramRoundTrips();
        legacyEndsMigrate();
        malformedStructuresFail();
        semanticErrorsAreLocated();
        jumpsMayTargetStructureBoundaries();
        breaksLeaveNearestStructure();
        ifElseChain();
        commentTextRoundTrip();
        generatedCodeIsOptimized();
        counterOperationsAreNotOptimized();
        switchStrategyFormulasAndShapes();
        switchTableExecutesSemantics();
        switchRegressionGrid();
        chainOnlyPreservesLegacySwitchOutput();
        jumpThreadCollapsesKnownChains();
        vanillaCodePassesThrough();
        expressionOpsRoundTrip();
        sensorMemberAccess();
        functionCallsInExpressions();
        exprCallEndToEnd();
        returnTempNamespace();
        returnExprRedMark();
        highlightMemberColor();
        normalModeMainJumpsPastBodies();
        structuredTargetsFollowExpressionResize();
        functionStatementsRoundTrip();
        quotedExpressionsRoundTrip();
        truncatedLinesFailCleanly();
        functionStringLiteralsSurviveRewrite();
        storageDevicesStayExempt();
        functionParamBindingInline();
        functionParamBindingNormal();
        functionVoidAndEarlyReturn();
        functionReturnValue();
        functionNestedCalls();
        functionCallBeforeDefinition();
        functionCallInLoop();
        functionCallerTempSurvives();
        functionBodyTempIsNamespaced();
        functionJumpToOwnEndIsExit();
        functionJumpBoundariesRejected();
        functionValidationRejected();
        functionRecursionRejected();
        functionUnreachableCostsNothing();
        functionInstructionLimitHint();
        functionProgramsExecute();
        libraryFunctions();
        libraryValidationRejected();
        functionCallsMarkedInvalid();
        libraryExtractionIsSelfContained();
        carrierSurvivesVanillaRoundTrip();
        restorePrefersCarrier();
        libraryEmbeddingRoundTrip();
        verificationDetectsExternalEdits();
        oversizeStripsComments();
        libraryDamageHarness();
        librarySalvageRegressions();
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
        check(lowered.size == 16, "unexpected lowered instruction count: " + lowered.size + " (15 lowered + 1 persistence carrier)");
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

        // return outside a function: the compile fails and the editor must mark it red too
        Seq<LStatement> returns = LAssembler.read("return \"\"\n", true);
        boolean[] returnInvalid = SugarCompiler.invalidStatements(returns);
        check(returnInvalid[0], "bare return was not marked invalid");

        // a return inside a loop but still outside any function is equally invalid
        Seq<LStatement> loopReturn = LAssembler.read("forbegin i 0 1 lessThanEq 3 2\nreturn \"\"\nblockend\n", true);
        boolean[] loopReturnInvalid = SugarCompiler.invalidStatements(loopReturn);
        check(loopReturnInvalid[1], "return inside a loop but outside a function was not marked invalid");

        // return inside a function body is legal: nothing is marked and compilation succeeds
        Seq<LStatement> funcReturn = LAssembler.read("funcdef f ~ 2\nreturn \"\"\nblockend\n", true);
        boolean[] funcReturnInvalid = SugarCompiler.invalidStatements(funcReturn);
        check(!funcReturnInvalid[1], "return inside a function was marked invalid");
        SugarCompiler.compile("funcdef f ~ 2\nreturn \"\"\nblockend\n");
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

    private static void ifElseChain(){
        // if a > 5 { x=1 } elif b <= 3 { x=2 } else { x=3 }
        String chain = """
            ifbegin a greaterThan 5 6
            set x 1
            elif b lessThanEq 3
            set x 2
            else
            set x 3
            blockend
            """;
        String compiled = loweredCode(SugarCompiler.compile(chain));
        check(compiled.contains("jump __ls_if_branch_2 lessThanEq a 5"), "if false-branch jump is wrong");
        check(compiled.contains("jump __ls_if_branch_4 greaterThan b 3"), "elif false-branch jump is wrong");
        check(compiled.contains("__ls_if_branch_2:") && compiled.contains("__ls_if_branch_4:"), "if/elif branch labels missing");
        check(compiled.contains("set x 1") && compiled.contains("set x 2") && compiled.contains("set x 3"), "if/elif/else bodies missing");

        // simple if with no elif/else
        String simple = loweredCode(SugarCompiler.compile("ifbegin a equal 0 2\nset x 1\nblockend\n"));
        check(simple.contains("jump __ls_stmt_3 notEqual a 0"), "simple if negated jump is wrong");
        check(!simple.contains("__ls_if_branch_"), "simple if has unexpected branch labels");

        // legacy single-value while still maps to "!= false"
        String legacy = loweredCode(SugarCompiler.compile("whilebegin a 2\nset y 1\nblockend\n"));
        check(legacy.contains("jump __ls_while_body_0 notEqual a false"), "legacy while condition not mapped");

        // elif/else outside an if are rejected
        expectFailure("elif a equal 0\n", "elif outside an if");
        expectFailure("else\n", "else outside an if");

        // strictEqual has no strict-not-equal op, so negation falls back to notEqual
        String strict = loweredCode(SugarCompiler.compile("ifbegin a strictEqual b 2\nset x 1\nblockend\n"));
        check(strict.contains("jump __ls_stmt_3 notEqual a b"), "strictEqual did not negate to notEqual");

        // an if chain may have at most one else, and no elif may follow it (compile path)
        expectFailure("ifbegin a equal 0 5\nelse\nset x 1\nelif b equal 0\nset x 2\nblockend\n", "elif after else");
        expectFailure("ifbegin a equal 0 5\nelse\nset x 1\nelse\nset x 2\nblockend\n", "duplicate else");

        // nested if: the else binds to the inner if (its nearest still-open if), not the outer
        String nested = loweredCode(SugarCompiler.compile(
            "ifbegin a equal 0 7\nset x 1\nifbegin b equal 0 6\nset y 1\nelse\nset y 2\nblockend\nblockend\n"));
        check(nested.contains("jump __ls_if_branch_4 notEqual b 0"), "inner if else jump is wrong");
        check(nested.contains("jump __ls_stmt_8 notEqual a 0"), "outer if exit jump is wrong");
    }

    /** The block <-> print-text toggle must round-trip losslessly, including underscores. */
    private static void commentTextRoundTrip(){
        check(SugarStatements.decodeStatementText(SugarStatements.encodeStatementText("set my_var 1")).equals("set my_var 1"),
            "underscore identifier was not preserved by the print-text encoding");
        check(SugarStatements.decodeStatementText(SugarStatements.encodeStatementText("set x \"hello world\"")).equals("set x \"hello world\""),
            "quoted string was not preserved by the print-text encoding");
        check(SugarStatements.decodeStatementText(SugarStatements.encodeStatementText("funccall f \"a, b\" ~")).equals("funccall f \"a, b\" ~"),
            "funccall with tilde and spaces was not preserved");
        check(SugarStatements.decodeStatementText(SugarStatements.encodeStatementText("ifbegin my_var greaterThan 5 3")).equals("ifbegin my_var greaterThan 5 3"),
            "three-part condition with underscore was not preserved");
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

        String switchCodeAuto = loweredCode(SugarCompiler.compile("""
            switchbegin x 3
            case 1
            print one
            blockend
            """));
        String switchCodeChain = loweredCode(SugarCompiler.compile("""
            switchbegin x 3
            case 1
            print one
            blockend
            """, SugarCompiler.FuncMode.normal, null, null, SugarCompiler.SwitchStrategy.chainOnly));
        // tiny dense switches stay on the comparison chain under every strategy
        check(switchCodeAuto.equals(switchCodeChain), "strategy changed the output of a chain-eligible switch");
        check(switchCodeChain.contains("jump __ls_case_1 equal x 1"), "switch did not compare its source value directly");
        check(!switchCodeChain.contains("__ls_sw_"), "switch temporary variable was emitted");
    }

    /** Explicit @counter use is observable control flow and must not enter op optimization. */
    private static void counterOperationsAreNotOptimized(){
        String lowered = loweredCode(SugarCompiler.compile("""
            whilebegin true 5
            op add @counter 1 2
            op add x @counter 1
            op add _0 4 5
            op add y _0 1
            blockend
            """));
        check(lowered.contains("op add @counter 1 2"), "@counter write was folded or rewritten");
        check(lowered.contains("op add x @counter 1"), "@counter read was folded or rewritten");
        check(!lowered.contains("set @counter 3"), "constant folding changed an explicit @counter operation");
        check(lowered.contains("set y 10"), "ordinary ops after the @counter barrier were not optimized");
    }

    // ===== switch jump tables =============================================================

    /** Builds a switch with the same pair of values declared many times. destIndex (and the
     *  default label stmt_<n+1>) is derived from the real blockend position, and one trailing
     *  `end` keeps the tail stable against threading. */
    private static StringBuilder dupSwitch(int valueA, int valueB, int repeats, String aBody, String bBody, String tail){
        StringBuilder body = new StringBuilder();
        for(int i = 0; i < repeats; i++){
            body.append("case ").append(valueA).append('\n').append(aBody).append('\n');
            body.append("case ").append(valueB).append('\n').append(bBody).append('\n');
        }
        if(tail != null && !tail.isEmpty()) body.append(tail).append('\n');
        int dest = 1 + body.toString().split("\n", -1).length - 1; // switchbegin + inner lines
        StringBuilder s = new StringBuilder("switchbegin x ").append(dest).append('\n');
        s.append(body);
        s.append("blockend\nend\n");
        return s;
    }

    private static int instructionLines(String lowered){
        int count = 0;
        for(String line : lowered.split("\n", -1)){
            String bare = line.trim();
            if(bare.isEmpty() || bare.endsWith(":")) continue;
            count++;
        }
        return count;
    }

    /** auto picks a jump table exactly when the cost model says so: N+1 chain vs sub?+2 guards
     *  +dispatch+span rows. Repeated values dedupe into slots, duplication-heavy switches win. */
    private static void switchStrategyFormulasAndShapes(){
        // span 2, min 0, 16 case statements: chain=17 vs table=0(sub)+2+1+2=5 -> table
        String dup = dupSwitch(0, 1, 8, "print zero", "print one", null).toString();
        String autoLowered = loweredCode(SugarCompiler.compile(dup));
        String chainLowered = loweredCode(SugarCompiler.compile(dup,
            SugarCompiler.FuncMode.normal, null, null, SugarCompiler.SwitchStrategy.chainOnly));

        // Bang-style executable-cost assertions on both shapes
        check(instructionLines(autoLowered) == instructionLines(chainLowered) - 12,
            "table saving must equal (chain cost 17 - table cost 5): "
                + instructionLines(autoLowered) + " vs " + instructionLines(chainLowered));

        // table shape: two bounds guards onto the default label, one dispatch, span slot rows
        check(autoLowered.contains("jump __ls_stmt_34 lessThan x 0"), "lower guard missing:\n" + autoLowered);
        check(autoLowered.contains("jump __ls_stmt_34 greaterThan x 1"), "upper guard missing");
        check(autoLowered.contains("op add @counter @counter x"), "dispatch missing");
        long rowsAuto = autoLowered.lines().filter(l -> l.equals("jump __ls_case_1 always x false")
            || l.equals("jump __ls_case_3 always x false")).count();
        check(rowsAuto == 2, "expected exactly span slot rows, got " + rowsAuto);
        check(!autoLowered.contains("equal x "), "table form must not compare the source value");

        // chain shape: one comparison per declared case plus the default jump (old output)
        check(chainLowered.contains("jump __ls_case_1 equal x 0") && chainLowered.contains("jump __ls_case_3 equal x 1"),
            "chain comparisons missing");
        check(chainLowered.contains("jump __ls_stmt_34 always x false"), "default jump missing");
        check(chainLowered.lines().filter(l -> l.startsWith("jump __ls_case_")).filter(l -> l.contains(" equal x ")).count() == 16,
            "expected one comparison per declared case");
        check(!chainLowered.contains("__ls_sw_") && !chainLowered.contains("@counter @counter"),
            "chain form leaked table artifacts");

        // span > MAX_TABLE_SPAN falls back to the chain even when duplication would win
        String wide = dupSwitch(0, 300, 8, "print a", "print b", null).toString();
        String wideAuto = loweredCode(SugarCompiler.compile(wide));
        check(wideAuto.contains("equal x 0") && wideAuto.contains("equal x 300"), "wide-range switch ignored span cap");
        check(!wideAuto.contains("@counter @counter"), "span-capped switch emitted a table");

        // non-integer case values are ineligible by definition (tables assume numeric ids)
        StringBuilder f = new StringBuilder("switchbegin x 9\n");
        for(int i = 0; i < 4; i++) f.append("case 0.5\nprint a\n");
        f.append("blockend\nend\n");
        String fracAuto = loweredCode(SugarCompiler.compile(f.toString()));
        check(fracAuto.contains("equal x 0.5"), "fractional case value did not keep the chain");
        check(!fracAuto.contains("@counter @counter"), "fractional case value emitted a table");

        // negative minimum normalizes through an explicit op sub into the index variable
        StringBuilder neg = dupSwitch(-2, 3, 6, "set y 20", "set y 30", "set y 99");
        String negAuto = loweredCode(SugarCompiler.compile(neg.toString()));
        check(negAuto.contains("op sub __ls_sw_0 x -2"), "negative-minimum table missed its op sub normalization:\n" + negAuto);
        check(negAuto.contains("jump __ls_stmt_27 lessThan __ls_sw_0 0"), "normalized lower guard wrong");
        check(negAuto.contains("jump __ls_stmt_27 greaterThan __ls_sw_0 5"), "normalized upper guard wrong");
        check(!negAuto.contains("lessThan x 0"), "guards compared the un-normalized source value");
    }

    /** Compiled tables behave exactly like chains at runtime: slots run their first-declared
     *  body (duplicate values follow first-match), holes and out-of-range probes take default. */
    private static void switchTableExecutesSemantics(){
        Vars.logicVars = new GlobalVars();
        Vars.logicVars.putEntry("false", 0);
        Vars.logicVars.putEntry("true", 1);

        // two leading lines shift every statement; recompute the destIndex token accordingly
        String head0 = dupSwitch(-2, 3, 6, "set y 20\nbreak", "set y 30\nbreak", null).toString();
        int dest = Integer.parseInt(head0.split("\n")[0].split(" ")[2]);
        String sugarHead = head0.replaceFirst("^switchbegin x \\d+", "switchbegin x " + (dest + 2));
        double[] probes = {-9, -3, -2, -1, 0, 1, 2, 3, 4, 99};
        for(double probe : probes){
            double expected = probe == -2 ? 20 : probe == 3 ? 30 : 0;
            String program = "set x " + formatProbe(probe) + "\nset y 0\n" + sugarHead;
            check(execute(program, SugarCompiler.FuncMode.normal, "y") == expected,
                "probe " + probe + ": expected y=" + expected
                    + "\n" + loweredCode(SugarCompiler.compile(program)));
        }
    }

    private static String formatProbe(double probe){
        return probe == Math.rint(probe) ? Long.toString((long)probe) : Double.toString(probe);
    }

    /** One function-level switch exercising fall-through, break, first-match duplicates and
     *  a nested dense switch; executed across the whole FuncMode x strategy grid. */
    private static void switchRegressionGrid(){
        Vars.logicVars = new GlobalVars();
        Vars.logicVars.putEntry("false", 0);
        Vars.logicVars.putEntry("true", 1);

        // Assembled with counted positions so funcdef/switchbegin destIndex tokens stay valid
        // no matter how the bodies evolve.
        java.util.List<String> lines = new java.util.ArrayList<>();
        lines.add("funcdef grade p,q PLACEFUNC");
        int switchHeaderAt = lines.size();
        lines.add("switchbegin p PLACESW");
        lines.add("case 1");
        lines.add("switchbegin q PLACEQ");
        lines.add("case 1");
        lines.add("set inner 11");
        lines.add("break");
        lines.add("case 2");
        lines.add("set inner 22");
        lines.add("break");
        int innerBlockend = lines.size();
        lines.add("blockend");
        lines.add("op add out q 10");       // outer case 1 intentionally falls through
        lines.add("case 2");
        lines.add("op add out out 100");    // only reached directly or via the fall-through
        lines.add("break");
        int dupPairs = 8;
        for(int i = 0; i < dupPairs; i++){
            lines.add("case 1");
            lines.add("set dead 7777");     // duplicated values must follow first match only
            lines.add("case 2");
            lines.add("set dead 7777");
        }
        int blockendAt = lines.size();
        lines.add("blockend");              // closes the outer switch
        int funcEndAt = lines.size();
        lines.add("blockend");              // closes the function definition itself
        // the run seeds five variables ahead of the program text, shifting every statement;
        // bump the three absolute destIndex tokens so validatePairs sees the real blockends
        int shift = 5;
        lines.set(0, lines.get(0).replace("PLACEFUNC", Integer.toString(funcEndAt + shift)));
        lines.set(switchHeaderAt, lines.get(switchHeaderAt).replace("PLACESW", Integer.toString(blockendAt + shift)));
        lines.set(switchHeaderAt + 2, lines.get(switchHeaderAt + 2).replace("PLACEQ", Integer.toString(innerBlockend + shift)));

        StringBuilder program = new StringBuilder();
        for(String line : lines) program.append(line).append('\n');
        program.append("funccall grade \"p, q\" ~\n").append("end\n");

        double[][] probes = {
            {1, 1, 111.0, 11.0},   // nested hit; fall-through adds twice (q+10, then +100)
            {1, 5, 115.0, -3.0},   // nested miss still falls through with its add
            {2, 9, 93.0, -3.0},    // direct case 2 skips the fall-through add entirely
            {9, 5, -7.0, -3.0},    // default: header hops past the whole block
        };
        for(SugarCompiler.FuncMode mode : SugarCompiler.FuncMode.values()){
            for(SugarCompiler.SwitchStrategy strategy : SugarCompiler.SwitchStrategy.values()){
                for(double[] probe : probes){
                    String seed = "set p " + formatProbe(probe[0]) + "\nset q " + formatProbe(probe[1])
                        + "\nset out -7\nset inner -3\nset dead 0\n" + program;
                    String code = SugarCompiler.compile(seed, mode, null, null, strategy);
                    String lowered = loweredCode(code);
                    check(instructionLines(lowered) > 4, mode + "/" + strategy + ": program collapsed");
                    double out = executeCode(code, "out");
                    check(out == probe[2], mode + "/" + strategy + ": p=" + probe[0]
                        + " expected out=" + probe[2] + " but got " + out + "\n" + lowered);
                    double nestedVal = executeCode(code, "inner");
                    check(nestedVal == probe[3], mode + "/" + strategy + ": p=" + probe[0]
                        + " expected inner=" + probe[3] + " but got " + nestedVal);
                    double dead = executeCode(code, "dead");
                    check(dead == 0.0, mode + "/" + strategy + ": duplicate case body executed (first-match violated)");
                }
            }
        }
    }

    /** chainOnly reproduces the pre-jump-table output byte-for-byte, including programs the
     *  auto strategy lowers as tables. */
    private static void chainOnlyPreservesLegacySwitchOutput(){
        String dup = dupSwitch(0, 1, 8, "print zero", "print one", null).toString();
        String auto = SugarCompiler.compile(dup);
        String chainOnly = SugarCompiler.compile(dup,
            SugarCompiler.FuncMode.normal, null, null, SugarCompiler.SwitchStrategy.chainOnly);
        check(auto.contains("op add @counter @counter x"), "auto did not pick the table for a dup-heavy switch");
        check(!chainOnly.contains("@counter @counter") && !chainOnly.contains("__ls_sw_"),
            "chainOnly leaked table instructions:\n" + chainOnly);
        // legacy shape: comparisons plus one unconditional default jump, nothing else
        check(chainOnly.contains("jump __ls_case_1 equal x 0"), "chainOnly lost the comparison header");
        check(chainOnly.contains("jump __ls_stmt_34 always x false"), "chainOnly lost the default jump");
        check(!chainOnly.contains("lessThan x 0") && !chainOnly.contains("greaterThan x "),
            "chainOnly emitted table guards");

        // negative-minimum duplication-heavy switches also stay verbatim under chainOnly
        String neg = dupSwitch(-2, 3, 6, "set y 20", "set y 30", "set y 99").toString();
        String negAuto = loweredCode(SugarCompiler.compile(neg));
        String negChain = loweredCode(SugarCompiler.compile(neg,
            SugarCompiler.FuncMode.normal, null, null, SugarCompiler.SwitchStrategy.chainOnly));
        check(negAuto.contains("op sub __ls_sw_0 x -2"), "negative minimum did not build a normalized table:\n" + negAuto);
        check(negAuto.contains("jump __ls_stmt_27 greaterThan __ls_sw_0 5"), "normalized upper guard wrong");
        check(negChain.contains("jump __ls_case_1 equal x -2") && negChain.contains("jump __ls_case_3 equal x 3"),
            "chainOnly negative case lost comparisons");
        check(!negChain.contains("__ls_sw_") && !negChain.contains("@counter @counter"),
            "chainOnly leaked table artifacts:\n" + negChain);
    }

    /** The go-threading pass retargets jumps whose label sits directly above another always
     *  jump; stacked switch defaults collapse into it and semantics stay identical. */
    private static void jumpThreadCollapsesKnownChains(){
        Vars.logicVars = new GlobalVars();
        Vars.logicVars.putEntry("false", 0);
        Vars.logicVars.putEntry("true", 1);
        // statements: funcdef@0..2, funccall@3, switchbegin@4 (dest 8), case@5, set@6,
        // break@7, blockend@8 -> default/break label stmt_9. In normal mode the hoisted-body
        // prelude appends "jump __ls_end" directly under that label, so break/default rows
        // and the default jump thread straight into it; inline has no hoist section.
        String sugar = """
            funcdef f ~ 2
            set flagK 7
            blockend
            funccall f "" ~
            switchbegin x 8
            case 3
            set y 1
            break
            blockend
            """;

        for(SugarCompiler.FuncMode mode : SugarCompiler.FuncMode.values()){
            for(SugarCompiler.SwitchStrategy strategy : SugarCompiler.SwitchStrategy.values()){
                String lowered = loweredCode(SugarCompiler.compile(sugar, mode, null, null, strategy));
                long endJumps = lowered.lines().filter(l -> l.equals("jump __ls_end always x false")).count();
                if(mode == SugarCompiler.FuncMode.normal){
                    check(!lowered.contains("jump __ls_stmt_9 always x false"),
                        mode + "/" + strategy + ": pre-threading default jump survived\n" + lowered);
                    check(endJumps >= 2, mode + "/" + strategy + ": stacked default was not threaded ("
                        + endJumps + " end jumps)\n" + lowered);
                }else{
                    check(endJumps == 0, mode + ": bodyless program must not emit the end jump");
                }

                // a one-line probe prefix shifts every statement: retokenize both headers
                int swDest = Integer.parseInt(sugar.split("\n")[4].split(" ")[2]);
                String probeSugar = "set x V\n" + sugar
                    .replaceFirst("funcdef f ~ \\d+", "funcdef f ~ 3")
                    .replaceFirst("switchbegin x \\d+", "switchbegin x " + (swDest + 1));
                check(execute(probeSugar.replace("set x V", "set x 3"), mode, "y") == 1.0,
                    mode + "/" + strategy + ": threaded switch broke matching");
                check(execute(probeSugar.replace("set x V", "set x 3"), mode, "flagK") == 7.0,
                    mode + "/" + strategy + ": threading broke the function body");
                check(execute(probeSugar.replace("set x V", "set x 99"), mode, "y") == 0.0,
                    mode + "/" + strategy + ": default path executed a case body");
            }
        }

        // idempotence: threading an already-threaded program changes nothing
        String once = SugarCompiler.threadAlwaysJumpTargets(loweredCode(SugarCompiler.compile(sugar)));
        String twice = SugarCompiler.threadAlwaysJumpTargets(once);
        check(once.equals(twice), "threading is not idempotent");

        // hand-built graph: unconditional chains collapse; conditional jumps stay put;
        // self-targeting chains keep their original loop semantics
        String manual = """
            set a 1
            jump mid always x false
            jump out lessThan a 5
            mid:
            jump fin always x false
            out:
            set b 2
            fin:
            set c 3
            """;
        String threadedManual = SugarCompiler.threadAlwaysJumpTargets(manual);
        check(threadedManual.contains("jump fin always x false\njump out lessThan a 5"),
            "conditional jump was rewritten or ordering changed:\n" + threadedManual);
        check(!threadedManual.contains("jump mid always"), "mid->fin chain not collapsed:\n" + threadedManual);
        check(threadedManual.contains("mid:") && threadedManual.contains("out:"),
            "label lines must survive threading verbatim:\n" + threadedManual);

        String selfLoop = "loop:\nset i 1\njump loop always x false\n";
        check(SugarCompiler.threadAlwaysJumpTargets(selfLoop).equals(selfLoop),
            "self-looping chain was altered:\n" + SugarCompiler.threadAlwaysJumpTargets(selfLoop));

        String mutualA = "a:\nb:\njump z always x false\nz:\nset k 1\n";
        check(SugarCompiler.threadAlwaysJumpTargets(mutualA).contains("z"),
            "degenerate label stack handled unsafely:\n" + SugarCompiler.threadAlwaysJumpTargets(mutualA));
    }

    private static String loweredCode(String compiled){
        int marker = compiled.indexOf("# @logic-sugar-v1 begin");
        return marker < 0 ? compiled : compiled.substring(0, marker);
    }

    private static void expressionOpsRoundTrip(){
        List<ExprCompiler.Line> ops = ExprCompiler.compile("result", "cos(a) * 10 + x");
        check(opText(ops).equals("op cos _0 a 0\nop mul _0 _0 10\nop add result _0 x"),
            "expression compiler emitted unexpected op chain");

        String restored = ExprCompiler.rebuild(ops);
        check(restored != null, "expression compiler did not restore an op chain");
        check(opText(ExprCompiler.compile("result", restored)).equals(opText(ops)),
            "restored expression changed the generated op chain");
    }

    private static String opText(List<ExprCompiler.Line> ops){
        StringBuilder result = new StringBuilder();
        for(int i = 0; i < ops.size(); i++){
            if(i > 0) result.append('\n');
            result.append(ops.get(i).toText());
        }
        return result.toString();
    }

    private static void sensorMemberAccess(){
        // 单属性：unit.Health → sensor result unit @health（大小写不敏感）
        List<ExprCompiler.Line> ops = ExprCompiler.compile("x", "unit.Health");
        check(opText(ops).equals("sensor x unit @health"),
            "member access did not compile to a sensor instruction: " + opText(ops));

        // 链式混合：@unit.Health * 2 → sensor _0 @unit @health + op mul x _0 2
        ops = ExprCompiler.compile("x", "@unit.Health * 2");
        check(opText(ops).equals("sensor _0 @unit @health\nop mul x _0 2"),
            "member access inside an expression chain failed: " + opText(ops));

        // 逆向重建：sensor 链 → unit.health * 2，往返编译一致
        String restored = ExprCompiler.rebuild(ops);
        check(restored != null, "member access chain did not rebuild");
        check(opText(ExprCompiler.compile("x", restored)).equals(opText(ops)),
            "restored member expression changed the generated chain");

        // 链式多级：unit.controller.Health → 两条 sensor
        ops = ExprCompiler.compile("x", "unit.controller.Health");
        check(opText(ops).equals("sensor _0 unit @controller\nsensor x _0 @health"),
            "chained member access failed: " + opText(ops));

        // 大写 camelCase：unit.maxHealth
        ops = ExprCompiler.compile("x", "unit.MaxHealth");
        check(opText(ops).equals("sensor x unit @maxHealth"),
            "camelCase member name failed: " + opText(ops));

        // 未知属性报错
        boolean thrown = false;
        try{
            ExprCompiler.compile("x", "unit.Amor");
        }catch(ExprCompiler.ParseException e){
            thrown = true;
        }
        check(thrown, "unknown member name did not throw");

        // 点后缺成员名报错
        thrown = false;
        try{
            ExprCompiler.compile("x", "unit.");
        }catch(ExprCompiler.ParseException e){
            thrown = true;
        }
        check(thrown, "missing member name after '.' did not throw");
    }

    private static void functionCallsInExpressions(){
        // 单调用：x = foo(a) → funccall foo "a" x
        List<ExprCompiler.Line> ops = ExprCompiler.compile("x", "foo(a)");
        check(opText(ops).equals("funccall foo \"a\" x"),
            "single function call failed: " + opText(ops));

        // 调用参与运算：x = foo(a) * 2 → funccall foo "a" _0 + op mul x _0 2
        ops = ExprCompiler.compile("x", "foo(a) * 2");
        check(opText(ops).equals("funccall foo \"a\" _0\nop mul x _0 2"),
            "function call in expression chain failed: " + opText(ops));

        // 多参数 + 嵌套：foo(cos(a), b) → 实参求值 + 调用
        ops = ExprCompiler.compile("x", "foo(cos(a), b)");
        check(opText(ops).equals("op cos _0 a 0\nfunccall foo \"_0, b\" x"),
            "call with computed argument failed: " + opText(ops));

        // 逆向重建：funccall 链 → foo(a) * 2，往返一致
        ops = ExprCompiler.compile("x", "foo(a) * 2");
        String restored = ExprCompiler.rebuild(ops);
        check(restored != null, "function call chain did not rebuild");
        check(opText(ExprCompiler.compile("x", restored)).equals(opText(ops)),
            "restored function call expression changed the generated chain");

        // collectCalls：收集文本中的用户函数（数学函数 cos 排除）
        java.util.List<ExprCompiler.CallSite> sites = ExprCompiler.collectCalls("foo(cos(a), bar(b)) > 5");
        check(sites.size() == 2 && sites.get(0).name.equals("foo") && sites.get(1).name.equals("bar"),
            "collectCalls did not collect nested user function calls: " + sites);

        // checker 校验：未知函数名在编辑期报错
        boolean thrown = false;
        try{
            ExprCompiler.compile("x", "nope(a)", name -> name.equals("foo"));
        }catch(ExprCompiler.ParseException e){
            thrown = true;
        }
        check(thrown, "unknown function with checker did not throw");
        // checker 通过的函数不报错
        thrown = false;
        try{
            ExprCompiler.compile("x", "foo(a)", name -> name.equals("foo"));
        }catch(ExprCompiler.ParseException e){
            thrown = true;
        }
        check(!thrown, "known function with checker threw");

        // 负数字面量实参往返：foo(-5) → op sub + funccall 链 → rebuild 回 foo(-5)
        ops = ExprCompiler.compile("x", "foo(-5)");
        String negRestored = ExprCompiler.rebuild(ops);
        check("foo(-5)".equals(negRestored), "negative literal arg did not round-trip: " + negRestored);
        // 再编译一次语义等价（-5 求值为 op sub，funccall 实参为 temp）
        check(opText(ExprCompiler.compile("x", negRestored)).equals(opText(ops)),
            "restored negative-literal call changed the generated chain");
    }

    private static void exprCallEndToEnd(){
        // 画布 unfold 后的 sugar：funcdef foo + x = foo(cos(a)) * 2 展开的语句链
        String sugar = "funcdef foo x 2\n"
            + "return \"x * 2\"\n"
            + "blockend\n"
            + "op cos _0 a 0\n"
            + "funccall foo \"_0\" _1\n"
            + "op mul x _1 2\n";
        String compiled = SugarCompiler.compile(sugar);
        check(compiled.contains("op cos _0 a 0"), "argument evaluation op was optimized away by the temp ref count fix");
        check(compiled.contains("jump __ls_func_foo_entry always x false"), "expr call did not emit normal-mode call sequence");
        check(compiled.contains("op mul __ls_func_foo_result x 2"), "function body was not hoisted (reachability)");
        check(compiled.contains("set _1 __ls_func_foo_result"), "call result was not bound into the expression chain");

        // 隐式递归：return foo(x) 里的调用也要进调用图，否则展开时无限循环
        String recursive = "funcdef foo x 2\n"
            + "return \"foo(x)\"\n"
            + "blockend\n"
            + "funccall foo \"x\" r\n";
        boolean thrown = false;
        try{
            SugarCompiler.compile(recursive);
        }catch(IllegalArgumentException e){
            thrown = e.getMessage().contains("recursion");
        }
        check(thrown, "implicit recursion via return expression was not detected");

        // 条件表达式里的调用：if foo(a) > 5
        String condSugar = "funcdef foo x 2\n"
            + "return \"x + 1\"\n"
            + "blockend\n"
            + "ifbegin expr \"foo(a) > 5\" 5\n"
            + "print \"hi\"\n"
            + "blockend\n";
        String condCompiled = SugarCompiler.compile(condSugar);
        check(condCompiled.contains("jump __ls_func_foo_entry always x false"), "condition expression call was not expanded");
        check(condCompiled.contains("op greaterThan __ls_cond_0 __ls_cond_0 5"), "condition result was not chained after the call");
    }

    private static void highlightMemberColor(){
        // 成员访问高亮：unit.Health 的 Health 应为天蓝成员色；数字/函数配色保持
        String h = ExprStatement.highlight("unit.Health");
        check(h.contains("[sky]Health[]"), "member name not highlighted in sky: " + h);
        check(h.contains("[white]unit[]"), "base variable lost white color: " + h);
        String chain = ExprStatement.highlight("unit.controller.maxHealth");
        check(chain.contains("[sky]controller[]") && chain.contains("[sky]maxHealth[]"),
            "chained members not highlighted: " + chain);
        String mixed = ExprStatement.highlight("cos(a).Health * 2.5");
        check(mixed.contains("[coral]cos[]") && mixed.contains("[sky]Health[]") && mixed.contains("[goldenrod]2.5[]"),
            "mixed highlight wrong: " + mixed);
        String broken = ExprStatement.highlight("unit.");
        check(!broken.contains("[sky]"), "dangling dot should not highlight anything: " + broken);
    }

    private static void returnExprRedMark(){
        // 回归：return 表达式 / funccall 实参编译报错但编辑器不标红。
        // a1.1 = 变量 a1 后接数字成员（非法），编译期抛错，编辑期 invalidStatements 必须标红。
        boolean thrown = false;
        try{
            ExprCompiler.compile("r", "a1.1");
        }catch(ExprCompiler.ParseException e){
            thrown = true;
        }
        check(thrown, "a1.1 should fail to compile");

        String sugar = "funcdef f x 2\nreturn \"a1.1\"\nblockend\nfunccall f \"1\" r\n";
        boolean[] invalid = SugarCompiler.invalidStatements(LAssembler.read(sugar, true));
        check(invalid[1], "return expression a1.1 is not marked invalid at statement 1");

        String ok = "funcdef f x 2\nreturn \"x + 1.5\"\nblockend\nfunccall f \"1\" r\n";
        boolean[] invalidOk = SugarCompiler.invalidStatements(LAssembler.read(ok, true));
        check(!invalidOk[1], "valid return expression is marked invalid");

        String callSugar = "funcdef f x 2\nreturn \"x\"\nblockend\nfunccall f \"a1.1\" r\n";
        boolean[] invalidCall = SugarCompiler.invalidStatements(LAssembler.read(callSugar, true));
        check(invalidCall[3], "funccall argument a1.1 is not marked invalid at statement 3");

        String callOk = "funcdef f x 2\nreturn \"x\"\nblockend\nfunccall f \"1.5, x\" r\n";
        boolean[] invalidCallOk = SugarCompiler.invalidStatements(LAssembler.read(callOk, true));
        check(!invalidCallOk[3], "valid funccall arguments are marked invalid");

        // 嵌套多参实参（max(1, 2)）不能被朴素逗号切分误伤：括号感知的 splitArgs 下应整体合法
        String callNested = "funcdef f x 2\nreturn \"x\"\nblockend\nfunccall f \"max(1, 2)\" r\n";
        boolean[] invalidNested = SugarCompiler.invalidStatements(LAssembler.read(callNested, true));
        check(!invalidNested[3], "nested multi-arg call max(1, 2) is marked invalid: " + java.util.Arrays.toString(invalidNested));
    }

    private static void returnTempNamespace(){
        // 上游 bug 回归：函数体 return 表达式的 temp 必须进入函数命名空间。
        // 裸 _0 会与调用者表达式链中"跨调用存活"的 _0 冲突（函数体覆盖链 temp → 错值）。
        List<ExprCompiler.Line> ops = ExprCompiler.compile("y", "cos(5) + foo(1) + cos(2)");
        StringBuilder sugar = new StringBuilder("funcdef foo a 2\nreturn \"a*2+1\"\nblockend\n");
        for(ExprCompiler.Line line : ops) sugar.append(line.toText()).append('\n');
        String compiled = SugarCompiler.compile(sugar.toString());
        String lowered = loweredCode(compiled);
        // 函数体 temp 必须是命名空间（__ls_rt_foo_0），不能是裸 _0
        check(lowered.contains("op mul __ls_rt_foo_0 a 2"),
            "return expression temp was not namespaced\n" + lowered);
        check(!lowered.contains("op mul _0 a 2"),
            "return expression leaked a bare _0 temp\n" + lowered);
        // 调用者链的 _0（cos(5) 结果）跨 funccall 存活，且函数体不再覆盖它
        check(lowered.contains("op cos _0 5 0") && lowered.contains("op add y _0 _2"),
            "caller chain temp was clobbered across the call\n" + lowered);
    }

    private static void normalModeMainJumpsPastBodies(){
        // 上游 bug：normal 模式函数体紧跟 main 程序之后，调用返回后（set result 执行完）
        // 指令流顺序落进共享函数体，导致函数体每帧重复执行、调用者结果变量无限递增。
        String sugar = "funcdef func x,y,z 2\nreturn \"x+y+z\"\nblockend\nfunccall func \"1, 2, 3\" x\n";
        String lowered = loweredCode(SugarCompiler.compile(sugar));
        int jumpAt = lowered.indexOf("jump __ls_end always x false");
        int entryAt = lowered.indexOf("__ls_func_func_entry:");
        int endAt = lowered.indexOf("__ls_end:");
        check(jumpAt >= 0 && entryAt > jumpAt && endAt > entryAt,
            "normal mode main must jump past function bodies (jump=" + jumpAt + " entry=" + entryAt + " end=" + endAt + "):\n" + lowered);
        // 无函数调用时不引入多余的跳转
        String plain = loweredCode(SugarCompiler.compile("set a 1\n"));
        check(!plain.contains("__ls_end"), "functionless program should not emit the __ls_end jump");
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

    private static void functionStatementsRoundTrip(){
        String sugar = """
            funcdef f a,b 3
            set x 1
            blockend
            funccall f "a + 1, b*2" out
            return "x + 1"
            return ""
            """;

        Seq<LStatement> parsed = LAssembler.read(sugar, true);
        check(parsed.size == 6, "unexpected statement count: " + parsed.size);
        FuncDefStatement def = (FuncDefStatement)parsed.get(0);
        check(def.name.equals("f") && def.params.equals("a,b") && def.destIndex == 3, "funcdef fields lost in round-trip");
        FuncCallStatement call = (FuncCallStatement)parsed.get(3);
        check(call.name.equals("f") && call.args.equals("a + 1, b*2") && call.result.equals("out"), "funccall fields lost in round-trip");
        ReturnStatement valueReturn = (ReturnStatement)parsed.get(4);
        check(valueReturn.expr.equals("x + 1"), "return value lost in round-trip");
        check(((ReturnStatement)parsed.get(5)).expr.isEmpty(), "void return lost in round-trip");
        check(LAssembler.write(parsed).equals(sugar), "function statements did not round-trip verbatim");

        // Empty params / no result / void return serialize with the optional markers.
        String sparse = LAssembler.write(LAssembler.read("funcdef g ~ 1\nblockend\nfunccall g \"\" ~\nreturn \"\"\n", true));
        check(sparse.contains("funcdef g ~ 1") && sparse.contains("funccall g \"\" ~") && sparse.contains("return \"\"\n"), "optional fields did not round-trip");
    }

    private static void functionParamBindingInline(){
        String compiled = loweredCode(SugarCompiler.compile("""
            funcdef f a 2
            print a
            blockend
            funccall f "5" ~
            """, SugarCompiler.FuncMode.inline));
        check(compiled.contains("set a 5"), "inline call did not bind the argument");
        check(compiled.contains("\nprint a\n"), "inline call did not copy the body");

        // argument expressions compile to caller-side temp chains before binding
        String expr = loweredCode(SugarCompiler.compile("""
            funcdef f a 2
            print a
            blockend
            funccall f "cos(x) * 2" ~
            """, SugarCompiler.FuncMode.inline));
        check(expr.contains("op cos _0 x 0") && expr.contains("set a _0"), "argument expression was not compiled and bound");
    }

    private static void functionParamBindingNormal(){
        String compiled = loweredCode(SugarCompiler.compile("""
            funcdef f a 2
            print a
            blockend
            funccall f "5" ~
            """, SugarCompiler.FuncMode.normal));
        check(compiled.contains("set a 5"), "normal call did not bind the argument");
        check(compiled.contains("set __ls_func_f_ret @counter"), "normal call did not save the return address");
        check(compiled.contains("op add __ls_func_f_ret __ls_func_f_ret 2"), "normal call did not compute the return offset");
        check(compiled.contains("jump __ls_func_f_entry always x false"), "normal call did not jump to the entry");
        check(compiled.contains("__ls_func_f_entry:\nprint a\n"), "function body was not hoisted");
        check(compiled.contains("set @counter __ls_func_f_ret"), "function body does not return through the saved address");
    }

    private static void functionVoidAndEarlyReturn(){
        String compiled = loweredCode(SugarCompiler.compile("""
            funcdef f ~ 4
            print hi
            return ""
            print bye
            blockend
            funccall f "" ~
            """, SugarCompiler.FuncMode.inline));
        check(compiled.contains("jump __ls_i_0_exit always x false"), "void return did not leave the inline copy");
        check(compiled.contains("\n__ls_i_0_exit:\n"), "inline copy has no exit label");
        check(compiled.contains("print bye"), "statements after an early return were dropped");

        // two call sites get distinct copy prefixes
        String twice = loweredCode(SugarCompiler.compile("""
            funcdef f ~ 2
            print hi
            blockend
            funccall f "" ~
            funccall f "" ~
            """, SugarCompiler.FuncMode.inline));
        check(twice.contains("__ls_i_0_exit:") && twice.contains("__ls_i_1_exit:"), "inline copies share an exit label");
    }

    private static void functionReturnValue(){
        String inline = loweredCode(SugarCompiler.compile("""
            funcdef f ~ 2
            return "x * 2"
            blockend
            funccall f "" out
            """, SugarCompiler.FuncMode.inline));
        check(inline.contains("op mul __ls_func_f_result x 2"), "return value was not computed into the result slot");
        check(inline.contains("set out __ls_func_f_result"), "caller result was not copied from the result slot");

        String normal = loweredCode(SugarCompiler.compile("""
            funcdef f ~ 2
            return "x * 2"
            blockend
            funccall f "" out
            """, SugarCompiler.FuncMode.normal));
        check(normal.contains("op mul __ls_func_f_result x 2"), "normal mode did not compute the return value");
        check(normal.contains("set out __ls_func_f_result"), "normal mode did not copy the result at the call site");

        // a value-returning function may also be called without a result slot
        String ignored = SugarCompiler.compile("""
            funcdef f ~ 2
            return "x"
            blockend
            funccall f "" ~
            """, SugarCompiler.FuncMode.normal);
        check(ignored.contains("set @counter"), "value-returning function called as void did not compile");
    }

    private static void functionNestedCalls(){
        String inline = loweredCode(SugarCompiler.compile("""
            funcdef g a 2
            op add r a 1
            blockend
            funcdef f ~ 5
            funccall g "2" ~
            blockend
            funccall f "" ~
            """, SugarCompiler.FuncMode.inline));
        check(inline.contains("set a 2"), "nested inline call did not bind its argument");
        check(inline.contains("op add r a 1"), "nested inline call did not copy the callee body");

        String normal = loweredCode(SugarCompiler.compile("""
            funcdef g a 2
            op add r a 1
            blockend
            funcdef f ~ 5
            funccall g "2" ~
            blockend
            funccall f "" ~
            """, SugarCompiler.FuncMode.normal));
        check(normal.contains("__ls_func_g_entry:") && normal.contains("__ls_func_f_entry:"), "nested functions were not hoisted");
        check(normal.contains("set __ls_func_g_ret @counter"), "call inside a function body did not use the callee return slot");
    }

    private static void functionCallBeforeDefinition(){
        for(SugarCompiler.FuncMode mode : SugarCompiler.FuncMode.values()){
            String compiled = loweredCode(SugarCompiler.compile("""
                funccall f "1" ~
                funcdef f a 3
                print a
                blockend
                """, mode));
            check(compiled.contains("set a 1"), "call before definition failed in mode " + mode);
        }
    }

    private static void functionCallInLoop(){
        String compiled = loweredCode(SugarCompiler.compile("""
            forbegin i 0 1 lessThanEq 5 2
            funccall f "i" ~
            blockend
            funcdef f x 5
            print x
            blockend
            """, SugarCompiler.FuncMode.inline));
        check(compiled.contains("set x i"), "call inside a loop did not bind the loop variable");
        check(compiled.contains("jump __ls_for_check_0"), "loop structure was lost around the inline copy");
    }

    private static void functionCallerTempSurvives(){
        String compiled = loweredCode(SugarCompiler.compile("""
            set _0 7
            funcdef f ~ 3
            op mul _0 _0 2
            blockend
            funccall f "" ~
            set x _0
            """, SugarCompiler.FuncMode.inline));
        check(compiled.contains("set _0 7") && compiled.contains("set x _0"), "caller temporary was renamed");
        check(compiled.contains("op mul __ls_f_f_0 __ls_f_f_0 2"), "function body temporary was not namespaced");
        check(!compiled.contains("op mul _0 _0 2"), "function body temporary clobbers the caller temporary");
    }

    private static void functionBodyTempIsNamespaced(){
        // temps inside nested bodies stay per-function even in normal mode
        String compiled = loweredCode(SugarCompiler.compile("""
            funcdef f ~ 2
            op add _0 _0 1
            blockend
            funccall f "" ~
            """, SugarCompiler.FuncMode.normal));
        check(compiled.contains("op add __ls_f_f_0 __ls_f_f_0 1"), "normal mode body temp was not namespaced");
    }

    private static void quotedExpressionsRoundTrip(){
        // args/expr containing quotes, tildes and inner spaces must survive write → read losslessly
        FuncCallStatement call = new FuncCallStatement();
        call.name = "f";
        call.args = "\"a  b\" ~ \"c\"";
        call.result = "out";
        StringBuilder callText = new StringBuilder();
        call.write(callText);
        Seq<LStatement> callParsed = LAssembler.read(callText.toString(), true);
        check(callParsed.size == 1 && callParsed.get(0) instanceof FuncCallStatement roundTrip
            && roundTrip.args.equals("\"a  b\" ~ \"c\"") && roundTrip.result.equals("out"),
            "funccall args with quotes/tildes did not round-trip: " + callText);

        ReturnStatement ret = new ReturnStatement();
        ret.expr = "\"x\" == \"y\"";
        StringBuilder retText = new StringBuilder();
        ret.write(retText);
        Seq<LStatement> retParsed = LAssembler.read(retText.toString(), true);
        check(retParsed.size == 1 && retParsed.get(0) instanceof ReturnStatement rt
            && rt.expr.equals("\"x\" == \"y\""), "return expr with quotes did not round-trip: " + retText);
    }

    private static void truncatedLinesFailCleanly(){
        // Truncated custom-prefix lines must fail with a clean parse error (not a raw
        // NumberFormatException/NPE). LParser hands parsers a static 16-slot array whose
        // trailing slots are null/stale, so the tests emulate that shape.
        expectParseFailure(() -> SugarStatements.parseForBegin(tokens("forbegin")), "forbegin");
        expectParseFailure(() -> SugarStatements.parseForBegin(tokens("forbegin", "i", "0", "1")), "forbegin without a condition");
        expectParseFailure(() -> SugarStatements.parseSwitchBegin(tokens("switchbegin")), "switchbegin");
        expectParseFailure(() -> SugarStatements.parseFuncDef(tokens("funcdef")), "funcdef");
        expectParseFailure(() -> SugarStatements.parseFuncCall(tokens("funccall")), "funccall");

        // a bare return is a valid void return, not an error
        ReturnStatement ret = (ReturnStatement)SugarStatements.parseReturn(tokens("return"));
        check(ret.expr.isEmpty(), "bare return did not parse as a void return");
    }

    private static String[] tokens(String... values){
        String[] result = new String[16]; // LParser's static token array size
        System.arraycopy(values, 0, result, 0, values.length);
        return result;
    }

    private static void expectParseFailure(arc.func.Prov<LStatement> parser, String label){
        try{
            parser.get();
            check(false, "'" + label + "' parsed without error");
        }catch(IllegalArgumentException expected){
            check(expected.getMessage().startsWith("Invalid "), "'" + label + "' error lacks context: " + expected.getMessage());
        }
    }

    private static void functionStringLiteralsSurviveRewrite(){
        // multi-space strings and _digit patterns inside quotes must survive the body rewrite
        // untouched: "cost  _1  credits" is text, not a variable reference
        String compiled = loweredCode(SugarCompiler.compile("""
            funcdef f ~ 4
            op add _0 _0 1
            op add _1 _0 1
            print "cost  _1  credits"
            blockend
            funccall f "" ~
            """, SugarCompiler.FuncMode.inline));
        check(compiled.contains("op add __ls_f_f_1 __ls_f_f_0 1"), "temps outside strings were not namespaced: " + compiled);
        check(compiled.contains("cost  _1  credits"), "string literal inside a function body was rewritten: " + compiled);
    }

    private static void storageDevicesStayExempt(){
        // a storage device name written by a library function must stay exempt from mangling
        // (the digit run starts at the end of the prefix: memory is 6 chars, cell/bank 4)
        Seq<LStatement> libraryStatements = LAssembler.read("""
            funcdef f ~ 3
            set memory1 5
            set bank1 6
            blockend
            """, true);
        SugarFunctions.LibraryIndex library = SugarFunctions.buildLibrary(libraryStatements);
        String compiled = loweredCode(SugarCompiler.compile("funccall f \"\" ~\nend\n",
            SugarCompiler.FuncMode.normal, library));
        check(compiled.contains("set memory1 5"), "memory1 was mangled in a library function body: " + compiled);
        check(compiled.contains("set bank1 6"), "bank1 was mangled in a library function body");
    }

    private static void functionJumpToOwnEndIsExit(){
        String inline = loweredCode(SugarCompiler.compile("""
            funcdef f ~ 3
            set x 1
            jump 3 always x false
            blockend
            funccall f "" ~
            """, SugarCompiler.FuncMode.inline));
        check(inline.contains("jump __ls_i_0_exit always x false"), "jump to the function end did not become an exit");

        String normal = loweredCode(SugarCompiler.compile("""
            funcdef f ~ 3
            set x 1
            jump 3 always x false
            blockend
            funccall f "" ~
            """, SugarCompiler.FuncMode.normal));
        check(normal.contains("jump __ls_func_f_exit always x false"), "normal mode did not route the end jump through the exit label");
    }

    private static void functionJumpBoundariesRejected(){
        expectFailure("funcdef f ~ 2\nset x 1\nblockend\njump 1 always x false\n", "jump into a function body");
        expectFailure("funcdef f ~ 3\nset x 1\njump 0 always x false\nblockend\n", "jump out of a function body");
        expectFailure("funcdef f ~ 2\nset x 1\nblockend\njump 0 always x false\n", "jump to a function boundary from outside");
        expectFailure("funcdef f ~ 1\nblockend\njump 0 always x false\n", "jump to a function definition from outside");
    }

    private static void functionValidationRejected(){
        expectFailure("funccall nope \"\" ~\n", "call to an undefined function");
        expectFailure("funcdef f ~ 1\nblockend\nfuncdef f ~ 3\nblockend\n", "duplicate function names");
        expectFailure("funcdef f ~ 3\nfuncdef g ~ 2\nblockend\nblockend\n", "nested function definition");
        expectFailure("forbegin i 0 1 lessThanEq 3 3\nfuncdef f ~ 2\nblockend\nblockend\n", "function inside a loop");
        expectFailure("return \"\"\n", "return outside a function");
        expectFailure("funcdef f a,b 2\nprint a\nblockend\nfunccall f \"1\" ~\n", "argument count mismatch");
        expectFailure("funcdef f ~ 2\nset x 1\nblockend\nfunccall f \"\" out\n", "result requested from a void function");
        expectFailure("funcdef 9bad ~ 1\nblockend\n", "invalid function name");
        expectFailure("funcdef f a,a 2\nprint a\nblockend\n", "duplicate parameter names");
        expectFailure("funcdef __ls_x ~ 1\nblockend\n", "reserved function name prefix");
    }

    private static void functionRecursionRejected(){
        String direct = """
            funcdef f ~ 2
            funccall f "" ~
            blockend
            funccall f "" ~
            """;
        try{
            SugarCompiler.compile(direct, SugarCompiler.FuncMode.inline);
            throw new AssertionError("direct recursion was not rejected");
        }catch(IllegalArgumentException expected){
            check(expected.getMessage().contains("recursion"), "recursion error has no explanation");
        }

        String indirect = """
            funcdef a ~ 2
            funccall b "" ~
            blockend
            funcdef b ~ 5
            funccall a "" ~
            blockend
            funccall a "" ~
            """;
        try{
            SugarCompiler.compile(indirect, SugarCompiler.FuncMode.normal);
            throw new AssertionError("indirect recursion was not rejected");
        }catch(IllegalArgumentException expected){
            check(expected.getMessage().contains("a -> b -> a"), "recursion error does not show the cycle path");
        }
    }

    private static void functionUnreachableCostsNothing(){
        String compiled = loweredCode(SugarCompiler.compile("""
            funcdef f ~ 2
            print hi
            blockend
            print main
            """, SugarCompiler.FuncMode.normal));
        check(!compiled.contains("__ls_func_f_entry"), "unreachable function body was hoisted");
        check(compiled.equals("print main\n"), "unreachable function changed the main program");
    }

    private static void functionInstructionLimitHint(){
        StringBuilder body = new StringBuilder();
        for(int i = 0; i < 40; i++) body.append("set v").append(i).append(' ').append(i).append('\n');
        StringBuilder calls = new StringBuilder();
        for(int i = 0; i < 30; i++) calls.append("funccall f \"\" ~\n");
        String sugar = "funcdef f ~ " + (40 + 1) + "\n" + body + "blockend\n" + calls;

        try{
            SugarCompiler.compile(sugar, SugarCompiler.FuncMode.inline);
            throw new AssertionError("inline blowup did not hit the instruction limit");
        }catch(IllegalArgumentException expected){
            check(expected.getMessage().contains("maximum is"), "instruction limit error missing");
            check(expected.getMessage().contains("normal mode"), "inline over-limit error has no mode hint");
        }

        // the same program in normal mode shares the body and fits
        String normal = SugarCompiler.compile(sugar, SugarCompiler.FuncMode.normal);
        check(normal.contains("__ls_func_f_entry:"), "normal mode did not share the function body");
    }

    /** Runs a compiled program headless and returns the value of a variable after it ends. */
    private static double execute(String sugar, SugarCompiler.FuncMode mode, String variable){
        return executeCode(SugarCompiler.compile(sugar, mode), variable);
    }

    /** Runs an already-compiled program headless (strategy-specific variants). */
    private static double executeCode(String code, String variable){
        LExecutor executor = new LExecutor();
        executor.load(LAssembler.assemble(code, true));
        for(int i = 0; i < 20000 && executor.counter.numval >= 0 && executor.counter.numval < executor.instructions.length; i++){
            executor.runOnce();
        }
        LVar result = executor.optionalVar(variable);
        return result == null ? Double.NaN : result.numval;
    }

    private static void functionProgramsExecute(){
        Vars.logicVars = new GlobalVars();
        // minimal stub: register the boolean constants the loop lowering relies on
        Vars.logicVars.putEntry("false", 0);
        Vars.logicVars.putEntry("true", 1);

        // nested calls with parameters and return values
        String nested = """
            funcdef g a 2
            return "a * 2"
            blockend
            funcdef f x 6
            funccall g "x + 1" mid
            return "mid + 1"
            blockend
            set base 5
            funccall f "base" out
            end
            """;
        for(SugarCompiler.FuncMode mode : SugarCompiler.FuncMode.values()){
            check(execute(nested, mode, "out") == 13.0, mode + ": nested call result is wrong");
            check(execute(nested, mode, "mid") == 12.0, mode + ": nested intermediate result is wrong");
        }

        // early return with value skips the rest of the body
        String early = """
            funcdef f a 3
            return "a * 10"
            set x 999
            blockend
            funccall f "3" out
            end
            """;
        for(SugarCompiler.FuncMode mode : SugarCompiler.FuncMode.values()){
            check(execute(early, mode, "out") == 30.0, mode + ": early value return is wrong");
            check(execute(early, mode, "x") == 0.0, mode + ": dead code after return executed");
        }

        // void early return still runs the side effects before it
        String voidReturn = """
            funcdef f ~ 4
            set flag 1
            return ""
            set flag 999
            blockend
            funccall f "" ~
            end
            """;
        for(SugarCompiler.FuncMode mode : SugarCompiler.FuncMode.values()){
            check(execute(voidReturn, mode, "flag") == 1.0, mode + ": void early return skipped preceding code");
        }

        // function called inside a loop accumulates into a caller variable
        String loopCall = """
            set sum 0
            funcdef f a 3
            op add sum sum a
            blockend
            forbegin i 1 1 lessThanEq 3 6
            funccall f "i" ~
            blockend
            end
            """;
        for(SugarCompiler.FuncMode mode : SugarCompiler.FuncMode.values()){
            check(execute(loopCall, mode, "sum") == 6.0, mode + ": call inside a loop is wrong");
        }

        // switch with value returns inside a function body
        String switchBody = """
            funcdef grade s 8
            switchbegin s 6
            case 1
            return "10"
            case 2
            return "20"
            blockend
            return "0"
            blockend
            funccall grade "2" out
            end
            """;
        for(SugarCompiler.FuncMode mode : SugarCompiler.FuncMode.values()){
            check(execute(switchBody, mode, "out") == 20.0, mode + ": switch inside a function body is wrong");
        }

        // while with break and a continue jump inside a function body
        String loopBody = """
            funcdef f ~ 7
            set i 0
            whilebegin true 6
            op add i i 1
            jump 6 lessThan i 5
            break
            blockend
            blockend
            funccall f "" ~
            set out i
            end
            """;
        for(SugarCompiler.FuncMode mode : SugarCompiler.FuncMode.values()){
            check(execute(loopBody, mode, "out") == 5.0, mode + ": while/break inside a function body is wrong");
        }
    }

    /** Runs a compiled program that resolves calls against a library index. */
    private static double executeLibrary(String sugar, SugarCompiler.FuncMode mode, SugarFunctions.LibraryIndex library, String variable){
        String code = SugarCompiler.compile(sugar, mode, library);
        LExecutor executor = new LExecutor();
        executor.load(LAssembler.assemble(code, true));
        for(int i = 0; i < 20000 && executor.counter.numval >= 0 && executor.counter.numval < executor.instructions.length; i++){
            executor.runOnce();
        }
        LVar result = executor.optionalVar(variable);
        return result == null ? Double.NaN : result.numval;
    }

    private static void libraryFunctions(){
        Seq<LStatement> libraryStatements = LAssembler.read("""
            funcdef add a,b 3
            op add s a b
            return "s * 2"
            blockend
            funcdef inner a 6
            return "a + 1"
            blockend
            funcdef outer x 10
            funccall inner "x" mid
            return "mid * 10"
            blockend
            """, true);
        SugarFunctions.LibraryIndex library = SugarFunctions.buildLibrary(libraryStatements);

        // mangling: body writes are local, reads see caller globals; both expansion modes
        String processor = """
            set x 3
            set s 999
            funccall add "x, 4" out
            end
            """;
        for(SugarCompiler.FuncMode mode : SugarCompiler.FuncMode.values()){
            String compiled = loweredCode(SugarCompiler.compile(processor, mode, library));
            check(compiled.contains("set __ls_func_add_a x"), mode + ": library parameter was not bound through the mangled name");
            check(compiled.contains("op add __ls_func_add_s __ls_func_add_a __ls_func_add_b"), mode + ": library body write was not mangled");
            check(compiled.contains("set s 999"), mode + ": caller variable disappeared");
            check(executeLibrary(processor, mode, library, "out") == 14.0, mode + ": library call result is wrong");
            check(executeLibrary(processor, mode, library, "s") == 999.0, mode + ": library function modified a caller variable");
        }

        // library functions call other library functions, mangled end to end
        String outer = "funccall outer \"2\" out\nend\n";
        for(SugarCompiler.FuncMode mode : SugarCompiler.FuncMode.values()){
            String compiled = loweredCode(SugarCompiler.compile(outer, mode, library));
            check(compiled.contains("set __ls_func_inner_a __ls_func_outer_x"), mode + ": nested library call did not mangle the argument expression");
            check(executeLibrary(outer, mode, library, "out") == 30.0, mode + ": nested library call result is wrong");
        }

        // local functions shadow library functions with the same name
        String shadow = """
            funcdef add a 2
            return "a + 100"
            blockend
            funccall add "1" out
            end
            """;
        check(executeLibrary(shadow, SugarCompiler.FuncMode.normal, library, "out") == 101.0, "local function did not shadow the library function");

        // a call that only the library can resolve fails without the library
        expectLibraryFailure("funccall nope \"1, 2\" out\n", library, "missing library function");

        // quoted strings survive the mangling rewrite untouched
        String quoted = loweredCode(SugarCompiler.compile("""
            funcdef f ~ 2
            print "hi there"
            blockend
            funccall f "" ~
            """, SugarCompiler.FuncMode.inline));
        check(quoted.contains("print \"hi there\""), "quoted strings were damaged by the mangling rewrite");
    }

    private static void libraryValidationRejected(){
        expectLibraryBuildFailure("funcdef f ~ 2\nfunccall f \"\" ~\nblockend\n", "library recursion");
        expectLibraryBuildFailure("funcdef f ~ 2\nfunccall nope \"\" ~\nblockend\n", "library function calls an undefined function");
        expectLibraryBuildFailure("set x 1\n", "stray statement in the library");
        expectLibraryBuildFailure("funcdef f ~ 2\nbreak\nblockend\n", "bare break inside a library function");
        expectLibraryBuildFailure("funcdef f ~ 3\nfuncdef g ~ 2\nblockend\nblockend\n", "nested library definition");
        expectLibraryBuildFailure("funcdef f a,a 2\nprint a\nblockend\n", "duplicate library parameter");
        expectLibraryBuildFailure("funcdef f ~ 3\nfunccall f \"\" ~\nblockend\n" + "funcdef f ~ 6\nblockend\n", "duplicate library function");
        expectLibraryBuildFailure("funcdef f ~ 7\nifbegin a equal 0 6\nelse\nset x 1\nelif b equal 0\nset x 2\nblockend\nblockend\n", "elif after else inside a library function");
    }

    /** Extracted function subsets must re-validate and compile identically to the original. */
    private static void libraryExtractionIsSelfContained(){
        String libraryText = """
            funcdef add a,b 3
            op add s a b
            return "s * 2"
            blockend
            funcdef outer x 7
            funccall inner "x" mid
            return "mid * 10"
            blockend
            funcdef inner a 10
            return "a + 1"
            blockend
            funcdef unused x 13
            print x
            blockend
            """;
        Set<String> used = new HashSet<>(List.of("outer", "inner"));
        String extracted = SugarFunctions.extractLibrarySource(libraryText, used);
        SugarFunctions.LibraryIndex index = SugarFunctions.buildLibrary(LAssembler.read(extracted, true));
        check(index.functions.containsKey("outer") && index.functions.containsKey("inner"), "extracted library misses functions");
        check(!index.functions.containsKey("add") && !index.functions.containsKey("unused"), "extracted library has extra functions");

        // outer is defined before inner in the text but calls it; the extracted subset must
        // compile to exactly the same program as the original library
        String sugar = "funccall outer \"2\" out\nend\n";
        String viaOriginal = SugarCompiler.compile(sugar, SugarCompiler.FuncMode.normal,
            SugarFunctions.buildLibrary(LAssembler.read(libraryText, true)), libraryText);
        String viaExtracted = SugarCompiler.compile(sugar, SugarCompiler.FuncMode.normal, index, extracted);
        check(normalize(viaOriginal).equals(normalize(viaExtracted)), "extracted library compiles differently from the original");

        // the extraction is idempotent: re-extracting the used subset from itself is a no-op
        check(SugarFunctions.extractLibrarySource(extracted, used).equals(extracted), "extraction is not idempotent");
    }

    /** The carrier must survive a vanilla parse/save round trip (markers are dropped by it). */
    private static void carrierSurvivesVanillaRoundTrip(){
        String sugar = """
            whilebegin true 2
            set x 1
            blockend
            """;
        String compiled = SugarCompiler.compile(sugar);
        check(SugarCompiler.restore(compiled).equals(sugar), "carrier round-trip changed sugar source");

        // a vanilla save drops the comment markers but keeps the carrier set statement;
        // a no-mod player parses with privileged=false, so use that for realism
        String vanillaSaved = LAssembler.write(LAssembler.read(compiled, false));
        check(!vanillaSaved.contains("# @logic-sugar"), "vanilla save kept the comment marker");
        check(SugarCompiler.restore(vanillaSaved).equals(sugar), "sugar was lost across a vanilla save");

        // stripping the marker block (the 16KB fallback) must not lose the sugar either
        String stripped = SugarCompiler.stripMarkers(compiled);
        check(!stripped.contains("# @logic-sugar"), "marker block was not stripped");
        check(SugarCompiler.restore(stripped).equals(sugar), "sugar was lost after marker stripping");
    }

    /** The carrier is authoritative: tampering with the marker block must not matter. */
    private static void restorePrefersCarrier(){
        String sugar = "whilebegin true 2\nset x 1\nblockend\n";
        String compiled = SugarCompiler.compile(sugar);
        String tampered = compiled.replace("# @logic-sugar-line set x 1", "# @logic-sugar-line set x 999");
        check(!tampered.equals(compiled), "test setup: tampering changed nothing");
        check(SugarCompiler.restore(tampered).equals(sugar), "carrier did not take priority over the markers");
    }

    /** The embedded library subset must reproduce the compiled program on any machine. */
    private static void libraryEmbeddingRoundTrip(){
        String libraryText = """
            funcdef add a,b 3
            op add s a b
            return "s * 2"
            blockend
            funcdef unused x 6
            print x
            blockend
            """;
        SugarFunctions.LibraryIndex library = SugarFunctions.buildLibrary(LAssembler.read(libraryText, true));
        String sugar = "set x 3\nfunccall add \"x, 4\" out\nend\n";
        String compiled = SugarCompiler.compile(sugar, SugarCompiler.FuncMode.normal, library, libraryText);
        String embedded = SugarCompiler.libraryFromCode(compiled);
        check(embedded != null, "library was not embedded into the compiled code");
        check(embedded.contains("funcdef add") && !embedded.contains("unused"), "embedded library has the wrong function subset");

        // recompiling with only the embedded library reproduces the stored program exactly
        SugarFunctions.LibraryIndex embeddedIndex = SugarFunctions.buildLibrary(LAssembler.read(embedded, true));
        String restored = SugarCompiler.restore(compiled);
        String recompiled = SugarCompiler.compile(restored, SugarCompiler.FuncMode.normal, embeddedIndex, embedded);
        check(normalize(compiled).equals(normalize(recompiled)), "recompiling with the embedded library changed the output");

        // an empty local library must still compile through the embedded one (the effective
        // library merge the dialog performs on open)
        SugarCompiler.EffectiveLibrary effective = SugarCompiler.effectiveLibrary(compiled,
            SugarFunctions.buildLibrary(LAssembler.read("", true)), "");
        check(effective.index != null && effective.index.functions.containsKey("add"), "embedded library was not merged over an empty local library");
        String noLocal = SugarCompiler.compile(restored, SugarCompiler.FuncMode.normal, effective.index, effective.text);
        check(normalize(noLocal).equals(normalize(compiled)), "compiling without the local library changed the output");
    }

    /** External edits to the compiled code must be detected; innocent round trips must pass. */
    private static void verificationDetectsExternalEdits(){
        String sugar = "whilebegin true 3\nset x 1\nset y 2\nblockend\n";
        String compiled = SugarCompiler.compile(sugar);
        check(SugarCompiler.verifyRestore(compiled, SugarCompiler.restore(compiled)), "pristine code failed verification");

        // external edit: change a value inside the compiled code
        String edited = compiled.replace("set y 2", "set y 999");
        check(!SugarCompiler.verifyRestore(edited, SugarCompiler.restore(edited)), "external edit was not detected");

        // a vanilla save round trip (markers stripped, carrier kept) still verifies
        String vanillaSaved = LAssembler.write(LAssembler.read(compiled, true));
        check(SugarCompiler.verifyRestore(vanillaSaved, SugarCompiler.restore(vanillaSaved)), "vanilla round trip failed verification");

        // both function modes are tried during verification
        String inline = SugarCompiler.compile(sugar, SugarCompiler.FuncMode.inline);
        check(SugarCompiler.verifyRestore(inline, SugarCompiler.restore(inline)), "inline compilation failed verification");
    }

    /** Marker stripping is the 16KB fallback; restore must still work after it. */
    private static void oversizeStripsComments(){
        // random values make the program incompressible, so the stored form can exceed the
        // 16KB compressed limit while the marker-stripped form still fits
        boolean exercised = false;
        for(int lines : new int[]{100, 110, 115, 120, 125, 130, 140, 160, 180, 220, 260, 300}){
            Random random = new Random(0x5eed);
            StringBuilder sugar = new StringBuilder("whilebegin true " + (lines + 1) + "\n");
            for(int i = 0; i < lines; i++){
                sugar.append("set v").append(i).append(" \"");
                for(int j = 0; j < 56; j++){
                    sugar.append("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".charAt(random.nextInt(62)));
                }
                sugar.append("\"\n");
            }
            sugar.append("blockend\n");
            String compiled = SugarCompiler.compile(sugar.toString());
            check(SugarCompiler.restore(compiled).equals(sugar.toString()), "large program failed carrier round-trip");

            byte[] withMarkers = LogicBlock.compress(compiled, new Seq<>());
            String stripped = SugarCompiler.stripMarkers(compiled);
            byte[] withoutMarkers = LogicBlock.compress(stripped, new Seq<>());
            check(!stripped.contains("# @logic-sugar"), "marker block was not stripped");
            check(SugarCompiler.restore(stripped).equals(sugar.toString()), "sugar was lost after marker stripping");
            if(withMarkers.length > 16000){
                check(withoutMarkers.length <= 16000,
                    "marker-stripped form still exceeds the storage limit: " + withoutMarkers.length);
                exercised = true;
                break;
            }
        }
        if(!exercised){
            System.out.println("note: could not exceed the 16KB compressed limit with generated content; "
                + "the oversize path was not exercised (strip/restore chain still verified)");
        }
    }

    /** Vanilla-compatible normalization: comments dropped, label jumps folded into indices. */
    private static String normalize(String code){
        return LAssembler.write(LAssembler.read(code, true));
    }

    private static void functionCallsMarkedInvalid(){
        SugarFunctions.setLibrarySource(null);
        try{
            Seq<LStatement> unresolved = LAssembler.read("funccall nope \"\" ~\n", true);
            check(SugarCompiler.invalidStatements(unresolved)[0], "unresolved function call was not marked invalid");

            Seq<LStatement> local = LAssembler.read("funcdef f ~ 2\nset x 1\nblockend\nfunccall f \"\" ~\n", true);
            boolean[] localInvalid = SugarCompiler.invalidStatements(local);
            check(!localInvalid[0] && !localInvalid[3], "resolved local function call was marked invalid");

            // library-provided functions resolve lazily
            SugarFunctions.setLibrarySource(() -> SugarFunctions.buildLibrary(LAssembler.read("funcdef g ~ 2\nset x 1\nblockend\n", true)));
            try{
                Seq<LStatement> libraryCall = LAssembler.read("funccall g \"\" ~\n", true);
                check(!SugarCompiler.invalidStatements(libraryCall)[0], "library function call was marked invalid");
            }finally{
                SugarFunctions.setLibrarySource(null);
            }
        }finally{
            SugarFunctions.setLibrarySource(null);
        }
    }

    private static void expectLibraryFailure(String source, SugarFunctions.LibraryIndex library, String scenario){
        try{
            SugarCompiler.compile(source, SugarCompiler.FuncMode.normal, library);
            throw new AssertionError("Expected failure for " + scenario);
        }catch(IllegalArgumentException expected){
            // Expected validation failure.
        }
    }

    private static void expectLibraryBuildFailure(String source, String scenario){
        try{
            SugarFunctions.buildLibrary(LAssembler.read(source, true));
            throw new AssertionError("Expected library build failure for " + scenario);
        }catch(IllegalArgumentException expected){
            // Expected validation failure.
        }
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

    // ===== library damage harness (Q1 witness regression) ================================
    //
    // Q1 is the library file from the v2.1.2 bug report: a `spawnUnits` function built from
    // vanilla world statements plus an EMPTY second function `func`. The report's failure
    // chain: the file later gained duplicate function definitions -> the library became
    // invalid -> every processor that called a library function failed to compile/save with
    // a misleading "calls undefined function" error.
    private static final String q1LibraryText = """
        funcdef spawnUnits count,team,unit,x,y 5
        forbegin i 0 1 lessThan count 4
        spawn unit x y 0 team r false
        explosion @crux x y 10 1000 true true true false
        blockend
        blockend
        funcdef func a,b 7
        blockend
        """;
    /** Q1 with three duplicate `spawn` definitions appended (the report's actual trigger). */
    private static final String q1DuplicatedText = q1LibraryText + "funcdef spawn a 9\nblockend\nfuncdef spawn a 11\nblockend\nfuncdef spawn a 13\nblockend\n";

    private static void libraryDamageHarness(){
        System.out.println("== library damage harness R1-R7 ==");
        String q1 = q1LibraryText;
        String wprocSpawnUnits = "set x 1\nfunccall spawnUnits \"1, 1, 1, 1, 1\" ~\nend\n";
        String wprocFunc = "funccall func \"1, 2\" ~\nend\n";

        boolean ok = true;

        // R1: valid Q1 builds
        SugarFunctions.LibraryIndex q1Index = null;
        try{
            q1Index = SugarFunctions.buildLibrary(LAssembler.read(q1, true));
            System.out.println("R1 buildLibrary(Q1): ok, functions=" + q1Index.functions.keySet());
        }catch(Throwable t){
            ok = false;
            System.out.println("R1 buildLibrary(Q1): FAIL -> " + t.getMessage());
        }
        if(q1Index != null){
            check(q1Index.functions.containsKey("spawnUnits"), "R1: spawnUnits missing from Q1 index");
            check(q1Index.functions.containsKey("func"), "R1: func missing from Q1 index");
        }

        // R2: processor calls compile against the valid Q1 index
        if(q1Index != null){
            for(SugarCompiler.FuncMode mode : SugarCompiler.FuncMode.values()){
                try{
                    SugarCompiler.compile(wprocSpawnUnits, mode, q1Index);
                    System.out.println("R2 call spawnUnits " + mode + ": ok");
                }catch(Throwable t){
                    ok = false;
                    System.out.println("R2 call spawnUnits " + mode + ": FAIL -> " + t.getMessage());
                }
            }
            try{
                SugarCompiler.compile(wprocFunc, SugarCompiler.FuncMode.normal, q1Index);
                System.out.println("R2 call empty func (2 args, no result): ok");
            }catch(Throwable t){
                ok = false;
                System.out.println("R2 call empty func (2 args, no result): FAIL -> " + t.getMessage());
            }
        }

        // R3: record the current error texts (asserted verbatim by T3 after the fix)
        String arityText = errorText("funccall func \"1\" ~\nend\n", q1Index);
        String resultText = errorText("funccall func \"1, 2\" out\nend\n", q1Index);
        System.out.println("R3 arity error: " + arityText);
        System.out.println("R3 result error: " + resultText);
        check(arityText != null && arityText.contains("argument"), "R3: no arity error text recorded");
        check(resultText != null && resultText.contains("requests a result"), "R3: no result error text recorded");

        // R4: duplicate definitions injected -> buildLibrary throws duplicate function name
        String q1Duplicated = q1DuplicatedText;
        try{
            SugarFunctions.buildLibrary(LAssembler.read(q1Duplicated, true));
            ok = false;
            System.out.println("R4 duplicate names: NOT REPRODUCED (no throw)");
        }catch(IllegalArgumentException expected){
            System.out.println("R4 duplicate names: reproduced -> " + expected.getMessage());
            check(expected.getMessage().contains("duplicate function name 'spawn'"), "R4: wrong duplicate error text");
        }catch(Throwable t){
            ok = false;
            System.out.println("R4 duplicate names: FAIL -> " + t.getMessage());
        }

        // R5: end-to-end failure chain (legacy processor without an embedded library carrier)
        if(q1Index != null){
            // valid snapshot compiles fine while the file is good (passes the text so the
            // used subset gets embedded into the stored code, like a real save does)
            String stored = SugarCompiler.compile(wprocSpawnUnits, SugarCompiler.FuncMode.normal, q1Index, q1);
            check(SugarCompiler.libraryFromCode(stored) != null, "R5 setup: stored code should carry the embedded library");

            // the file turns bad: the library source now fails to load
            SugarFunctions.setLibrarySource(() -> null);
            try{
                SugarFunctions.LibraryIndex local = SugarFunctions.library();
                String code = "set x 1\nfunccall spawnUnits \"1, 1, 1, 1, 1\" ~\nend\n"; // legacy, no carrier
                SugarCompiler.EffectiveLibrary effective = SugarCompiler.effectiveLibrary(code, local, "");
                try{
                    SugarCompiler.compile("set x 1\nfunccall spawnUnits \"1, 1, 1, 1, 1\" ~\nend\n",
                        SugarCompiler.FuncMode.normal, effective.index, effective.text);
                    ok = false;
                    System.out.println("R5 end-to-end undefined: NOT REPRODUCED (compiled anyway)");
                }catch(IllegalArgumentException e){
                    System.out.println("R5 end-to-end undefined: reproduced -> " + e.getMessage());
                    check(e.getMessage().startsWith("funccall at statement 1 calls undefined function 'spawnUnits'."),
                        "R5: reported error prefix changed");
                    check(e.getMessage().contains("library is unavailable; check Settings -> Function Library"),
                        "R5: library cause is not explained");
                }
                // a function that truly does not exist against a valid library keeps the plain text
                if(q1Index != null){
                    String plain = errorText("funccall nope \"1\" ~\nend\n", q1Index);
                    System.out.println("R5 normal missing: " + plain);
                    check(plain != null && plain.equals("funccall at statement 0 calls undefined function 'nope'."),
                        "R5: normal missing-function text changed");
                }
                // a salvaged (damaged) library explains the failure through its repair warning
                if(q1Index != null){
                    String dupText = q1DuplicatedText;
                    SugarFunctions.LibraryIndex damaged = SugarFunctions.sanitizedLibrary(dupText).index;
                    check(damaged.damaged, "R5: salvaged library should be flagged damaged");
                    String damagedError = errorText("funccall nope \"1\" ~\nend\n", damaged);
                    System.out.println("R5 damaged hint: " + damagedError);
                    check(damagedError != null && damagedError.contains("Note: the function library has errors (duplicate function name 'spawn'"),
                        "R5: damaged library repair hint missing");
                }
            }finally{
                SugarFunctions.setLibrarySource(null);
            }
        }else{
            System.out.println("R5 skipped (Q1 did not build)");
        }

        // R6: extraction of the used subset is idempotent and re-validates
        try{
            Set<String> used = new HashSet<>(List.of("spawnUnits", "func"));
            String extracted = SugarFunctions.extractLibrarySource(q1, used);
            String again = SugarFunctions.extractLibrarySource(extracted, used);
            check(extracted.equals(again), "R6: extraction is not idempotent");
            SugarFunctions.LibraryIndex revalidated = SugarFunctions.buildLibrary(LAssembler.read(extracted, true));
            check(revalidated.functions.size() == 2, "R6: re-validated extracted library has " + revalidated.functions.size() + " functions");
            System.out.println("R6 extraction idempotent + re-validates: ok");
        }catch(Throwable t){
            ok = false;
            System.out.println("R6 extraction: FAIL -> " + t.getMessage());
        }

        // R7: session snapshot refresh - a program opened against the old library must pick
        // up a function added to the library file before submitting
        {
            String oldLib = "funcdef add a,b 3\nop add s a b\nreturn \"s * 1\"\nblockend\n";
            String newLib = oldLib + "funcdef sub a,b 7\nop sub s a b\nreturn \"s * 1\"\nblockend\n";
            SugarFunctions.LibraryIndex oldIndex = SugarFunctions.buildLibrary(LAssembler.read(oldLib, true));
            SugarFunctions.LibraryIndex newIndex = SugarFunctions.buildLibrary(LAssembler.read(newLib, true));
            // open-time snapshot vs. submit-time refresh (what SugarLogicDialog.submit does)
            SugarCompiler.EffectiveLibrary stale = SugarCompiler.effectiveLibrary("", oldIndex, oldLib);
            SugarCompiler.EffectiveLibrary fresh = SugarCompiler.effectiveLibrary("", newIndex, newLib);
            String program = "funccall sub \"3, 2\" out\nend\n";
            try{
                SugarCompiler.compile(program, SugarCompiler.FuncMode.normal, stale.index, stale.text);
                ok = false;
                System.out.println("R7 stale snapshot: NOT REPRODUCED (stale snapshot resolved the new function)");
            }catch(IllegalArgumentException e){
                System.out.println("R7 stale snapshot: stale snapshot fails -> " + e.getMessage());
            }
            try{
                SugarCompiler.compile(program, SugarCompiler.FuncMode.normal, fresh.index, fresh.text);
                System.out.println("R7 refreshed snapshot: compiles ok");
            }catch(Throwable t){
                ok = false;
                System.out.println("R7 refreshed snapshot: FAIL -> " + t.getMessage());
            }
        }

        check(ok, "library damage harness found a broken baseline expectation (see output above)");
        System.out.println("== harness complete ==");
    }

    /** Returns the compile error text for a processor against a library, or null when it compiles. */
    private static String errorText(String source, SugarFunctions.LibraryIndex library){
        try{
            SugarCompiler.compile(source, SugarCompiler.FuncMode.normal, library);
            return null;
        }catch(IllegalArgumentException expected){
            return expected.getMessage();
        }
    }

    // ===== library salvage regressions (T1-T5) ===========================================
    private static void librarySalvageRegressions(){
        String q1 = q1LibraryText;
        String q1Duplicated = q1DuplicatedText;

        // T1: the duplicated library is salvaged by function - spawnUnits, func and the last
        // dup'd spawn survive; the processor that used to fail now compiles in both modes
        SugarFunctions.SanitizedLibrary salvaged = SugarFunctions.sanitizedLibrary(q1Duplicated);
        check(salvaged.damaged, "T1: duplicated library was not flagged damaged");
        check(salvaged.index.functions.containsKey("spawnUnits"), "T1: spawnUnits was lost");
        check(salvaged.index.functions.containsKey("func"), "T1: func was lost");
        check(salvaged.index.functions.containsKey("spawn"), "T1: duplicate spawn was not kept");
        check(salvaged.index.functions.get("spawn").params.size() == 1, "T1: kept spawn is not the last definition");
        check(salvaged.warnings.stream().anyMatch(w -> w.contains("duplicate function name 'spawn'")),
            "T1: duplicate-name repair is not reported");
        for(SugarCompiler.FuncMode mode : SugarCompiler.FuncMode.values()){
            SugarCompiler.compile("funccall spawnUnits \"1, 1, 1, 1, 1\" ~\nend\n", mode, salvaged.index);
        }
        SugarFunctions.buildLibrary(LAssembler.read(salvaged.text, true));

        // T2: a fully valid library sanitizes byte-identically with no warnings
        SugarFunctions.SanitizedLibrary clean = SugarFunctions.sanitizedLibrary(q1);
        check(!clean.damaged && clean.warnings.isEmpty(), "T2: valid library flagged damaged");
        check(clean.text.equals(q1), "T2: sanitizer changed a fully valid library");

        // T3: empty-function call errors stay precise (arity / result request)
        SugarFunctions.LibraryIndex q1Index = SugarFunctions.buildLibrary(LAssembler.read(q1, true));
        String arity = errorText("funccall func \"1\" ~\nend\n", q1Index);
        check("funccall at statement 0 calls 'func' with 1 argument(s) but it expects 2.".equals(arity),
            "T3: arity text changed: " + arity);
        String result = errorText("funccall func \"1, 2\" out\nend\n", q1Index);
        check("funccall at statement 0 requests a result from 'func' but its body never returns a value.".equals(result),
            "T3: result text changed: " + result);

        // T4: extracting the slices around the empty function is idempotent and re-validates
        Set<String> used = new HashSet<>(List.of("spawnUnits", "func"));
        String extracted = SugarFunctions.extractLibrarySource(q1, used);
        check(extracted.equals(SugarFunctions.extractLibrarySource(extracted, used)), "T4: extraction is not idempotent");
        SugarFunctions.LibraryIndex reExtracted = SugarFunctions.buildLibrary(LAssembler.read(extracted, true));
        check(reExtracted.functions.containsKey("spawnUnits") && reExtracted.functions.containsKey("func"),
            "T4: extracted subset lost functions");

        // T5: a damaged local library merges into a non-null effective library with the
        // recoverable functions, and the damage state is propagated for the repair hint
        SugarCompiler.EffectiveLibrary effective = SugarCompiler.effectiveLibrary("", null, q1Duplicated);
        check(effective.index != null, "T5: effective library went null for a damaged local");
        check(effective.index.functions.containsKey("spawnUnits") && effective.index.functions.containsKey("func"),
            "T5: effective library lost the salvaged functions");
        check(effective.index.damaged && !effective.index.warnings.isEmpty(),
            "T5: effective merge did not propagate the damage state");
        // the merged library compiles the processor that the pre-fix release rejected
        SugarCompiler.compile("funccall spawnUnits \"1, 1, 1, 1, 1\" ~\nend\n",
            SugarCompiler.FuncMode.normal, effective.index, effective.text);
    }

    private static void registerParsersPublic(){
        registerParsers();
    }

    private static void registerParsers(){
        LAssembler.customParsers.put("forbegin", SugarStatements::parseForBegin);
        LAssembler.customParsers.put("forbeginc", tokens -> SugarStatements.parseForBegin(tokens, true));
        LAssembler.customParsers.put("whilebegin", SugarStatements::parseWhileBegin);
        LAssembler.customParsers.put("whilebeginc", tokens -> SugarStatements.parseWhileBegin(tokens, true));
        LAssembler.customParsers.put("switchbegin", SugarStatements::parseSwitchBegin);
        LAssembler.customParsers.put("switchbeginc", tokens -> SugarStatements.parseSwitchBegin(tokens, true));
        LAssembler.customParsers.put("ifbegin", SugarStatements::parseIfBegin);
        LAssembler.customParsers.put("ifbeginc", tokens -> SugarStatements.parseIfBegin(tokens, true));
        LAssembler.customParsers.put("case", SugarStatements::parseCase);
        LAssembler.customParsers.put("elif", SugarStatements::parseElseIf);
        LAssembler.customParsers.put("else", SugarStatements::parseElse);
        LAssembler.customParsers.put("break", tokens -> new BreakStatement());
        LAssembler.customParsers.put("continue", tokens -> new ContinueStatement());
        LAssembler.customParsers.put("blockend", tokens -> new BlockEndStatement());
        LAssembler.customParsers.put("funcdef", SugarStatements::parseFuncDef);
        LAssembler.customParsers.put("funcdefc", tokens -> SugarStatements.parseFuncDef(tokens, true));
        LAssembler.customParsers.put("funccall", SugarStatements::parseFuncCall);
        LAssembler.customParsers.put("return", SugarStatements::parseReturn);
        LAssembler.customParsers.put("forend", tokens -> new BlockEndStatement());
        LAssembler.customParsers.put("whileend", tokens -> new BlockEndStatement());
        LAssembler.customParsers.put("switchend", tokens -> new BlockEndStatement());
    }
}
