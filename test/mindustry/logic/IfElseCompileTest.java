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
