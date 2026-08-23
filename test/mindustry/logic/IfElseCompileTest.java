package mindustry.logic;

import mindustry.gen.LogicIO;
import mindustry.logic.SugarStatements.IfBeginStatement;

/**
 * Compile-level smoke test for the if / elif / else and while statements with
 * three-part conditions (value + ConditionOp + compare, matching for). Registers the
 * sugar statements directly (mirroring LogicSugarMod.registerStatements) and checks
 * the lowered mlog output.
 */
public final class IfElseCompileTest{
    private IfElseCompileTest(){
    }

    public static void main(String[] args){
        registerStatements();

        // if a > 5 { x=1 } elif b <= 3 { x=2 } else { x=3 }
        String sugar =
            "ifbegin a greaterThan 5 6\n" +
            "set x 1\n" +
            "elif b lessThanEq 3\n" +
            "set x 2\n" +
            "else\n" +
            "set x 3\n" +
            "blockend\n";

        String compiled = SugarCompiler.compile(sugar);
        System.out.println("=== if/elif/else ===\n" + compiled);

        check(compiled.contains("jump __ls_if_branch_2 lessThanEq a 5"), "if false branch (negated)");
        check(compiled.contains("set x 1"), "if body present");
        check(compiled.contains("jump __ls_stmt_7 always x false"), "if body exit jump");
        check(compiled.contains("__ls_if_branch_2:"), "elif label");
        check(compiled.contains("jump __ls_if_branch_4 greaterThan b 3"), "elif false branch (negated)");
        check(compiled.contains("set x 2"), "elif body present");
        check(compiled.contains("__ls_if_branch_4:"), "else label");
        check(compiled.contains("set x 3"), "else body present");
        check(compiled.contains("__ls_stmt_7:"), "end label");

        // simple if: if a == 0 { x=1 }
        String simple = "ifbegin a equal 0 2\nset x 1\nblockend\n";
        String compiledSimple = SugarCompiler.compile(simple);
        System.out.println("=== simple if ===\n" + compiledSimple);
        check(compiledSimple.contains("jump __ls_stmt_3 notEqual a 0"), "simple if false jump (negated)");
        check(!compiledSimple.contains("__ls_if_branch_"), "simple if has no branch labels");

        // if + else: if a != false { x=1 } else { x=2 }
        String ifElse = "ifbegin a notEqual false 4\nset x 1\nelse\nset x 2\nblockend\n";
        String compiledIfElse = SugarCompiler.compile(ifElse);
        System.out.println("=== if/else ===\n" + compiledIfElse);
        check(compiledIfElse.contains("jump __ls_if_branch_2 equal a false"), "if/else if jump (negated)");
        check(compiledIfElse.contains("__ls_if_branch_2:"), "if/else else label");

        // while with three-part condition: while x < 10 { set y 1 }
        String whileSugar = "whilebegin x lessThan 10 2\nset y 1\nblockend\n";
        String compiledWhile = SugarCompiler.compile(whileSugar);
        System.out.println("=== while ===\n" + compiledWhile);
        check(compiledWhile.contains("jump __ls_while_body_0 lessThan x 10"), "while enter jump");
        check(compiledWhile.contains("jump __ls_stmt_3 always x false"), "while exit jump");
        check(compiledWhile.contains("__ls_while_body_0:"), "while body label");
        check(compiledWhile.contains("jump __ls_stmt_0 always x false"), "while loop-back jump");

        // legacy single-value while still parses: while a { set y 1 }
        String legacyWhile = "whilebegin a 2\nset y 1\nblockend\n";
        String compiledLegacyWhile = SugarCompiler.compile(legacyWhile);
        System.out.println("=== legacy while ===\n" + compiledLegacyWhile);
        check(compiledLegacyWhile.contains("jump __ls_while_body_0 notEqual a false"), "legacy while maps to != false");

        // Expr mode: the full condition lowers to op instructions plus a native jump.
        String exprIf = "ifbegin expr \"a > 5 && ready\" 4\nset x 1\nelif expr \"fallback != 0\"\nset x 2\nblockend\n";
        String compiledExpr = SugarCompiler.compile(exprIf);
        System.out.println("=== if/elif expr ===\n" + compiledExpr);
        check(compiledExpr.contains("op greaterThan __ls_cond_0 a 5"), "expr if comparison emitted");
        check(compiledExpr.contains("op land __ls_cond_0 __ls_cond_0 ready"), "expr if boolean operator emitted");
        check(compiledExpr.contains("jump __ls_if_branch_2 equal __ls_cond_0 0"), "expr if false branch emitted");
        check(compiledExpr.contains("op notEqual __ls_cond_2 fallback 0"), "expr elif comparison emitted");
        check(compiledExpr.contains("jump __ls_stmt_5 equal __ls_cond_2 0"), "expr elif false branch emitted");

        // while with Expr condition: the condition lowers to op + a notEqual enter jump
        String exprWhile = "whilebegin expr \"x < 10 && ready\" 2\nset y 1\nblockend\n";
        String compiledExprWhile = SugarCompiler.compile(exprWhile);
        System.out.println("=== while expr ===\n" + compiledExprWhile);
        check(compiledExprWhile.contains("op lessThan __ls_cond_0 x 10"), "expr while comparison emitted");
        check(compiledExprWhile.contains("jump __ls_while_body_0 notEqual __ls_cond_0 0"), "expr while enter jump");

        // for with Expr condition
        String exprFor = "forbegin i 0 1 expr \"i < count && go\" 2\nset y 1\nblockend\n";
        String compiledExprFor = SugarCompiler.compile(exprFor);
        System.out.println("=== for expr ===\n" + compiledExprFor);
        check(compiledExprFor.contains("op lessThan __ls_cond_0 i count"), "expr for comparison emitted");
        check(compiledExprFor.contains("jump __ls_for_body_0 notEqual __ls_cond_0 0"), "expr for body jump");

        // Function body with an Expr condition referencing a function parameter:
        // rewriteBody must rewrite the parameter inside the condition expression,
        // otherwise the compiled call would compare against the outer variable.
        // statements: 0=funcdef, 1=ifbegin expr, 2=set, 3=blockend(if), 4=blockend(func)
        String funcExpr = "funcdef f a 4\nifbegin expr \"a > 0 && ready\" 3\nset x 1\nblockend\nblockend\nfunccall f \"threshold\" ~\n";
        String compiledFuncExpr = SugarCompiler.compile(funcExpr);
        System.out.println("=== func body expr ===\n" + compiledFuncExpr);
        check(compiledFuncExpr.contains("op greaterThan __ls_cond_func_f_0 a 0"), "func Expr condition compiled with function-local temp");
        check(compiledFuncExpr.contains("jump __ls_func_f_stmt_3 equal __ls_cond_func_f_0 0"), "func Expr false branch emitted");
        check(compiledFuncExpr.contains("set a threshold"), "func Expr condition parameter bound to call argument");

        // invalid: elif outside if must throw
        expectFailure("elif a equal 0\n", "elif outside if rejected");

        // round-trip: statement.write() -> LAssembler.read() (what MindustryX toggleComment relies on)
        SugarStatements.IfBeginStatement ifBegin = new SugarStatements.IfBeginStatement();
        ifBegin.value = "a";
        ifBegin.op = ConditionOp.greaterThan;
        ifBegin.compare = "5";
        ifBegin.destIndex = 3;
        StringBuilder text = new StringBuilder();
        ifBegin.write(text);
        check(text.toString().equals("ifbegin a greaterThan 5 3"), "ifbegin serializes via write()");

        // for/while Expr serialization round-trip
        SugarStatements.ForBeginStatement forExpr = new SugarStatements.ForBeginStatement();
        forExpr.variable = "i";
        forExpr.initial = "0";
        forExpr.step = "1";
        forExpr.expressionMode = true;
        forExpr.conditionExpr = "i < count && go";
        forExpr.destIndex = 4;
        StringBuilder forText = new StringBuilder();
        forExpr.write(forText);
        check(forText.toString().equals("forbegin i 0 1 expr \"i < count && go\" 4"), "for expr serializes via write()");
        LStatement parsedFor = LAssembler.read(forText.toString(), true).first();
        check(parsedFor instanceof SugarStatements.ForBeginStatement, "for expr round-trips back to ForBeginStatement");
        SugarStatements.ForBeginStatement parsedForExpr = (SugarStatements.ForBeginStatement)parsedFor;
        check(parsedForExpr.expressionMode && parsedForExpr.conditionExpr.equals("i < count && go") && parsedForExpr.destIndex == 4,
            "for expr fields survive round-trip");

        SugarStatements.WhileBeginStatement whileExpr = new SugarStatements.WhileBeginStatement();
        whileExpr.expressionMode = true;
        whileExpr.conditionExpr = "x < 10 && ready";
        whileExpr.destIndex = 2;
        StringBuilder whileText = new StringBuilder();
        whileExpr.write(whileText);
        check(whileText.toString().equals("whilebegin expr \"x < 10 && ready\" 2"), "while expr serializes via write()");
        LStatement parsedWhile = LAssembler.read(whileText.toString(), true).first();
        check(parsedWhile instanceof SugarStatements.WhileBeginStatement, "while expr round-trips back to WhileBeginStatement");
        SugarStatements.WhileBeginStatement parsedWhileExpr = (SugarStatements.WhileBeginStatement)parsedWhile;
        check(parsedWhileExpr.expressionMode && parsedWhileExpr.conditionExpr.equals("x < 10 && ready") && parsedWhileExpr.destIndex == 2,
            "while expr fields survive round-trip");

        SugarStatements.IfBeginStatement exprStatement = new SugarStatements.IfBeginStatement();
        exprStatement.expressionMode = true;
        exprStatement.conditionExpr = "a > 5 && ready";
        exprStatement.destIndex = 3;
        StringBuilder exprText = new StringBuilder();
        exprStatement.write(exprText);
        check(exprText.toString().equals("ifbegin expr \"a > 5 && ready\" 3"), "expr ifbegin serializes via write()");
        LStatement parsedExpr = LAssembler.read(exprText.toString(), true).first();
        check(parsedExpr instanceof SugarStatements.IfBeginStatement, "expr ifbegin round-trips back to IfBeginStatement");
        SugarStatements.IfBeginStatement parsedExprIf = (SugarStatements.IfBeginStatement)parsedExpr;
        check(parsedExprIf.expressionMode && parsedExprIf.conditionExpr.equals("a > 5 && ready") && parsedExprIf.destIndex == 3,
            "expr ifbegin fields survive round-trip");
        LStatement parsed = LAssembler.read(text.toString(), true).first();
        check(parsed instanceof SugarStatements.IfBeginStatement, "ifbegin round-trips back to IfBeginStatement");
        SugarStatements.IfBeginStatement parsedIf = (SugarStatements.IfBeginStatement)parsed;
        check(parsedIf.value.equals("a") && parsedIf.op == ConditionOp.greaterThan && parsedIf.compare.equals("5") && parsedIf.destIndex == 3,
            "ifbegin fields survive round-trip");

        System.out.println("ALL CHECKS PASSED");
    }

