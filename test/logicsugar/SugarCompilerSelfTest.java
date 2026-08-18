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
        vanillaCodePassesThrough();
        expressionOpsRoundTrip();
        structuredTargetsFollowExpressionResize();
        functionStatementsRoundTrip();
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

        String switchCode = loweredCode(SugarCompiler.compile("""
            switchbegin x 3
            case 1
            print one
            blockend
            """));
        check(switchCode.contains("jump __ls_case_1 equal x 1"), "switch did not compare its source value directly");
        check(!switchCode.contains("__ls_switch_"), "switch temporary variable was emitted");
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
        String code = SugarCompiler.compile(sugar, mode);
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