    private static void expectFailure(String sugar, String name){
        try{
            SugarCompiler.compile(sugar);
        }catch(IllegalArgumentException expected){
            System.out.println("ok: " + name + " -> " + expected.getMessage());
            return;
        }
        throw new AssertionError("FAILED: " + name + " (no exception thrown)");
    }

    private static void registerStatements(){
        LogicIO.allStatements.add(SugarStatements.ForBeginStatement::new);
        LogicIO.allStatements.add(SugarStatements.WhileBeginStatement::new);
        LogicIO.allStatements.add(SugarStatements.SwitchBeginStatement::new);
        LogicIO.allStatements.add(IfBeginStatement::new);
        LogicIO.allStatements.add(SugarStatements.CaseStatement::new);
        LogicIO.allStatements.add(SugarStatements.ElseIfStatement::new);
        LogicIO.allStatements.add(SugarStatements.ElseStatement::new);
        LogicIO.allStatements.add(SugarStatements.BreakStatement::new);
        LogicIO.allStatements.add(SugarStatements.ContinueStatement::new);
        LogicIO.allStatements.add(SugarStatements.BlockEndStatement::new);
        LogicIO.allStatements.add(SugarStatements.FuncDefStatement::new);
        LogicIO.allStatements.add(SugarStatements.FuncCallStatement::new);
        LogicIO.allStatements.add(SugarStatements.ReturnStatement::new);

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
        LAssembler.customParsers.put("break", tokens -> new SugarStatements.BreakStatement());
        LAssembler.customParsers.put("continue", tokens -> new SugarStatements.ContinueStatement());
        LAssembler.customParsers.put("blockend", tokens -> new SugarStatements.BlockEndStatement());
        LAssembler.customParsers.put("funcdef", SugarStatements::parseFuncDef);
        LAssembler.customParsers.put("funcdefc", tokens -> SugarStatements.parseFuncDef(tokens, true));
        LAssembler.customParsers.put("funccall", SugarStatements::parseFuncCall);
        LAssembler.customParsers.put("return", SugarStatements::parseReturn);
    }

    private static void check(boolean condition, String name){
        if(!condition) throw new AssertionError("FAILED: " + name);
        System.out.println("ok: " + name);
    }
}
